package com.rabbah.mqtt

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Configuration for [MqttLib]. Topics are derived as:
 *   "<topicPrefix>/<deviceId>/liveLog"   (device -> dashboard, everything enqueued)
 *   "<topicPrefix>/<deviceId>/commands"  (dashboard -> device, dispatched to listeners)
 */
class MqttConfig(
    val topicPrefix: String,
    val deviceId: String,
    val brokerHost: String = "broker.hivemq.com",
    val brokerPort: Int = 1883,
    val keepAliveSec: Int = 30,
    val queueCapacity: Int = 1000,
    /** MQTT username, for brokers with auth enabled (e.g. a private mosquitto). Null = none. */
    val username: String? = null,
    /** MQTT password. Only sent when [username] is set, per MQTT 3.1.1. */
    val password: String? = null,
    /** Fixed client id. Null = "<deviceId>-<millis%100000>", unique enough to avoid the broker
     * kicking a same-id session. Never share one id between two live clients. */
    val clientId: String? = null
)

/**
 * Minimal from-scratch MQTT 3.1.1 client (CONNECT, PUBLISH QoS 0, SUBSCRIBE, incoming PUBLISH
 * parsing, PINGREQ \u2014 no third-party library) packaged as a reusable transport with:
 *
 *  - ONE bounded outbound queue. Every producer (mdb-lib internals, the host app's own code)
 *    calls [enqueue]; a single publisher thread is the only thing that ever writes to the socket.
 *    Producers never block and never touch the network, so [enqueue] is safe from any thread,
 *    including the UI thread. The queue buffers while offline and drains, in order, on reconnect.
 *    When full, the OLDEST message is dropped and [droppedMessages] increments \u2014 a long offline
 *    stretch can never grow into an OOM.
 *
 *  - A command listener CHAIN for incoming messages. Listeners are called in registration order;
 *    the first one to return true "consumes" the command and stops the chain. Anything nobody
 *    consumes is reported back on the log topic as an unknown command. "ping" is answered here
 *    ("PONG") before the chain, so dashboards can verify liveness with no listeners registered.
 *
 * This library has NO knowledge of MDB or any other domain \u2014 it moves strings.
 *
 * Not a private channel: anyone who knows (or guesses) the topic strings on a public broker can
 * watch the log or send commands. Fine for field debugging; move to a private broker before this
 * ever carries anything sensitive.
 */
object MqttLib {

    private const val TAG = "MqttLib"
    private const val RECONNECT_DELAY_MS = 3000L
    private const val OFFLINE_RETRY_DELAY_MS = 200L

    private lateinit var config: MqttConfig
    private lateinit var logTopic: String
    private lateinit var commandTopic: String

    private class Outbound(val topic: String, val payload: String)

    private var queue: LinkedBlockingQueue<Outbound> = LinkedBlockingQueue(1000)
    private val dropped = AtomicLong(0)
    private val commandListeners = CopyOnWriteArrayList<(String) -> Boolean>()

    /** Extra subscriptions by topic SUFFIX ("<prefix>/<deviceId>/<suffix>"), each with its
     * listeners. Suffixes are stored, full topics resolved at connect time, and every entry is
     * re-subscribed on every reconnect. Prefer [RabbahMqtt.subscribeJson] over calling these. */
    private val topicListeners =
        java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    private val nextPacketId = java.util.concurrent.atomic.AtomicInteger(2)

    @Volatile private var initialized = false
    @Volatile private var running = false
    /** Bumped by every stop(): threads from an older start() see the mismatch and exit, so a
     * quick stop()+init()+start() (reconnect with new settings) can never leave zombie loops. */
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    private fun alive(gen: Int) = running && gen == generation

    /** True while a broker connection is currently established. [enqueue] works either way. */
    val isConnected: Boolean
        get() = output != null

    /** How many messages have been discarded (oldest-first) because the queue was full. */
    val droppedMessages: Long
        get() = dropped.get()

