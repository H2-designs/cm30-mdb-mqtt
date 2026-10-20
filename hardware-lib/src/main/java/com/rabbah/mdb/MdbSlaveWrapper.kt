package com.rabbah.mdb

import android.hardware.mdbSlave.MdbSlave
import android.util.Log

/**
 * Kotlin wrapper around the CM30 vendor library's `android.hardware.mdbSlave.MdbSlave` transport.
 * The underlying library only moves raw bytes plus a bare ACK/NAK/RET handshake; this wrapper
 * adds the MDB checksum and fetches every payload fresh from [MdbConfigStore] on each send, which
 * is what makes payloads remotely editable with no rebuild. Internal to mdb-lib: apps talk to
 * [MdbLib], never to this.
 */
internal object MdbSlaveWrapper {

    private const val TAG = "MdbSlaveWrapper"

    private val mdbSlave: MdbSlave = MdbSlave.getInstance()

    var isOpen: Boolean = false
        private set

    /** Powers on and opens the MDB slave port. */
    fun open(): Boolean {
        isOpen = mdbSlave.open() == MdbSlave.SUCCESS
        return isOpen
    }

    /** Closes the port and powers it off. */
    fun close() {
        mdbSlave.close()
        isOpen = false
    }

    /** Reads up to 36 bytes from the VMC. Returns null if nothing was received. The scratch
     * buffer is reused across calls (only the bus thread calls this, ~1000x/s while idle) so the
     * receive path allocates nothing on empty reads - GC pauses here are response-window misses. */
    private val rxBuffer = ByteArray(36)
    fun receiveCommand(): ByteArray? {
        val len = mdbSlave.receiveCommand(rxBuffer)
        return if (len > 0) rxBuffer.copyOf(len) else null
    }

    fun sendAck(): Boolean = mdbSlave.sendAnswer(0x00) > 0

    /** Sends up to 36 bytes back to the VMC exactly as given - no checksum is computed here. */
    fun sendResponseData(data: ByteArray): Pair<Boolean, Int> {
        val respCode = IntArray(1)
        val ret = mdbSlave.sendResponseData(data, data.size, respCode)
        return (ret > 0) to respCode[0]
    }

    /**
     * Builds the full packet via [withChecksum] (data + computed CHK byte appended as the actual
     * last byte), sends it, THEN logs it. The send must come first: the VMC's reply window is a
     * few milliseconds, and formatting a 35-byte hex string plus a logcat write before the send
     * was enough for the VMC to discard our READER CONFIG responses and loop the handshake.
     */
    private fun sendAndLog(name: String, data: ByteArray) {
        val packet = withChecksum(data)
        sendResponseData(packet)
        Log.d(TAG, "$name: ${packet.joinToString(", ") { "0x%02X".format(it) }}")
    }

    //----------------------------------- MDB COMMANDS -----------------------------------//
    // ACK is a bare handshake byte via sendAck() - no checksum, per MDB spec (ACK/NAK/RET carry
    // none). Every other command builds its data array from MdbConfigStore, then sendAndLog()
    // computes the CHK byte and appends it as the real last byte before sending.

    /** Bare ACK handshake, no payload/checksum. Returns success/failure. */
    fun ack(): Boolean = sendAck()

    /** POLL response: JUST RESET (response ID 0x00). */
    fun jstReset() {
        sendAndLog("JUST_RESET", MdbConfigStore.get("JUST_RESET"))
    }

    /** POLL response: CANCELED (response ID 0x08). */
    fun can() {
        sendAndLog("CAN", MdbConfigStore.get("CAN"))
    }

    /**
     * POLL response: READER CONFIG DATA (response ID 0x01). [level] sets feature level (byte 1),
     * [mode] sets misc options (byte 7) - every other byte comes from MdbConfigStore.
     */
    fun readerConfigData(level: Int, mode: Int) {
        val data = MdbConfigStore.get("READER_CONFIG_DATA")
        data[1] = level.toByte()
        data[7] = mode.toByte()
        sendAndLog("READER_CONFIG_DATA", data)
    }

