package com.rabbah.mqtt

import org.json.JSONObject

/**
 * JSON in / JSON out for the Android app - the second producer next to mdb-lib's automatic
 * log stream. Sending rides the same bounded [MqttLib] queue as everything else (never
 * blocks, buffers offline, no-op when MQTT is off); receiving is a plain callback per topic.
 *
 *     RabbahMqtt.sendJson("telemetry", JSONObject().put("battery", 87))
 *     val sub = RabbahMqtt.subscribeJson("commands") { json -> ... }
 *     RabbahMqtt.unsubscribe(sub)
 *
 * Topics are always "<topicPrefix>/<deviceId>/<topicSuffix>" - callers never build one.
 */
object RabbahMqtt {

    /** Handle returned by [subscribeJson]; pass it to [unsubscribe] to stop receiving. */
    class Subscription internal constructor(
        internal val topicSuffix: String,
        internal val rawListener: (String) -> Unit
    )

    /** SEND any JSON object to "<prefix>/<deviceId>/<topicSuffix>". Queued, safe anywhere. */
    @JvmStatic
    fun sendJson(topicSuffix: String, json: JSONObject) = MqttLib.enqueue(topicSuffix, json.toString())

    /** Same, for JSON you already hold as a string. It is sent verbatim. */
    @JvmStatic
    fun sendJson(topicSuffix: String, json: String) = MqttLib.enqueue(topicSuffix, json)

    /**
     * RECEIVE: every message published to "<prefix>/<deviceId>/<topicSuffix>" reaches [handler]
     * parsed as JSON - a payload that isn't valid JSON is wrapped as {"raw":"<text>"} so the
     * handler always gets an object. The subscription is re-established automatically on every
     * reconnect. Handlers run on the MQTT reader thread: return quickly, never block, and hop
     * to your own thread for real work. A throwing handler is caught and logged, never fatal.
     */
    @JvmStatic
    fun subscribeJson(topicSuffix: String, handler: (JSONObject) -> Unit): Subscription {
        val rawListener: (String) -> Unit = { payload ->
            val json = try {
                JSONObject(payload)
            } catch (t: Throwable) {
                JSONObject().put("raw", payload)
            }
            handler(json)
        }
        MqttLib.subscribeRaw(topicSuffix, rawListener)
        return Subscription(topicSuffix, rawListener)
    }


    // ------------------------- generic logs, no MDB involved -------------------------

    /**
     * SEND one log line to the dashboard's live log - the generic "just log this" call for
     * any Android app using mqtt-lib on its own. Rides the same compact RABBAH_LOG envelope
     * as everything else (INFO_RAW), so the dashboard renders it with timestamp, app name and
     * version with no extra setup. Queued, never blocks, silent no-op when MQTT is off.
     *
     *     RabbahMqtt.sendLog("payment gateway responded in 420ms")
     */
    @JvmStatic
    fun sendLog(message: String) = RabbahLog.raw(message)

    /** Same as [sendLog] but shipped as an ERROR - shows red on the dashboard. */
    @JvmStatic
    fun sendError(message: String) = RabbahLog.rawError(message)

    /** Handle returned by [onCommand]; pass it to [removeCommand] to stop receiving. */
    class CommandSubscription internal constructor(
        internal val listener: (String) -> Boolean
    )

    /**
     * RECEIVE plain-text messages the dashboard (or anything else) publishes to this device's
     * commands topic - the generic inbox, independent of MDB. [handler] gets each trimmed
     * payload; return true to CONSUME it (later listeners, including mdb-lib's, never see it),
     * false to pass it along the chain. Runs on the MQTT reader thread: return quickly and hop
     * to your own thread for real work.
     *
     *     val sub = RabbahMqtt.onCommand { cmd ->
     *         when (cmd) {
     *             "rebootKiosk" -> { scheduleReboot(); true }
     *             else -> false   // not mine - let other listeners look
     *         }
     *     }
     */
    @JvmStatic
    fun onCommand(handler: (String) -> Boolean): CommandSubscription {
        MqttLib.addCommandListener(handler)
        return CommandSubscription(handler)
    }

    /** Stop receiving for one [onCommand] handle. */
    @JvmStatic
    fun removeCommand(sub: CommandSubscription) = MqttLib.removeCommandListener(sub.listener)

    // ------------------------- connection state -------------------------

    /** True while the broker session is up right now (sends work either way - they queue). */
    @JvmStatic
    val isConnected: Boolean
        get() = MqttLib.isConnected

    /** Handle returned by [onConnection]; pass it to [removeConnection] to stop receiving. */
    class ConnectionSubscription internal constructor(
        internal val listener: (Boolean) -> Unit
    )

    /**
     * RECEIVE connection-state changes: [handler] is called with true the moment the broker
     * session is fully up and false the moment it drops - exactly once per change, plus once
     * immediately with the CURRENT state, so a status view is correct from the first frame:
     *
     *     RabbahMqtt.onConnection { connected ->
     *         runOnUiThread { statusView.text = if (connected) "MQTT: CONNECTED" else "MQTT: OFFLINE" }
     *     }
     *
     * Reconnects are automatic either way - this is purely visibility. Runs on MQTT internal
     * threads: hop to your own thread (runOnUiThread) for UI work.
     */
    @JvmStatic
    fun onConnection(handler: (connected: Boolean) -> Unit): ConnectionSubscription {
        MqttLib.addConnectionListener(handler)
        return ConnectionSubscription(handler)
    }

    /** Stop receiving for one [onConnection] handle. */
    @JvmStatic
    fun removeConnection(sub: ConnectionSubscription) = MqttLib.removeConnectionListener(sub.listener)

    /**
     * SEND plain text to any custom channel "<prefix>/<deviceId>/<topicSuffix>" - when the
     * payload is not JSON and not a log line, e.g. a CSV row or a bare status word.
     */
    @JvmStatic
    fun sendText(topicSuffix: String, text: String) = MqttLib.enqueue(topicSuffix, text)

    /**
     * RECEIVE plain text from a custom channel - the string arrives exactly as published,
     * re-subscribed automatically on every reconnect. Same threading rules as [subscribeJson].
     */
    @JvmStatic
    fun subscribeText(topicSuffix: String, handler: (String) -> Unit): Subscription {
        MqttLib.subscribeRaw(topicSuffix, handler)
        return Subscription(topicSuffix, handler)
    }

    /** Stop receiving for one [subscribeJson] handle. */
    @JvmStatic
    fun unsubscribe(sub: Subscription) = MqttLib.unsubscribeRaw(sub.topicSuffix, sub.rawListener)
}
