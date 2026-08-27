package com.ciontek.mdblib

import android.hardware.mdbSlave.MdbSlave
import android.util.Log

/**
 * Kotlin wrapper around the CM30 library's `android.hardware.mdbSlave.MdbSlave` transport API.
 * The underlying library only moves raw bytes + a bare ACK/NAK/RET handshake; this wrapper just
 * gives that the same shape in Kotlin (nullable results instead of raw int/error codes).
 *
 * Internal to this library module — app integrators use [MdbSlave] (the public class), not this.
 */
internal object MdbSlaveWrapper {

    private const val TAG = "MdbSlaveWrapper"

    //----------------------------------- MDB COMMAND PAYLOADS -----------------------------------//
    // Response byte arrays, named after the function that sends each one (see MDB COMMANDS below).
    // These hold DATA ONLY — no checksum byte. The checksum is computed fresh and appended by
    // sendAndLog() on every send, since sendResponseData() sends exactly the bytes it's given with
    // no auto-computed checksum of its own.

    private val jstReset = byteArrayOf(0x00)
    private val can = byteArrayOf(0x08)

    /** Response ID 0x01 (READER CONFIG DATA) — indices 1 (feature level) and 7 (misc options) are
     * mutated in place from the level/mode arguments before each send. */
    private val readerConfigData =
        byteArrayOf(0x01, 0x02, 0x19, 0x78, 0x01, 0x02, 0xE8.toByte(), 0x0B)

    /** Response ID 0x09 (PERIPHERAL ID) — "CAS" manufacturer/serial/model/software version. */
    private val readerConfigInfo = byteArrayOf(
        0x09, 0x43, 0x41, 0x53, 0x30, 0x30, 0x30, 0x30,
        0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31,
        0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
        0x30, 0x30, 0x30, 0x31, 0x00, 0x01
    )

    /** VEND APPROVED response, response ID + price high/low — mutated in place before each send. */
    private val vendApproved = byteArrayOf(0x05, 0x00, 0x00)

    private val vendDenied = byteArrayOf(0x06)
    private val endSession = byteArrayOf(0x07)
    private val sessionCancel = byteArrayOf(0x04)

    /** BEGIN SESSION response, response ID + funds available. Funds default to 0xFFFF — the MDB
     * spec's "unknown/token balance" sentinel. */
    private val sessionBegin = byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte())

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

    /** Reads up to 36 bytes from the VMC. Returns null if nothing was received. */
    fun receiveCommand(): ByteArray? {
        val buffer = ByteArray(36)
        val len = mdbSlave.receiveCommand(buffer)
        return if (len > 0) buffer.copyOf(len) else null
    }

    fun sendAck(): Boolean = mdbSlave.sendAnswer(0x00) > 0

    /** Sends up to 36 bytes back to the VMC exactly as given — no checksum is computed here. */
    fun sendResponseData(data: ByteArray): Pair<Boolean, Int> {
        val respCode = IntArray(1)
        val ret = mdbSlave.sendResponseData(data, data.size, respCode)
        return (ret > 0) to respCode[0]
    }

    /**
     * Builds the full packet via [withChecksum] (data + computed CHK byte appended as the actual
     * last byte), logs it, and sends that complete packet.
     */
    private fun sendAndLog(name: String, data: ByteArray) {
        val packet = withChecksum(data)
        Log.d(TAG, "$name: ${packet.joinToString(", ") { "0x%02X".format(it) }}")
        sendResponseData(packet)
    }

    //----------------------------------- MDB COMMANDS -----------------------------------//
    // ACK is a bare handshake byte via sendAck() — no checksum, per MDB spec. Every other command
    // below builds its data array, then sendAndLog() computes the CHK byte and appends it as the
    // real last byte of the packet before sending.

    /** Bare ACK handshake, no payload/checksum — sent via sendAnswer(). Returns success/failure. */
    fun ack(): Boolean = sendAck()

    /** POLL response: JUST RESET (response ID 0x00). */
    fun jstReset() {
        sendAndLog("JUST_RESET", jstReset)
    }

    /** POLL response: CANCELED (response ID 0x08). */
    fun can() {
        sendAndLog("CAN", can)
    }

    /**
     * POLL response: READER CONFIG DATA (response ID 0x01). [level] sets feature level (byte 1),
     * [mode] sets misc options (byte 7).
     */
    fun readerConfigData(level: Int, mode: Int) {
        readerConfigData[1] = level.toByte()
        readerConfigData[7] = mode.toByte()
        sendAndLog("READER_CONFIG_DATA", readerConfigData)
    }

    /** POLL response: PERIPHERAL ID (response ID 0x09). */
    fun readerConfigInfo() {
        sendAndLog("READER_CONFIG_INFO", readerConfigInfo)
    }

    /** POLL response: VEND APPROVED (response ID 0x05), price high/low bytes 0-255. */
    fun vendApproved(priceHigh: Int, priceLow: Int) {
        vendApproved[1] = priceHigh.toByte()
        vendApproved[2] = priceLow.toByte()
        sendAndLog("VEND_APPROVED", vendApproved)
    }

    /** POLL response: VEND DENIED (response ID 0x06). */
    fun vendDenied() {
        sendAndLog("VEND_DENIED", vendDenied)
    }

    /** POLL response: END SESSION (response ID 0x07). */
    fun endSession() {
        sendAndLog("END_SESSION", endSession)
    }

    /** POLL response: SESSION CANCEL REQUEST (response ID 0x04). */
    fun sessionCancel() {
        sendAndLog("SESSION_CANCEL", sessionCancel)
    }

    /** POLL response: BEGIN SESSION (response ID 0x03). */
    fun sessionBegin() {
        sendAndLog("SESSION_BEGIN", sessionBegin)
    }

    /**
     * MDB's "checksum" (CHK): 8-bit sum of all bytes in the packet, carry discarded, sent as the
     * final byte with the MODE bit set. Not a real CRC. ACK/NAK/RET are single bytes with no CHK.
     */
    fun calculateChecksum(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return (sum and 0xFF).toByte()
    }

    /** Returns [data] with its CHK byte computed and appended as the actual last byte. */
    fun withChecksum(data: ByteArray): ByteArray {
        return data + calculateChecksum(data)
    }
}