    /** POLL response: PERIPHERAL ID (response ID 0x09). [level] selects Level 2's 29-byte payload
     * or Level 3's 33-byte one (with the 4 extra optional-feature-bits bytes). */
    fun readerConfigInfo(level: Int) {
        val name = if (level >= 3) "READER_CONFIG_INFO_L3" else "READER_CONFIG_INFO"
        sendAndLog(name, MdbConfigStore.get(name))
    }

    /** POLL response: VEND APPROVED (response ID 0x05), price high/low bytes 0-255. */
    fun vendApproved(priceHigh: Int, priceLow: Int) {
        val data = MdbConfigStore.get("VEND_APPROVED")
        data[1] = priceHigh.toByte()
        data[2] = priceLow.toByte()
        sendAndLog("VEND_APPROVED", data)
    }

    /** POLL response: VEND DENIED (response ID 0x06). */
    fun vendDenied() {
        sendAndLog("VEND_DENIED", MdbConfigStore.get("VEND_DENIED"))
    }

    /** POLL response: END SESSION (response ID 0x07). */
    fun endSession() {
        sendAndLog("END_SESSION", MdbConfigStore.get("END_SESSION"))
    }

    /** POLL response: SESSION CANCEL REQUEST (response ID 0x04). */
    fun sessionCancel() {
        sendAndLog("SESSION_CANCEL", MdbConfigStore.get("SESSION_CANCEL"))
    }

    /** POLL response: BEGIN SESSION (response ID 0x03). [level] selects Level 1's 3-byte payload
     * or Level 2/3's 10-byte one ("SESSION_BEGIN_L2"). */
    fun sessionBegin(level: Int) {
        val name = if (level >= 2) "SESSION_BEGIN_L2" else "SESSION_BEGIN"
        sendAndLog(name, MdbConfigStore.get(name))
    }

    /**
     * POLL response: REVALUE LIMIT AMOUNT (response ID 0x0F), reply to REVALUE LIMIT REQUEST
     * (15H 01H). [limitHigh]/[limitLow] are the max revalue amount, high/low bytes 0-255.
     */
    fun revalueLimit(limitHigh: Int, limitLow: Int) {
        val data = MdbConfigStore.get("REVALUE_LIMIT")
        data[1] = limitHigh.toByte()
        data[2] = limitLow.toByte()
        sendAndLog("REVALUE_LIMIT", data)
    }

    /** POLL response: REVALUE LIMIT AMOUNT exactly as configured - the limit bytes come straight
     * from the REVALUE_LIMIT config, no runtime override. */
    fun revalueLimit() {
        sendAndLog("REVALUE_LIMIT", MdbConfigStore.get("REVALUE_LIMIT"))
    }

    /** POLL response: REVALUE DENIED (response ID 0x0E), reply to REVALUE REQUEST (15H 00H). */
    fun revalueDenied() {
        sendAndLog("REVALUE_DENIED", MdbConfigStore.get("REVALUE_DENIED"))
    }

    /**
     * MDB's "checksum" (CHK) per the MDB spec: 8-bit sum of all bytes in the packet, carry
     * discarded, sent as the final byte with the MODE bit set. Confirmed against the spec's
     * worked example: 0x11+0x01+0x05+0xDC+0x00+0x64 = 0x157 -> mod 256 = 0x57. Not a real CRC.
     * ACK/NAK/RET are single bytes with no CHK.
     */
    fun calculateChecksum(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return (sum and 0xFF).toByte()
    }

    /** Returns [data] with its CHK byte computed and appended as the actual last byte - the full
     * packet exactly as it goes out on the wire. */
    fun withChecksum(data: ByteArray): ByteArray {
        return data + calculateChecksum(data)
    }
}
