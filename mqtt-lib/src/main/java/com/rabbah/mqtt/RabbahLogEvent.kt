package com.rabbah.mqtt

import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry in a Rabbah compact-log codebook.
 *
 * A log never carries its message text over MQTT — it carries a schema code, a message code
 * and the raw parameter values; the reader (dashboard) reconstitutes the sentence from the
 * matching codebook entry. This is the exact envelope/decode discipline of the production
 * "Rabbah Log Codebook": codes are positional and APPEND-ONLY (a new event always takes the
 * next number, never an inserted one, or every stored log after it silently re-maps).
 * [eventName] is the durable identifier.
 */
interface RabbahLogEvent {
    /** Which codebook this entry belongs to — the "s" field on the wire. */
    val schemaCode: String

    /** The entry's positional code inside its codebook — the "m" field on the wire. */
    val messageCode: Int

    /** Durable name, safe to grep for forever. */
    val eventName: String

    /** Sentence template; {0}, {1}, ... are filled from params by index. */
    val template: String

    /** Expected params size — the decoder's arity check, never enforced before shipping. */
    val paramCount: Int

    /** Severity used when the emit site doesn't say otherwise. Still an envelope field. */
    val defaultKind: RabbahLog.Kind
}

/**
 * The unified MDB codebook (schema "MDB").
 *
 * Every event is ONE complete bus exchange: params always begin with
 *   p[0] = rx  — the raw frame the VMC sent us (hex bytes)
 *   p[1] = tx  — what we replied (the engine's reply name: ACK, JUST RESET, ...)
 * so what we received and what we answered can never be split across two log lines.
 *
 * CODES 110-136 (since mqtt-lib 2.4.0): the wire codes are UNIFIED with the MdbCmd exchange
 * codes in hardware-lib - one code space for "which command" and "which log line", the same
 * scheme RS232 (137-143) has always used. Historical builds (mqtt-lib <= 2.3.0) emitted 0-26
 * for the same events in the same order; the codebook a device serves always matches what it
 * emits, so codebook-driven decoders never notice the change.
 *
 * APPEND-ONLY: add new events at the bottom with the next code (144+, after RS232's 137-143).
 * Never insert, never renumber.
 */
enum class MdbLogEvent(
    override val messageCode: Int,
    override val template: String,
    override val paramCount: Int,
    override val defaultKind: RabbahLog.Kind = RabbahLog.Kind.INFO
) : RabbahLogEvent {
    MDB_EXCHANGE(110, "EXCHANGE rx={0} tx={1}", 2),
    MDB_OTHER_PERIPHERAL(135, "OTHER PERIPHERAL rx={0} tx={1}", 2, RabbahLog.Kind.DEBUG),
    MDB_UNHANDLED(136, "UNHANDLED rx={0} tx={1}", 2, RabbahLog.Kind.ERROR),
    MDB_RESET(111, "RESET rx={0} tx={1}", 2),
    MDB_SETUP_CONFIG(113, "SETUP CONFIG rx={0} tx={1} level={2}", 3),
    MDB_SETUP_PRICES(115, "SETUP MAX/MIN PRICES rx={0} tx={1}", 2),
    MDB_POLL(133, "POLL rx={0} tx={1}", 2, RabbahLog.Kind.DEBUG),
    MDB_POLL_JUST_RESET(112, "POLL (JUST RESET) rx={0} tx={1}", 2),
    MDB_POLL_SESSION_BEGIN(120, "POLL (SESSION BEGIN) rx={0} tx={1}", 2),
    MDB_POLL_END_SESSION(127, "POLL (END SESSION) rx={0} tx={1}", 2),
    MDB_POLL_VEND_APPROVED(122, "POLL (VEND APPROVED) rx={0} tx={1}", 2),
    MDB_POLL_SESSION_CANCEL(129, "POLL (SESSION CANCEL REQUEST) rx={0} tx={1}", 2),
    MDB_POLL_VEND_DENIED(123, "POLL (VEND DENIED) rx={0} tx={1}", 2),
    MDB_VEND_REQUEST(121, "VEND REQUEST rx={0} tx={1} price={2} item={3}", 4),
    MDB_VEND_CANCEL(128, "VEND CANCEL rx={0} tx={1}", 2),
    MDB_VEND_SUCCESS(124, "VEND SUCCESS rx={0} tx={1} item={2}", 3),
    MDB_VEND_FAILURE(125, "VEND FAILURE rx={0} tx={1}", 2, RabbahLog.Kind.ERROR),
    MDB_SESSION_COMPLETE(126, "SESSION COMPLETE rx={0} tx={1}", 2),
    MDB_CASH_SALE(132, "CASH SALE rx={0} tx={1} price={2} item={3}", 4),
    MDB_READER_DISABLE(118, "READER DISABLE rx={0} tx={1}", 2),
    MDB_READER_ENABLE(117, "READER ENABLE rx={0} tx={1}", 2),
    MDB_READER_CANCEL(119, "READER CANCEL rx={0} tx={1}", 2),
    MDB_REVALUE_REQUEST(130, "REVALUE REQUEST rx={0} tx={1}", 2),
    MDB_REVALUE_LIMIT_REQUEST(131, "REVALUE LIMIT REQUEST rx={0} tx={1}", 2),
    MDB_EXPANSION_REQUEST_ID(114, "EXPANSION REQUEST ID rx={0} tx={1}", 2),
    MDB_EXPANSION_ENABLE_OPTIONS(116, "EXPANSION ENABLE OPTIONS rx={0} tx={1}", 2),
    MDB_EXPANSION_OTHER(134, "EXPANSION rx={0} tx={1}", 2);

    override val schemaCode: String get() = "MDB"
    override val eventName: String get() = name
}

