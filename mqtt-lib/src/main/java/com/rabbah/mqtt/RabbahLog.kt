package com.rabbah.mqtt

import org.json.JSONArray
import org.json.JSONObject

/**
 * The ONE way any code on the device ships a log to the dashboard.
 *
 * Every call becomes a compact log item — the exact envelope of the production Rabbah Log
 * Codebook, single-letter keys, every value a string, params positional:
 *
 *   RABBAH_LOG:{"t":"1787743651002","s":"MDB","m":"13","a":"vending-app","v":"2.13.4",
 *               "k":"i","i":"7c1f2a9b","d":1,"p":["13 00 01 F4 00 03","ACK","500","3"]}
 *
 * and is pushed onto [MqttLib]'s bounded outbound queue: never blocks, never does I/O on the
 * caller's thread, buffers while offline, drop-oldest when full, and is a harmless no-op when
 * MQTT was never configured — calling this can not crash or stall the host app.
 *
 * Two producers use it:
 *  - mdb-lib, automatically: every bus exchange arrives as ONE unified [MdbLogEvent] whose
 *    p[0] is always the received frame (rx) and p[1] the reply we sent (tx).
 *  - the Android app, manually: [log] with a typed event, or [raw]/[rawError] for free text.
 */
object RabbahLog {

    /** Severity, exactly as it travels in "k". Lives on the envelope, not the codebook — the
     * same messageCode may arrive as INFO from one site and ERROR from another. */
    enum class Kind(val wire: String) { INFO("i"), ERROR("e"), DEBUG("d") }

    /** Tag prefixes on the shared liveLog topic, so compact items coexist with the existing
     * VMC_STATUS:/SETTINGS_JSON:/CONFIG_JSON: control-plane messages untouched. */
    const val LOG_TAG = "RABBAH_LOG:"
    const val CODEBOOK_TAG = "CODEBOOK_JSON:"

    @Volatile private var appName: String = "app"
    @Volatile private var appVersion: String = ""

    /** Correlates every log inside one vend — the "i" field. mdb-lib sets a fresh id when it
     * answers SESSION BEGIN and clears it after END SESSION; apps may also set their own. */
    @JvmStatic
    @Volatile
    var sessionId: String? = null

    init {
        // The dashboard asks "getCodebook" and gets this build's full dictionary back, so its
        // decode tables can never drift from what the device actually emits. Registering a
        // command listener is safe even before MqttLib.init().
        MqttLib.addCommandListener { cmd ->
            if (cmd == "getCodebook") {
                MqttLib.enqueue(CODEBOOK_TAG + RabbahCodebook.json())
                true
            } else {
                false
            }
        }
    }

    /** Once at startup — names the emitter ("a") and its version ("v") on every item after. */
    @JvmStatic
    @JvmOverloads
    fun init(appName: String, appVersion: String = "") {
        this.appName = appName
        this.appVersion = appVersion
    }

    /**
     * THE log function. Each parameter maps to one key of the JSON envelope:
     * [event] -> s + m, [params] -> p, [kind] -> k, [sessionId] -> i, [instant] -> d;
     * t, a and v are stamped automatically.
     */
    @JvmStatic
    @JvmOverloads
    fun log(
        event: RabbahLogEvent,
        params: List<String> = emptyList(),
        kind: Kind = event.defaultKind,
        sessionId: String? = this.sessionId,
        instant: Boolean = false
    ) {
        MqttLib.enqueue(LOG_TAG + makeLogJson(event, params, kind, sessionId, instant))
    }

    /** Short form so a call site stays one line: log(event, "rx bytes", "ACK", "500", "3"). */
    @JvmStatic
    fun log(event: RabbahLogEvent, vararg params: String) = log(event, params.toList())

    /** Free text from anywhere in the app — schema INFO, code 0. */
    @JvmStatic
    fun raw(message: String) = log(InfoLogEvent.INFO_RAW, listOf(message))

    /** Free text, shipped as an error. */
    @JvmStatic
    fun rawError(message: String) = log(InfoLogEvent.INFO_RAW, listOf(message), Kind.ERROR)

    /**
     * Builds the compact envelope WITHOUT sending it — the JSON maker, public for anyone who
     * needs the string itself (tests, alternate transports, previews).
     */
    @JvmStatic
    @JvmOverloads
    fun makeLogJson(
        event: RabbahLogEvent,
        params: List<String> = emptyList(),
        kind: Kind = event.defaultKind,
        sessionId: String? = this.sessionId,
        instant: Boolean = false
    ): String = JSONObject().apply {
        put("t", System.currentTimeMillis().toString()) // t: timestamp, epoch millis as string
        put("s", event.schemaCode)                      // s: which codebook
        put("m", event.messageCode.toString())          // m: entry in that codebook
        put("a", appName)                               // a: emitting app
        put("v", appVersion)                            // v: app version
        put("k", kind.wire)                             // k: severity i/e/d
        sessionId?.let { put("i", it) }                 // i: vend correlation, only when open
        put("d", if (instant) 2 else 1)                 // d: 1 buffered, 2 instant
        put("p", JSONArray(params))                     // p: values, in slot order
    }.toString()

    /**
     * Local decode of [event]'s template — the on-device screen log renders through this, so
     * the screen and the dashboard always show the identical sentence.
     */
    @JvmStatic
    fun format(event: RabbahLogEvent, params: List<String>): String {
        var out = event.template
        for (i in params.indices) out = out.replace("{$i}", params[i])
        return out
    }
}
