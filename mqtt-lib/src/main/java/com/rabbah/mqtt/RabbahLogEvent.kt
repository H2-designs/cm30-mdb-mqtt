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
 * APPEND-ONLY: add new events at the bottom with the next code. Never insert, never renumber.
 */
enum class MdbLogEvent(
    override val messageCode: Int,
    override val template: String,
    override val paramCount: Int,
    override val defaultKind: RabbahLog.Kind = RabbahLog.Kind.INFO
) : RabbahLogEvent {
    MDB_EXCHANGE(0, "EXCHANGE rx={0} tx={1}", 2),
    MDB_OTHER_PERIPHERAL(1, "OTHER PERIPHERAL rx={0} tx={1}", 2, RabbahLog.Kind.DEBUG),
    MDB_UNHANDLED(2, "UNHANDLED rx={0} tx={1}", 2, RabbahLog.Kind.ERROR),
    MDB_RESET(3, "RESET rx={0} tx={1}", 2),
    MDB_SETUP_CONFIG(4, "SETUP CONFIG rx={0} tx={1} level={2}", 3),
    MDB_SETUP_PRICES(5, "SETUP MAX/MIN PRICES rx={0} tx={1}", 2),
    MDB_POLL(6, "POLL rx={0} tx={1}", 2, RabbahLog.Kind.DEBUG),
    MDB_POLL_JUST_RESET(7, "POLL (JUST RESET) rx={0} tx={1}", 2),
    MDB_POLL_SESSION_BEGIN(8, "POLL (SESSION BEGIN) rx={0} tx={1}", 2),
    MDB_POLL_END_SESSION(9, "POLL (END SESSION) rx={0} tx={1}", 2),
    MDB_POLL_VEND_APPROVED(10, "POLL (VEND APPROVED) rx={0} tx={1}", 2),
    MDB_POLL_SESSION_CANCEL(11, "POLL (SESSION CANCEL REQUEST) rx={0} tx={1}", 2),
    MDB_POLL_VEND_DENIED(12, "POLL (VEND DENIED) rx={0} tx={1}", 2),
    MDB_VEND_REQUEST(13, "VEND REQUEST rx={0} tx={1} price={2} item={3}", 4),
    MDB_VEND_CANCEL(14, "VEND CANCEL rx={0} tx={1}", 2),
    MDB_VEND_SUCCESS(15, "VEND SUCCESS rx={0} tx={1} item={2}", 3),
    MDB_VEND_FAILURE(16, "VEND FAILURE rx={0} tx={1}", 2, RabbahLog.Kind.ERROR),
    MDB_SESSION_COMPLETE(17, "SESSION COMPLETE rx={0} tx={1}", 2),
    MDB_CASH_SALE(18, "CASH SALE rx={0} tx={1} price={2} item={3}", 4),
    MDB_READER_DISABLE(19, "READER DISABLE rx={0} tx={1}", 2),
    MDB_READER_ENABLE(20, "READER ENABLE rx={0} tx={1}", 2),
    MDB_READER_CANCEL(21, "READER CANCEL rx={0} tx={1}", 2),
    MDB_REVALUE_REQUEST(22, "REVALUE REQUEST rx={0} tx={1}", 2),
    MDB_REVALUE_LIMIT_REQUEST(23, "REVALUE LIMIT REQUEST rx={0} tx={1}", 2),
    MDB_EXPANSION_REQUEST_ID(24, "EXPANSION REQUEST ID rx={0} tx={1}", 2),
    MDB_EXPANSION_ENABLE_OPTIONS(25, "EXPANSION ENABLE OPTIONS rx={0} tx={1}", 2),
    MDB_EXPANSION_OTHER(26, "EXPANSION rx={0} tx={1}", 2);

    override val schemaCode: String get() = "MDB"
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