/**
 * The RS232 codebook (schema "RS232") — one coded event per serial exchange, so the backend
 * and dashboard parse RS232 traffic exactly like MDB instead of scraping "[rs232] ..." text.
 * hardware-lib emits these through HardwareLib.addRs232EventListener (names map 1:1).
 *
 * APPEND-ONLY: add new events at the bottom with the next code. Never insert, never renumber.
 */
enum class Rs232LogEvent(
    override val messageCode: Int,
    override val template: String,
    override val paramCount: Int,
    override val defaultKind: RabbahLog.Kind = RabbahLog.Kind.INFO
) : RabbahLogEvent {
    // Codes continue AFTER the MDB CMD schema (110-136, the numbering the whole project
    // thinks in), so an RS232 code can never be confused with an MDB one anywhere - not in
    // the log codebook, not next to MdbCmd exchange codes.
    /** p = [ruleName, rxHex, txHex or "-", price or "-1"] */
    RS232_RX_MATCHED(137, "RS232 {0} rx={1} tx={2} price={3}", 4),
    /** p = [rxHex] */
    RS232_RX_UNMATCHED(138, "RS232 UNMATCHED rx={0}", 1, RabbahLog.Kind.ERROR),
    /** p = [rxHex, expectedCrcHex] */
    RS232_CRC_DISCARDED(139, "RS232 CRC FAIL rx={0} expected={1} - discarded", 2, RabbahLog.Kind.ERROR),
    /** p = [txHex] — manual sendHex / sendXor* writes (rule auto-replies ride RX_MATCHED) */
    RS232_TX(140, "RS232 TX {0}", 1),
    /** p = [portParams e.g. "baud=9600 8N1", ruleCount] */
    RS232_PORT_OPEN(141, "RS232 port open {0}, {1} rule(s) active", 2),
    RS232_PORT_CLOSED(142, "RS232 port closed", 0),
    /** p = [ruleCount] */
    RS232_RULES_LOADED(143, "RS232 {0} rule(s) loaded", 1);

    override val schemaCode: String get() = "RS232"
    override val eventName: String get() = name
}

/**
 * Free-text codebook (schema "INFO") — the "just send a string" path for any app.
 * APPEND-ONLY, same rule as every codebook.
 */
enum class InfoLogEvent(
    override val messageCode: Int,
    override val template: String,
    override val paramCount: Int,
    override val defaultKind: RabbahLog.Kind = RabbahLog.Kind.INFO
) : RabbahLogEvent {
    INFO_RAW(0, "{0}", 1);

    override val schemaCode: String get() = "INFO"
    override val eventName: String get() = name
}

/**
 * The full dictionary, one place. The dashboard fetches this straight from the running
 * device ("getCodebook" -> "CODEBOOK_JSON:{...}"), so its decode tables always match the
 * exact build that is emitting — there is no second copy to drift.
 */
object RabbahCodebook {

    /** All schemas this build can emit, keyed by schemaCode. */
    val schemas: Map<String, List<RabbahLogEvent>> = mapOf(
        "MDB" to MdbLogEvent.values().toList(),
        "RS232" to Rs232LogEvent.values().toList(),
        "INFO" to InfoLogEvent.values().toList()
    )

    /** The entry for (schema, code), or null for a code newer than this build. */
    fun find(schemaCode: String, messageCode: Int): RabbahLogEvent? =
        schemas[schemaCode]?.firstOrNull { it.messageCode == messageCode }

    /**
     * {"MDB": {"13": {"eventName": ..., "template": ..., "paramCount": ...}, ...}, "INFO": {...}}
     */
    fun json(): String {
        val root = JSONObject()
        for ((schema, events) in schemas) {
            val entries = JSONObject()
            for (e in events) {
                entries.put(e.messageCode.toString(), JSONObject().apply {
                    put("eventName", e.eventName)
                    put("template", e.template)
                    put("paramCount", e.paramCount)
                })
            }
            root.put(schema, entries)
        }
        return root.toString()
    }
}