    /** How many messages are currently waiting in the outbound queue (0 when fully drained —
     * i.e. everything handed over so far has actually been written to the broker). */
    val pendingMessages: Int
        get() = queue.size

    /** Must be called once, before [start]. Safe to call again only after [stop]. */
    fun init(config: MqttConfig) {
        this.config = config
        logTopic = "${config.topicPrefix}/${config.deviceId}/liveLog"
        commandTopic = "${config.topicPrefix}/${config.deviceId}/commands"
        queue = LinkedBlockingQueue(config.queueCapacity)
        initialized = true
    }

    /** Starts the connect/reconnect loop and the single publisher thread. */
    fun start() {
        check(initialized) { "MqttLib.init(config) must be called before start()" }
        if (running) return
        running = true
        val gen = ++generation
        thread(name = "MqttConnect") { connectLoop(gen) }
        thread(name = "MqttPublisher") { publisherLoop(gen) }
    }

    fun stop() {
        generation++
        running = false
        closeQuietly()
    }

    /**
     * THE one way out: puts [payload] on the outbound queue for the liveLog topic and returns
     * immediately. Never blocks, never does I/O, safe from any thread. Silently drops the oldest
     * queued message (counting it in [droppedMessages]) if the queue is full.
     *
     * Safe to call even if [init] was never run: it becomes a silent no-op, so a host app that
     * runs without MQTT entirely (no dashboard) loses nothing but the remote log - MDB keeps
     * working on the bus, nothing crashes.
     */
    fun enqueue(payload: String) {
        if (!initialized) return
        enqueueTo(logTopic, payload)
    }

    /** Same as [enqueue] but to "<topicPrefix>/<deviceId>/<topicSuffix>" for custom channels.
     * Also a silent no-op before [init]. */
    fun enqueue(topicSuffix: String, payload: String) {
        if (!initialized) return
        enqueueTo("${config.topicPrefix}/${config.deviceId}/$topicSuffix", payload)
    }

    private fun enqueueTo(topic: String, payload: String) {
        val msg = Outbound(topic, payload)
        while (!queue.offer(msg)) {
            if (queue.poll() != null) dropped.incrementAndGet()
        }
    }

    /**
     * Registers [listener] on the incoming-command chain. Called (on the MQTT reader thread) with
     * the trimmed payload of each message on the commands topic; return true to consume it and
     * stop the chain, false to let later listeners see it.
     */
    fun addCommandListener(listener: (String) -> Boolean) {
        commandListeners.add(listener)
    }

    fun removeCommandListener(listener: (String) -> Boolean) {
        commandListeners.remove(listener)
    }

    /**
     * Low-level subscribe to "<topicPrefix>/<deviceId>/<topicSuffix>": [listener] gets each
     * message's payload as a string, on the MQTT reader thread. Safe to call any time (before
     * init, offline, connected — the subscription is sent now if connected and re-sent on every
     * reconnect). Prefer [RabbahMqtt.subscribeJson], which parses to JSON for you.
     */
    fun subscribeRaw(topicSuffix: String, listener: (String) -> Unit) {
        val listeners = topicListeners.getOrPut(topicSuffix) { CopyOnWriteArrayList() }
        listeners.add(listener)
        // Already connected: register with the broker immediately instead of waiting for a
        // reconnect. A dead socket here is fine — the reconnect path re-subscribes everything.
        val out = output
        if (initialized && out != null) {
            try {
                writePacket(out, subscribePacket(fullTopic(topicSuffix), nextPacketId.getAndIncrement()))
            } catch (t: Throwable) {
                Log.w(TAG, "subscribe($topicSuffix) will retry on reconnect: ${t.message}")
            }
        }
    }

    /** Removes one [subscribeRaw] listener. (The broker-side subscription stays until the next
     * reconnect — harmless, as payloads without listeners are simply dropped.) */
    fun unsubscribeRaw(topicSuffix: String, listener: (String) -> Unit) {
        topicListeners[topicSuffix]?.remove(listener)
    }

    private fun fullTopic(suffix: String) = "${config.topicPrefix}/${config.deviceId}/$suffix"

