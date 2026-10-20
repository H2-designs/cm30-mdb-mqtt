package com.rabbah.mdb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.rs232.RS232
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RS232 support over the CM30's serial port (vendor class android.hardware.rs232.RS232) -
 * the third hardware pillar next to the MDB engine and PulseLib.
 *
 * THE MODEL - a configurable RX -> TX rule table (up to [MAX_RULES] entries): the machine
 * sends a hex frame, the library matches it against the rules IN ORDER and immediately writes
 * the matched rule's reply back on the wire. Rules are added at runtime (from code or from the
 * dashboard/backend as JSON), persisted across restarts, and hot-reloaded - "tx this when rx
 * this", and it starts working:
 *
 *   Rs232Lib.setRulesJson(""\"[
 *     {"name":"STATUS",       "rx":"FF FF FF FF FF",    "tx":"01 00"},
 *     {"name":"VEND_REQUEST", "rx":"F1 05 ?? ?? 0D",    "tx":"06",  "priceHi":2,  "priceLo":3},
 *     {"name":"VEND_VARLEN",  "rx":"F2 *",              "tx":"06",  "priceHi":-3, "priceLo":-2}
 *   ]""\")
 *   Rs232Lib.open(baud = 9600)
 *
 * MATCHING: a rule's rx is hex bytes where "??" matches ANY ONE byte - that is how a VEND
 * REQUEST whose price changes per sale still matches one rule. A single trailing "*" matches
 * ANY NUMBER of remaining bytes (including none), for frames whose length varies - e.g.
 * "F1 05 *" matches every frame starting F1 05. Without "*" the frame length must equal the
 * pattern length. First matching rule wins.
 *
 * VEND REQUEST rules: set [Rs232Rule.priceHiIndex]/[priceLoIndex] to the byte positions
 * carrying the price high/low bytes - 0-based from the frame START, or NEGATIVE to count from
 * the frame END (-1 = last byte, -2 = second-to-last...), which is how a price at a fixed
 * distance from the end survives a variable-length frame. On match the price (hi*256 + lo) is
 * extracted and [vendRequestListener] fires with it - approve by sending your approval frame
 * via [sendHex] (or configure the rule's tx to auto-ack immediately; both work together).
 *
 * VEND LISTENER (8.1.0): a rule may carry "event":"vendRequest" | "vendSuccess" | "vendFailure" |
 * "vendCancel". On match the library fires the SAME three-callback [HardwareLib.VendListener]
 * the MDB engine uses - onVendRequest(amount, minorUnits, item=-1) with the extracted price
 * (minor units; amount = price / 100.0), then exactly one onVendSuccess(-1) or onVendFailure()
 * per request (reason FAILED for vendFailure, CANCELLED_BY_VMC for vendCancel; a second request
 * while one is open closes the first with SESSION_ENDED). The app answers the machine with
 * [sendHex] / [sendXorFrame] - RS232 has no approve/deny command of its own.
 *
 * FRAMING: RS232 is a raw byte stream with no frame markers, so frames are cut by silence -
 * bytes that arrive within [frameGapMs] of each other belong to one frame; a gap ends it.
 *
 * Every exchange is reported as "[rs232] ..." lines through the normal HardwareLib log
 * pipeline (screen + dashboard), and [exchangeListener] delivers the structured version.
 * No CM30 runtime degrades to reported failures and false returns, never a crash.
 */
object Rs232Lib {

    const val MAX_RULES = 20

    /** "No price byte here" sentinel for [Rs232Rule.priceHiIndex]/[priceLoIndex] - needed
     * because real negative indexes (-1, -2...) mean "count from the frame end". */
    const val NO_INDEX = Int.MIN_VALUE

    /** One RX -> TX mapping. [rx] is space-separated hex, "??" = any one byte, a trailing "*"
     * = any remaining bytes (variable-length frames); [tx] is the reply hex ("" = match +
     * report + listener, but send nothing). Two ways to mark the vend price in the frame
     * ([NO_INDEX] = not used; >= 0 counts from the frame start, negative from the frame end,
     * -1 = last byte):
     *  - BINARY: [priceHiIndex]/[priceLoIndex] - two bytes, price = hi*256 + lo.
     *  - ASCII: [amountStartIndex]/[amountEndIndex] - the price is digit TEXT inside the frame
     *    (e.g. bytes "31 35 30 30 30 30" = "150000" = 150000); end is EXCLUSIVE, so
     *    amountEnd -1 means "up to but not including the last byte" (skips a trailing CRC).
     *    Non-digit characters (a decimal point) are ignored. */
    data class Rs232Rule(
        val name: String,
        val rx: String,
        val tx: String,
        val priceHiIndex: Int = NO_INDEX,
        val priceLoIndex: Int = NO_INDEX,
        val amountStartIndex: Int = NO_INDEX,
        val amountEndIndex: Int = NO_INDEX,
        /** 8.1.0: which [HardwareLib.VendListener] callback this rule fires when it matches - one of
         *  [EVENT_VEND_REQUEST] (needs a price in the frame), [EVENT_VEND_SUCCESS], [EVENT_VEND_FAILURE]
         *  or [EVENT_VEND_CANCEL]; null = report only. Same three callbacks and the same
         *  "exactly one success or failure per request" guarantee as the MDB engine. */
        val event: String? = null
    )

    /** Rule "event" values (8.1.0). */
    const val EVENT_VEND_REQUEST = "vendRequest"
    const val EVENT_VEND_SUCCESS = "vendSuccess"
    const val EVENT_VEND_FAILURE = "vendFailure"
    const val EVENT_VEND_CANCEL = "vendCancel"
    val EVENTS: Set<String> = setOf(EVENT_VEND_REQUEST, EVENT_VEND_SUCCESS, EVENT_VEND_FAILURE, EVENT_VEND_CANCEL)

    /** One RS232 event, MDB-style (7.22.0): EVERY coded event (137-143) arrives here - frames,
     * manual TX, CRC discards, port open/close, rule loads - with its stable [code] and
     * codebook-ready [params], so ONE listener can feed a logging pipeline AND business logic,
     * exactly like HardwareLib.exchangeListener does for MDB.
     *
     * Frame fields ([rxHex]/[ruleName]/[txHex]/[price]) are populated for RX_MATCHED/
     * RX_UNMATCHED/CRC_DISCARDED/TX; for port/rules events they hold ""/null/-1.
     *  - [code]/[eventName]: the Rs232Cmd schema entry (137-143, same numbers as the wire)
     *  - [params]: positional, identical to the wire p[] arrays
     *  - [message]: the human sentence, rendered from the same template the dashboard uses
     *  - [ruleName]: null = no rule matched (UNMATCHED) - null IS information
     *  - [txHex]: null = nothing was written to the machine
     *  - [price]: minor units; -1 = this event carries no price */
    data class Rs232Exchange(
        val rxHex: String,
        val ruleName: String?,
        val txHex: String?,
        val price: Int,
        val timestampMs: Long,
        val code: Int = 0,
        val eventName: String = "",
        val params: List<String> = emptyList(),
        val message: String = ""
    )

    /** Builds the MDB-style event and fires [exchangeListener] - one path for all 137-143. */
    private fun emitExchange(
        cmd: Rs232Cmd,
        params: List<String>,
        rxHex: String = "",
        ruleName: String? = null,
        txHex: String? = null,
        price: Int = -1
    ) {
        try {
            exchangeListener?.invoke(
                Rs232Exchange(
                    rxHex = rxHex, ruleName = ruleName, txHex = txHex, price = price,
                    timestampMs = System.currentTimeMillis(),
                    code = cmd.code, eventName = cmd.eventName, params = params,
                    message = cmd.format(params)
                )
            )
        } catch (_: Throwable) {
        }
    }

    /** Fires on every received frame (matched or not), on the reader thread - return fast. */
    @JvmStatic
    @Volatile
    var exchangeListener: ((Rs232Exchange) -> Unit)? = null

    /** Fires when a rule with price byte positions matches: the extracted price (hi*256+lo)
     * and the full frame. Runs on the reader thread - hop to your own thread for gateway work,
     * then answer the machine with [sendHex]. */
    @JvmStatic
    @Volatile
    var vendRequestListener: ((price: Int, frameHex: String) -> Unit)? = null

    /** Silence that ends a frame, in ms - the reply goes out this long after the machine's
     * last byte. Lower it for machines with a tight response window. */
    @JvmStatic
    @Volatile
    var frameGapMs: Int = 1000

    private const val PREFS_NAME = "rs232_rules"
    private const val KEY_RULES = "rulesJson"
    private const val KEY_CRC = "crcCheck"
    private const val READ_BUF = 256

    private var prefs: SharedPreferences? = null
    @Volatile private var rules: List<Rs232Rule> = emptyList()

    private val running = AtomicBoolean(false)
    private var serial: RS232? = null

    /** Bumped on every open() AND close(). The reader thread captures the value it was born
     * with and exits the moment it no longer matches, so a rapid stop()->open() sequence can
     * never leave the OLD reader alive next to the new one (two readers on one port steal
     * alternating bytes - observed on real hardware), and a stale reader dying on its closed
     * port can never shoot down the running flag of the NEW reader. */
    private val readerGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while the port is open and the reader loop is running. */
    val isOpen: Boolean
        get() = running.get()

    /** When on, every received frame's LAST byte must equal the XOR of all preceding bytes
     * (the card-reader protocol's CRC). A frame that fails is DISCARDED - no rule match, no
     * reply, no listeners - exactly as the protocol document requires ("discarded directly
     * without response"); only a diagnostic log line is reported. Default off. */
    @JvmStatic
    @Volatile
    var crcCheckEnabled: Boolean = false
        private set

    /** Turns incoming XOR CRC validation on or off (persisted across restarts). */
    @JvmStatic
    fun setCrcCheck(enabled: Boolean): Boolean {
        crcCheckEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_CRC, enabled)?.apply()
        HardwareLib.reportLine("[rs232] crc check ${if (enabled) "ON - bad-CRC frames are discarded" else "OFF"}")
        return true
    }

    /** Restores persisted rules. Called by [HardwareLib.init] automatically; safe to call
     * directly when using Rs232Lib standalone. */
    @JvmStatic
    fun init(context: Context): Boolean {
        if (prefs != null) return true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        crcCheckEnabled = prefs?.getBoolean(KEY_CRC, false) ?: false
        prefs?.getString(KEY_RULES, null)?.let { saved ->
            val err = setRulesJson(saved, persist = false)
            if (err != null) HardwareLib.reportLine("[rs232] saved rules ignored: $err")
        }
        return true
    }

    // ------------------------------------ rules ------------------------------------

    /**
     * Replaces the whole rule table from JSON - the same payload the dashboard/backend sends:
     *
     *   [ {"name":"STATUS","rx":"FF FF FF FF FF","tx":"01 00"},
     *     {"name":"VEND","rx":"F1 05 ?? ?? 0D","tx":"06","priceHi":2,"priceLo":3} ]
     *
     * Up to [MAX_RULES] entries; each rx must be valid hex/?? tokens, price indexes (when
     * given) must point inside the rx pattern. Persisted and applied to the NEXT frame - no
     * reopen needed. Returns null on success, else a human-readable error (table unchanged).
     */
    @JvmStatic
    @JvmOverloads
    fun setRulesJson(json: String, persist: Boolean = true): String? {
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return "invalid JSON array: ${e.message}"
        }
        if (arr.length() > MAX_RULES) return "too many rules: ${arr.length()}, maximum is $MAX_RULES"
        val parsed = ArrayList<Rs232Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return "rule $i is not an object"
            val name = o.optString("name").ifEmpty { "RULE_$i" }
            val rx = o.optString("rx")
            val tx = o.optString("tx")
            val hi = if (o.has("priceHi")) o.optInt("priceHi", NO_INDEX) else NO_INDEX
            val lo = if (o.has("priceLo")) o.optInt("priceLo", NO_INDEX) else NO_INDEX
            val aStart = if (o.has("amountStart")) o.optInt("amountStart", NO_INDEX) else NO_INDEX
            val aEnd = if (o.has("amountEnd")) o.optInt("amountEnd", NO_INDEX) else NO_INDEX
            val event = o.optString("event").trim().ifEmpty { null }
            if (event != null && event !in EVENTS) return "rule $name: event must be one of " + EVENTS.joinToString("|") + ", got $event"
            if (event == EVENT_VEND_REQUEST && hi == NO_INDEX && aStart == NO_INDEX) return "rule $name: event vendRequest needs priceHi/priceLo or amountStart/amountEnd"
            val pattern = parsePattern(rx) ?: return "rule $name: rx is not valid hex/??/* tokens: $rx"
            if (tx.isNotBlank() && parseHex(tx) == null) return "rule $name: tx is not valid hex: $tx"
            if ((hi == NO_INDEX) != (lo == NO_INDEX)) return "rule $name: priceHi and priceLo must be set together"
            if ((aStart == NO_INDEX) != (aEnd == NO_INDEX)) return "rule $name: amountStart and amountEnd must be set together"
            if (hi != NO_INDEX && aStart != NO_INDEX) return "rule $name: use priceHi/priceLo OR amountStart/amountEnd, not both"
            // A fixed-length pattern matches frames of exactly tokens.size bytes, so both price
            // indexes must land inside it. An open-ended ("*") pattern matches longer frames
            // too - any index is accepted here and bounds-checked per frame at runtime.
            val minLen = pattern.tokens.size
            if (!pattern.openEnded) {
                for (idx in intArrayOf(hi, lo, aStart)) {
                    if (idx == NO_INDEX) continue
                    val fits = if (idx >= 0) idx < minLen else -idx <= minLen
                    if (!fits) return "rule $name: price index $idx outside the $minLen-byte rx pattern"
                }
            }
            parsed.add(Rs232Rule(name, normalizePattern(rx), tx.trim().uppercase(), hi, lo, aStart, aEnd, event))
        }
        rules = parsed
        if (persist) prefs?.edit()?.putString(KEY_RULES, rulesJson())?.apply()
        emitExchange(Rs232Cmd.RS232_RULES_LOADED, listOf(parsed.size.toString()))
        val coded = HardwareLib.dispatchRs232Event("RS232_RULES_LOADED", listOf(parsed.size.toString()))
        val line = "[rs232] ${parsed.size} rule(s) loaded"
        if (coded) HardwareLib.reportLocal(line) else HardwareLib.reportLine(line)
        return null
    }

    /** The current rule table as JSON (the same shape [setRulesJson] accepts). */
    @JvmStatic
    fun rulesJson(): String {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("rx", r.rx)
                put("tx", r.tx)
                if (r.priceHiIndex != NO_INDEX) {
                    put("priceHi", r.priceHiIndex)
                    put("priceLo", r.priceLoIndex)
                }
                if (r.amountStartIndex != NO_INDEX) {
                    put("amountStart", r.amountStartIndex)
                    put("amountEnd", r.amountEndIndex)
                }
                if (r.event != null) put("event", r.event)
            })
        }
        return arr.toString()
    }

    /** Removes every rule (persisted). Frames then only report as UNMATCHED. */
    @JvmStatic
    fun clearRules(): Boolean {
        rules = emptyList()
        prefs?.edit()?.remove(KEY_RULES)?.apply()
        HardwareLib.reportLine("[rs232] rules cleared")
        return true
    }

    // ------------------------------------ port ------------------------------------

    /**
     * Opens the serial port and starts the reader loop. Defaults: 9600 baud, 8 data bits,
     * 1 stop bit, no parity ('N'), no flow control ('N') - override any of them. Returns true
     * when the port opened; false (with a reported line) otherwise. Idempotent while open.
     */
    @JvmStatic
    @JvmOverloads
    fun open(
        baud: Int = 9600,
        dataBits: Int = 8,
        stopBits: Int = 1,
        parity: Char = 'N',
        flowControl: Char = 'N',
        port: Int = 0
    ): Boolean {
        if (running.getAndSet(true)) return true
        val s = try {
            RS232()
        } catch (t: Throwable) {
            running.set(false)
            HardwareLib.reportLine("[rs232] open failed - CM30 RS232 runtime unavailable (${t.javaClass.simpleName})")
            return false
        }
        val ret = try {
            s.RS232Open(port, baud, dataBits, stopBits, parity, flowControl)
        } catch (t: Throwable) {
            running.set(false)
            HardwareLib.reportLine("[rs232] open failed: ${t.javaClass.simpleName} ${t.message}")
            return false
        }
        if (ret < 0) {
            running.set(false)
            HardwareLib.reportLine("[rs232] open failed - RS232Open returned $ret")
            return false
        }
        serial = s
        val gen = readerGeneration.incrementAndGet()
        val portParams = "baud=$baud ${dataBits}${parity}${stopBits}"
        emitExchange(Rs232Cmd.RS232_PORT_OPEN, listOf(portParams, rules.size.toString()))
        val codedOpen = HardwareLib.dispatchRs232Event("RS232_PORT_OPEN", listOf(portParams, rules.size.toString()))
        val openLine = "[rs232] port open - $portParams, ${rules.size} rule(s) active"
        if (codedOpen) HardwareLib.reportLocal(openLine) else HardwareLib.reportLine(openLine)
        thread(name = "Rs232Reader", isDaemon = true) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
            }
            readerLoop(s, gen)
        }
        return true
    }

    /** Closes the port and stops the reader. True when it was open. Safe to follow with an
     * immediate [open] - the generation bump retires the old reader even mid-read. */
    @JvmStatic
    fun close(): Boolean {
        val wasOpen = running.getAndSet(false)
        readerGeneration.incrementAndGet() // retire the current reader thread immediately
        try { serial?.RS232Close() } catch (_: Throwable) {}
        serial = null
        if (wasOpen) {
            emitExchange(Rs232Cmd.RS232_PORT_CLOSED, emptyList())
            val coded = HardwareLib.dispatchRs232Event("RS232_PORT_CLOSED", emptyList())
            if (coded) HardwareLib.reportLocal("[rs232] port closed") else HardwareLib.reportLine("[rs232] port closed")
        }
        return wasOpen
    }

    /**
     * Writes raw hex to the machine RIGHT NOW - the manual TX for approvals and anything not
     * covered by an auto-reply rule. True when every byte was written.
     */
    @JvmStatic
    fun sendHex(hex: String): Boolean {
        val bytes = parseHex(hex)
        if (bytes == null || bytes.isEmpty()) {
            HardwareLib.reportLine("[rs232] sendHex rejected - invalid hex: $hex")
            return false
        }
        val s = serial
        if (s == null || !running.get()) {
            HardwareLib.reportLine("[rs232] sendHex rejected - port not open")
            return false
        }
        val ret = try {
            s.RS232Write(bytes)
        } catch (t: Throwable) {
            HardwareLib.reportLine("[rs232] sendHex failed: ${t.javaClass.simpleName}")
            return false
        }
        val ok = ret >= 0
        if (ok) {
            emitExchange(Rs232Cmd.RS232_TX, listOf(toHex(bytes)), txHex = toHex(bytes))
            val coded = HardwareLib.dispatchRs232Event("RS232_TX", listOf(toHex(bytes)))
            val line = "[rs232] tx ${toHex(bytes)}"
            if (coded) HardwareLib.reportLocal(line) else HardwareLib.reportLine(line)
        } else {
            HardwareLib.reportLine("[rs232] sendHex FAILED - RS232Write returned $ret")
        }
        return ok
    }

    // --------------------------- framed-protocol helpers ---------------------------

    /** ASCII text as space-separated hex: "SUCCESS" -> "53 55 43 43 45 53 53". */
    @JvmStatic
    fun asciiHex(text: String): String =
        text.toByteArray(Charsets.US_ASCII).joinToString(" ") { String.format("%02X", it) }

    /**
     * Builds a [header][length(2, big-endian)][data][XOR CRC] frame - the classic vending
     * card-reader format. [headerHex] is the fixed lead-in (e.g. "A1 02" = start byte + cmd),
     * [dataHex] the payload (use [asciiHex] for text payloads); the 2-byte data length and the
     * XOR of every preceding byte are appended automatically. Null when the hex is invalid.
     *
     *   buildXorFrame("A1 02", asciiHex("SUCCESS"))  ->  "A1 02 00 07 53 55 43 43 45 53 53 E7"
     */
    @JvmStatic
    @JvmOverloads
    fun buildXorFrame(headerHex: String, dataHex: String = ""): String? {
        val header = parseHex(headerHex) ?: return null
        val data = if (dataHex.isBlank()) ByteArray(0) else parseHex(dataHex) ?: return null
        val frame = ByteArray(header.size + 2 + data.size + 1)
        header.copyInto(frame)
        frame[header.size] = (data.size shr 8).toByte()
        frame[header.size + 1] = (data.size and 0xFF).toByte()
        data.copyInto(frame, header.size + 2)
        var crc = 0
        for (i in 0 until frame.size - 1) crc = crc xor (frame[i].toInt() and 0xFF)
        frame[frame.size - 1] = crc.toByte()
        return toHex(frame)
    }

    /** [buildXorFrame] + [sendHex] in one call - the built frame is reported even on failure
     * so you can see what would go on the wire. */
    @JvmStatic
    @JvmOverloads
    fun sendXorFrame(headerHex: String, dataHex: String = ""): Boolean {
        val frame = buildXorFrame(headerHex, dataHex)
        if (frame == null) {
            HardwareLib.reportLine("[rs232] sendXorFrame rejected - invalid hex: $headerHex / $dataHex")
            return false
        }
        HardwareLib.reportLine("[rs232] frame built: $frame")
        return sendHex(frame)
    }

    /** [sendXorFrame] with an ASCII payload: sendXorAscii("A1 02", "SUCCESS"). */
    @JvmStatic
    fun sendXorAscii(headerHex: String, text: String): Boolean = sendXorFrame(headerHex, asciiHex(text))

    /**
     * Test hook: feeds a fake received frame through the rule matcher exactly as if the machine
     * sent it - the reply is really written when the port is open, otherwise only logged. Lets
     * you verify a rule table (matching, price extraction, listeners) without hardware.
     */
    @JvmStatic
    fun simulateFrame(hex: String): Boolean {
        val bytes = parseHex(hex)
        if (bytes == null || bytes.isEmpty()) {
            HardwareLib.reportLine("[rs232] simulateFrame rejected - invalid hex: $hex")
            return false
        }
        processFrame(serial, bytes)
        return true
    }

    // ------------------------------------ reader ------------------------------------

    /** Reads the byte stream, cuts frames on [frameGapMs] of silence, and processes each one.
     * The reply (when a rule matches) is written BEFORE any logging - same send-first
     * discipline as the MDB engine. */
    private fun readerLoop(s: RS232, gen: Int) {
        val buf = ByteArray(READ_BUF)
        val frame = ArrayList<Byte>(READ_BUF)
        while (running.get() && gen == readerGeneration.get()) {
            val n = try {
                s.RS232Read(buf, buf.size, if (frame.isEmpty()) 200 else frameGapMs)
            } catch (t: Throwable) {
                // A retired reader dying on its closed port must not touch the flag that
                // belongs to the CURRENT reader.
                if (gen == readerGeneration.get()) {
                    HardwareLib.reportLine("[rs232] read failed: ${t.javaClass.simpleName} - reader stopped")
                    running.set(false)
                }
                return
            }
            if (gen != readerGeneration.get()) return // retired mid-read: discard, exit
            if (n > 0) {
                for (i in 0 until n) frame.add(buf[i])
                if (frame.size > 4096) { // runaway stream guard
                    processFrame(s, frame.toByteArray()); frame.clear()
                }
            } else if (frame.isNotEmpty()) {
                // Silence after data: the frame is complete.
                processFrame(s, frame.toByteArray())
                frame.clear()
            }
        }
    }

    private fun processFrame(s: RS232?, bytes: ByteArray) {
        // CRC gate: last byte must be the XOR of everything before it. A bad frame is
        // discarded without response, per the card-reader protocol.
        if (crcCheckEnabled && bytes.size >= 2) {
            var crc = 0
            for (i in 0 until bytes.size - 1) crc = crc xor (bytes[i].toInt() and 0xFF)
            val actual = bytes[bytes.size - 1].toInt() and 0xFF
            if (crc != actual) {
                val expected = String.format("%02X", crc)
                emitExchange(Rs232Cmd.RS232_CRC_DISCARDED, listOf(toHex(bytes), expected), rxHex = toHex(bytes))
                val codedCrc = HardwareLib.dispatchRs232Event("RS232_CRC_DISCARDED", listOf(toHex(bytes), expected))
                val crcLine = "[rs232] rx=${toHex(bytes)} CRC FAIL (expected $expected) - discarded"
                if (codedCrc) HardwareLib.reportLocal(crcLine) else HardwareLib.reportLine(crcLine)
                return
            }
        }
        val rule = rules.firstOrNull { matches(it, bytes) }
        var txHex: String? = null
        // 1. Reply first - the machine's reply window does not wait for our bookkeeping.
        if (rule != null && rule.tx.isNotBlank()) {
            val txBytes = parseHex(rule.tx)
            if (txBytes != null) {
                if (s != null) {
                    try {
                        s.RS232Write(txBytes)
                        txHex = rule.tx
                    } catch (t: Throwable) {
                        HardwareLib.reportLine("[rs232] reply write failed for ${rule.name}: ${t.javaClass.simpleName}")
                    }
                } else {
                    txHex = rule.tx // simulated frame, port closed: reply is logged, not written
                }
            }
        }
        // 2. Price extraction + vend hook. Negative indexes count from the frame end.
        val rxHex = toHex(bytes)
        var price = -1
        if (rule != null && rule.priceHiIndex != NO_INDEX) {
            // Binary: two bytes, hi*256 + lo.
            val hi = resolveIndex(rule.priceHiIndex, bytes.size)
            val lo = resolveIndex(rule.priceLoIndex, bytes.size)
            if (hi in bytes.indices && lo in bytes.indices) {
                price = (bytes[hi].toInt() and 0xFF) * 256 + (bytes[lo].toInt() and 0xFF)
            }
        } else if (rule != null && rule.amountStartIndex != NO_INDEX) {
            // ASCII: digit text inside the frame, end EXCLUSIVE (-1 skips a trailing CRC byte).
            val start = resolveIndex(rule.amountStartIndex, bytes.size)
            val endEx = resolveIndex(rule.amountEndIndex, bytes.size)
            if (start >= 0 && endEx <= bytes.size && start < endEx) {
                val digits = String(bytes, start, endEx - start, Charsets.US_ASCII).filter { it.isDigit() }
                price = digits.toIntOrNull() ?: -1
            }
        }
        if (price >= 0) {
            val p = price
            try { vendRequestListener?.invoke(p, rxHex) } catch (_: Throwable) {}
        }
        // 2b. 8.1.0: the rule's "event" drives the SAME VendListener the MDB engine uses.
        if (rule?.event != null) HardwareLib.rs232VendEvent(rule.event, price, rule.name, rxHex)
        // 3. Report: a coded RS232 schema event when a bridge is wired (RabbahLog codebook,
        // schema "RS232"), with the plain-text twin kept LOCAL so the wire never carries the
        // exchange twice; with no bridge the plain line ships remotely as before.
        val coded = if (rule == null) {
            HardwareLib.dispatchRs232Event("RS232_RX_UNMATCHED", listOf(rxHex))
        } else {
            HardwareLib.dispatchRs232Event(
                "RS232_RX_MATCHED",
                listOf(rule.name, rxHex, txHex ?: "-", price.toString())
            )
        }
        val line = when {
            rule == null -> "[rs232] rx=$rxHex UNMATCHED (no rule)"
            price >= 0 -> "[rs232] rx=$rxHex matched=${rule.name} price=$price tx=${txHex ?: "(none)"}"
            else -> "[rs232] rx=$rxHex matched=${rule.name} tx=${txHex ?: "(none)"}"
        }
        if (coded) HardwareLib.reportLocal(line) else HardwareLib.reportLine(line)
        if (rule == null) {
            emitExchange(Rs232Cmd.RS232_RX_UNMATCHED, listOf(rxHex), rxHex = rxHex)
        } else {
            emitExchange(
                Rs232Cmd.RS232_RX_MATCHED, listOf(rule.name, rxHex, txHex ?: "-", price.toString()),
                rxHex = rxHex, ruleName = rule.name, txHex = txHex, price = price
            )
        }
    }

    // ------------------------------------ hex helpers ------------------------------------

    /** Parsed rx pattern: [tokens] byte values with -1 = "??" (any one byte); [openEnded]
     * when the pattern ended with "*" - the frame may then carry any extra bytes after the
     * fixed part. */
    private class Pattern(val tokens: IntArray, val openEnded: Boolean)

    private fun matches(rule: Rs232Rule, bytes: ByteArray): Boolean {
        val p = parsePattern(rule.rx) ?: return false
        if (if (p.openEnded) bytes.size < p.tokens.size else bytes.size != p.tokens.size) return false
        for (i in p.tokens.indices) {
            val t = p.tokens[i]
            if (t != -1 && t != (bytes[i].toInt() and 0xFF)) return false
        }
        return true
    }

    /** "FF ?? 0A" -> [0xFF, -1, 0x0A]; "FF ?? *" -> the same fixed part, open-ended. Null when
     * any token is invalid or "*" is not the last token. */
    private fun parsePattern(pattern: String): Pattern? {
        var parts = pattern.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val openEnded = parts.last() == "*"
        if (openEnded) parts = parts.dropLast(1)
        if (parts.isEmpty() || parts.contains("*")) return null // "*" alone or mid-pattern
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            out[i] = if (p == "??") -1
            else p.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFF } ?: return null
        }
        return Pattern(out, openEnded)
    }

    private fun normalizePattern(pattern: String): String =
        pattern.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .joinToString(" ") {
                when (it) {
                    "??" -> "??"
                    "*" -> "*"
                    else -> String.format("%02X", it.removePrefix("0x").removePrefix("0X").toInt(16))
                }
            }

    /** >= 0 is a from-start index as-is; negative counts from the end (-1 = last byte). */
    private fun resolveIndex(index: Int, frameSize: Int): Int =
        if (index >= 0) index else frameSize + index

    private fun parseHex(hex: String): ByteArray? {
        val parts = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ByteArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            if (v !in 0..0xFF) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { String.format("%02X", it) }
}
