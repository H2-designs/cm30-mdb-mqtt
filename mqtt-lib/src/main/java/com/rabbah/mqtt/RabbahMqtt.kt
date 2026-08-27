package com.rabbah.mqtt

import org.json.JSONObject

/**
 * JSON in / JSON out for the Android app — the second producer next to mdb-lib's automatic
 * log stream. Sending rides the same bounded [MqttLib] queue as everything else (never
 * blocks, buffers offline, no-op when MQTT is off); receiving is a plain callback per topic.
 *
 *     RabbahMqtt.sendJson("telemetry", JSONObject().put("battery", 87))
 *     val sub = RabbahMqtt.subscribeJson("commands") { json -> ... }
 *     RabbahMqtt.unsubscribe(sub)
 *
 * Topics are always "<topicPrefix>/<deviceId>/<topicSuffix>" — callers never build one.
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
     * parsed as JSON — a payload that isn't valid JSON is wrapped as {"raw":"<text>"} so the
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

    /** Stop receiving for one [subscribeJson] handle. */
    @JvmStatic
    fun unsubscribe(sub: Subscription) = MqttLib.unsubscribeRaw(sub.topicSuffix, sub.rawListener)
}