    // ------------------------------------ publisher ------------------------------------

    /**
     * The ONLY socket writer. Takes messages off the queue one at a time and retries each until
     * it is actually written (or the library is stopped) \u2014 this is what makes the queue an
     * offline buffer: while disconnected it just waits, and the reconnect loop below eventually
     * replaces the socket underneath it.
     */
    private fun publisherLoop(gen: Int) {
        while (alive(gen)) {
            val msg = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            var sentOk = false
            while (alive(gen) && !sentOk) {
                val out = output
                if (out == null) {
                    Thread.sleep(OFFLINE_RETRY_DELAY_MS)
                    continue
                }
                try {
                    writePacket(out, publishPacket(msg.topic, msg.payload.toByteArray(Charsets.UTF_8)))
                    sentOk = true
                } catch (t: Throwable) {
                    // Dead socket: the reader loop notices too and triggers a reconnect. Keep the
                    // message and retry it on the next socket so nothing is lost mid-flight.
                    Thread.sleep(OFFLINE_RETRY_DELAY_MS)
                }
            }
        }
    }

    // ------------------------------------ connection ------------------------------------

    private fun connectLoop(gen: Int) {
        while (alive(gen)) {
            try {
                connectOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "MQTT connect/session failed: ${t.message}")
            }
            if (gen == generation) closeQuietly()
            if (alive(gen)) Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    private fun connectOnce() {
        val s = Socket()
        s.connect(InetSocketAddress(config.brokerHost, config.brokerPort), 8000)
        val out = s.getOutputStream()
        val input = s.getInputStream()

        val clientId = config.clientId ?: "${config.deviceId}-${System.currentTimeMillis() % 100_000}"
        writePacket(out, connectPacket(clientId, config.keepAliveSec, config.username, config.password))
        readConnAck(input)

        writePacket(out, subscribePacket(commandTopic, packetId = 1))
        // Re-establish every extra subscription (RabbahMqtt.subscribeJson / subscribeRaw) on
        // this fresh session — clean-session connects forget broker-side state.
        for (suffix in topicListeners.keys) {
            writePacket(out, subscribePacket(fullTopic(suffix), nextPacketId.getAndIncrement()))
        }

        socket = s
        output = out
        Log.d(TAG, "Connected to ${config.brokerHost}:${config.brokerPort} as $clientId, log=$logTopic cmd=$commandTopic")

        thread(name = "MqttPing") {
            while (running && socket === s) {
                try {
                    Thread.sleep(config.keepAliveSec * 1000L / 2)
                    // The keep-alive PINGREQ shares the socket with the publisher thread; both
                    // writes are tiny single packets and OutputStream.write of one buffer is
                    // atomic enough for this broker use \u2014 same behavior the app always had.
                    if (socket === s) writePacket(out, PACKET_PINGREQ)
                } catch (t: Throwable) {
                    break
                }
            }
        }

        // Blocks parsing whatever the broker sends (our subscribed PUBLISHes, plus SUBACK and
        // PINGRESP which are just consumed) \u2014 this is also what detects a dropped connection so
        // connectLoop() can reconnect.
        readIncomingLoop(input)
    }

    private fun readConnAck(input: InputStream) {
        val connack = readFully(input, 4)
        if (connack[0] != 0x20.toByte() || connack[3] != 0x00.toByte()) {
            val reason = when (connack[3].toInt()) {
                1 -> "unacceptable protocol version"
                2 -> "client id rejected"
                3 -> "server unavailable"
                4 -> "bad username or password"
                5 -> "not authorized"
                else -> "return code=${connack[3]}"
            }
            throw IOException("MQTT CONNECT refused: $reason")
        }
    }

    private fun readIncomingLoop(input: InputStream) {
        while (running) {
            val header = readByte(input)
            val packetType = (header ushr 4) and 0x0F
            val remaining = readRemainingLength(input)
            val body = if (remaining > 0) readFully(input, remaining) else ByteArray(0)
            if (packetType == 0x03) handlePublish(header, body)
        }
    }

    private fun handlePublish(header: Int, body: ByteArray) {
        if (body.size < 2) return
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        if (body.size < 2 + topicLen) return
        val topic = String(body, 2, topicLen, Charsets.UTF_8)
        var payloadStart = 2 + topicLen
        val qos = (header ushr 1) and 0x03
        if (qos > 0) payloadStart += 2
        if (payloadStart > body.size) return
        val payload = String(body, payloadStart, body.size - payloadStart, Charsets.UTF_8)

        if (topic == commandTopic) {
            dispatchCommand(payload)
            return
        }
        // Extra subscriptions: match "<prefix>/<deviceId>/<suffix>" back to its listeners.
        val base = "${config.topicPrefix}/${config.deviceId}/"
        if (topic.startsWith(base)) {
            val listeners = topicListeners[topic.substring(base.length)] ?: return
            for (listener in listeners) {
                try {
                    listener(payload)
                } catch (t: Throwable) {
                    Log.w(TAG, "topic listener threw for $topic", t)
                }
            }
        }
    }

    /** Runs the listener chain. "ping" is answered here so liveness needs no listeners at all. */
    private fun dispatchCommand(payload: String) {
        val trimmed = payload.trim()
        if (trimmed == "ping") {
            enqueue("PONG")
            return
        }
        for (listener in commandListeners) {
            try {
                if (listener(trimmed)) return
            } catch (t: Throwable) {
                Log.w(TAG, "command listener threw for: $trimmed", t)
            }
        }
        enqueue("[remote] unknown command: $trimmed")
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (t: Throwable) {
        }
        socket = null
        output = null
    }

    private fun writePacket(out: OutputStream, packet: ByteArray) {
        out.write(packet, 0, packet.size)
        out.flush()
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw IOException("MQTT stream closed")
        return b
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val encodedByte = readByte(input)
            value += (encodedByte and 0x7F) * multiplier
            if (encodedByte and 0x80 == 0) break
            multiplier *= 128
        }
        return value
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("MQTT stream closed")
            off += n
        }
        return buf
    }

    // ------------------------------- MQTT 3.1.1 packet encoding -------------------------------

    private val PACKET_PINGREQ = byteArrayOf(0xC0.toByte(), 0x00)

    private fun encodeRemainingLength(length: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 0x80
            bytes.add(encodedByte.toByte())
        } while (x > 0)
        return bytes.toByteArray()
    }

    private fun encodeString(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun connectPacket(
        clientId: String,
        keepAliveSec: Int,
        username: String?,
        password: String?
    ): ByteArray {
        val protocolName = encodeString("MQTT")
        val protocolLevel = byteArrayOf(0x04)
        // Clean session always; username/password flags only when provided. Per MQTT 3.1.1 a
        // password may only be sent together with a username.
        var flags = 0x02
        if (username != null) flags = flags or 0x80
        if (username != null && password != null) flags = flags or 0x40
        val connectFlags = byteArrayOf(flags.toByte())
        val keepAlive = byteArrayOf((keepAliveSec ushr 8).toByte(), keepAliveSec.toByte())
        var body = protocolName + protocolLevel + connectFlags + keepAlive + encodeString(clientId)
        if (username != null) {
            body += encodeString(username)
            if (password != null) body += encodeString(password)
        }
        return byteArrayOf(0x10) + encodeRemainingLength(body.size) + body
    }

    private fun publishPacket(topic: String, payload: ByteArray): ByteArray {
        val body = encodeString(topic) + payload
        return byteArrayOf(0x30) + encodeRemainingLength(body.size) + body
    }

    private fun subscribePacket(topic: String, packetId: Int): ByteArray {
        val packetIdField = byteArrayOf((packetId ushr 8).toByte(), packetId.toByte())
        val topicFilter = encodeString(topic) + byteArrayOf(0x00) // requested QoS 0
        val body = packetIdField + topicFilter
        return byteArrayOf(0x82.toByte()) + encodeRemainingLength(body.size) + body
    }
}
