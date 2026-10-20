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
    val brokerHost: String = "mosquitto", // the Rabbah stack's broker, resolvable in-cluster;
    // devices elsewhere must pass the server's reachable IP/hostname explicitly
    val brokerPort: Int = 1883,
    val keepAliveSec: Int = 30,
    val queueCapacity: Int = 1000,
    /** MQTT username, for brokers with auth enabled (e.g. a private mosquitto). Null = none. */
    val username: String? = null,
    /** MQTT password. Only sent when [username] is set, per MQTT 3.1.1. */
    val password: String? = null,
    /** Fixed client id. Null = "<deviceId>-<millis%100000>", unique enough to avoid the broker
     * kicking a same-id session. Never share one id between two live clients. */
    val clientId: String? = null,
    /** Topic suffix for the outbound log stream. Default "liveLog" keeps the existing dashboard
     * wire format; the Rabbah backend contract uses "logs". */
    val logTopicSuffix: String = "liveLog",
    /** Topic suffix for incoming commands. Default "commands" keeps the existing dashboard wire
     * format; the Rabbah backend contract uses "cmd". */
    val commandTopicSuffix: String = "commands",
    /** Presence: when set (the backend contract uses "status"), a RETAINED birth message
     * ("online") is published on every connect, an MQTT Last Will makes the broker publish a
     * retained "offline" if the connection drops, and a graceful [MqttLib.stop] publishes the
     * retained "offline" itself (a clean disconnect never fires the Will). Null = no presence
     * traffic at all - the pre-1.3.0 behavior. */
    val statusTopicSuffix: String? = null,
    /** Second command channel, kept SEPARATE from [commandTopicSuffix] so operator/dashboard
     * commands never mix into a topic another system owns (the backend's cmd/ack/progress
     * flow). Same listener chain as commands. Null disables it. Default "passthrough". */
    val passthroughTopicSuffix: String? = "passthrough",
    /** When true, the first successful broker connection reflectively calls
     * com.rabbah.mdb.HardwareLib.attachRabbahMqtt() so hardware-lib's remote commands and log
     * stream are live without the host app writing any wiring code. A no-op when hardware-lib
     * is absent (RN/standalone apps) or too old (pre-7.16.0).
     *
     * DEFAULT FALSE since 2.6.0 (fleet decision): a device boots UNATTACHED and silent, and the
     * operator wires it on demand from the dashboard (HW toggle / "attachHardware" built-in,
     * answered by mqtt-lib itself so it always works) - then unwires with detachHardware, or the
     * next app restart drops it again. Set true for the old always-on behavior. */
    val autoAttachHardware: Boolean = false
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

    const val VERSION = "2.7.1"

    private const val TAG = "MqttLib"
    private const val RECONNECT_DELAY_MS = 3000L
    /** 2.7.0: reconnect delay doubles per consecutive failure up to this, plus 0-1 s of jitter. */
    private const val RECONNECT_DELAY_MAX_MS = 60_000L
    private const val OFFLINE_RETRY_DELAY_MS = 200L
    /** A session that ends sooner than this after CONNACK counts as "short" (takeover suspect). */
    private const val SHORT_SESSION_MS = 5_000L
    /** Short sessions in a row before the takeover warning is logged. */
    private const val TAKEOVER_STREAK = 3
    /** A session that lasts this long resets the backoff and the short-session streak. */
    private const val STABLE_SESSION_MS = 30_000L
    private val PACKET_DISCONNECT = byteArrayOf(0xE0.toByte(), 0x00)

    // ------------------------------------ connection counters (2.7.0) ------------------------------------
    // All readable any time, published as MQTT_STATS:{...} on "mqttStats", and summarised in one
    // "[mqtt] connected ..." log line on every (re)connect so the dashboard sees link health without asking.

    /** Sessions that reached CONNACK since process start. */
    @Volatile var sessionsEstablished: Int = 0; private set
    /** Sessions that were up and then died (socket EOF/error, broker kick, ping failure). */
    @Volatile var connectionLosses: Int = 0; private set
    /** Connect attempts that never reached CONNACK (refused, unreachable, timeout). */
    @Volatile var failedConnectAttempts: Int = 0; private set
    /** Short sessions (< SHORT_SESSION_MS) in a row - the takeover signature. */
    @Volatile var consecutiveShortSessions: Int = 0; private set
    @Volatile var lastLossReason: String = ""; private set
    @Volatile var lastLossAtMs: Long = 0L; private set
    @Volatile var lastSessionDurationMs: Long = 0L; private set
    @Volatile private var sessionSinceMs: Long = 0L
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var takeoverWarned: Boolean = false
    /** The client id this process uses for EVERY connect (2.7.0: stable per process, see init). */
    @Volatile var activeClientId: String = ""; private set

    /** Link-health snapshot as JSON - the payload of MQTT_STATS:. */
    fun statsJson(): String {
        val now = System.currentTimeMillis()
        val up = output != null
        return org.json.JSONObject().apply {
            put("clientId", activeClientId)
            put("connected", up)
            put("sessionUptimeSec", if (up && sessionSinceMs > 0) (now - sessionSinceMs) / 1000 else 0)
            put("sessionsEstablished", sessionsEstablished)
            put("connectionLosses", connectionLosses)
            put("failedConnectAttempts", failedConnectAttempts)
            put("consecutiveShortSessions", consecutiveShortSessions)
            put("takeoverSuspected", consecutiveShortSessions >= TAKEOVER_STREAK)
            put("lastLossReason", lastLossReason)
            put("lastLossAgoSec", if (lastLossAtMs > 0) (now - lastLossAtMs) / 1000 else -1)
            put("lastSessionDurationSec", lastSessionDurationMs / 1000)
            put("nextRetryDelayMs", reconnectDelayMs())
        }.toString()
    }

    private fun reconnectDelayMs(): Long {
        val exp = minOf(consecutiveFailures, 5)
        val base = minOf(RECONNECT_DELAY_MS shl exp, RECONNECT_DELAY_MAX_MS)
        return base + (Math.random() * 1000).toLong()
    }

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

    // ------------------------------------ connection state ------------------------------------

    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    /** The state last delivered to listeners - edge-triggering guard, so repeated failed
     * reconnect attempts do not spam "disconnected" over and over. Starts false: only a real
     * established session may fire the first true. */
    @Volatile private var lastNotifiedConnected: Boolean = false

    /**
     * Registers [listener] for connection-state changes: called with true the moment a broker
     * session is fully up (CONNACK accepted, subscriptions re-established) and with false the
     * moment it is lost (socket death or [stop]). Edge-triggered - exactly one call per actual
     * change - plus one immediate call with the CURRENT state on registration, so UI code shows
     * the right thing from the first frame with no polling.
     *
     * Runs on MQTT internal threads: return quickly and hop to your own thread (or
     * runOnUiThread) for real work. A throwing listener is caught and logged, never fatal.
     */
    fun addConnectionListener(listener: (connected: Boolean) -> Unit): Boolean {
        connectionListeners.add(listener)
        try {
            listener(isConnected)
        } catch (t: Throwable) {
            Log.w(TAG, "connection listener threw on registration", t)
        }
        return true
    }

    /** Returns true when the listener was registered and is now removed; false otherwise. */
    fun removeConnectionListener(listener: (connected: Boolean) -> Unit): Boolean =
        connectionListeners.remove(listener)

    private fun notifyConnection(connected: Boolean) {
        if (lastNotifiedConnected == connected) return
        lastNotifiedConnected = connected
        for (listener in connectionListeners) {
            try {
                listener(connected)
            } catch (t: Throwable) {
                Log.w(TAG, "connection listener threw", t)
            }
        }
    }

    /** How many messages have been discarded (oldest-first) because the queue was full. */
    val droppedMessages: Long
        get() = dropped.get()

    /** How many messages are currently waiting in the outbound queue (0 when fully drained -
     * i.e. everything handed over so far has actually been written to the broker). */
    val pendingMessages: Int
        get() = queue.size

    /** Must be called once, before [start]. Safe to call again only after [stop]. */
    private var statusTopic: String? = null
    private var passthroughTopic: String? = null

    fun init(config: MqttConfig): Boolean {
        this.config = config
        logTopic = "${config.topicPrefix}/${config.deviceId}/${config.logTopicSuffix}"
        commandTopic = "${config.topicPrefix}/${config.deviceId}/${config.commandTopicSuffix}"
        statusTopic = config.statusTopicSuffix?.let { "${config.topicPrefix}/${config.deviceId}/$it" }
        passthroughTopic = config.passthroughTopicSuffix?.let { "${config.topicPrefix}/${config.deviceId}/$it" }
        queue = LinkedBlockingQueue(config.queueCapacity)
        // 2.7.0: ONE client id per process, reused on every reconnect. Before, each attempt took a new
        // millis-based id, so after a cellular drop the broker kept the OLD half-open session alive
        // (up to 1.5 x keepAlive = 45 s) with its Will still armed; when that Will finally fired it
        // wrote a retained "offline" OVER the new session's "online" - the device looked offline
        // while it was connected. With a stable id the broker closes the old session the moment the
        // new one connects, fires its Will FIRST, and our fresh "online" lands last. Two processes
        // or two devices still never collide: the suffix is random per process.
        activeClientId = config.clientId ?: (config.deviceId + "-" + Integer.toHexString((Math.random() * 0xFFFF).toInt() or 0x1000))
        initialized = true
        return true
    }

    /** Starts the connect/reconnect loop and the single publisher thread. Returns true when the
     * loops started (or were already running); false when [init] was never called. */
    fun start(): Boolean {
        if (!initialized) {
            Log.w(TAG, "start() ignored - MqttLib.init(config) must be called first")
            return false
        }
        if (running) return true
        running = true
        val gen = ++generation
        thread(name = "MqttConnect") { connectLoop(gen) }
        thread(name = "MqttPublisher") { publisherLoop(gen) }
        return true
    }

    /** Stops the loops, publishing the retained "offline" first when presence is configured.
     * Returns true when a running client was stopped; false when it was not running. */
    fun stop(): Boolean {
        val wasRunning = running
        generation++
        running = false
        // Graceful goodbye: a clean disconnect never fires the Will, so publish the retained
        // "offline" ourselves. Best effort - a dead socket just means the Will already covered it.
        val topic = statusTopic
        val out = output
        if (topic != null && out != null) {
            try {
                writePacket(out, publishPacket(topic, "offline".toByteArray(Charsets.UTF_8), retained = true))
                // 2.7.0: a proper DISCONNECT tells the broker this close is deliberate, so it does NOT
                // fire the Will on top of the "offline" we just wrote (no doubled offline, no late Will).
                writePacket(out, PACKET_DISCONNECT)
            } catch (_: Throwable) {
            }
        }
        closeQuietly()
        return wasRunning
    }

    /**
     * THE one way out: puts [payload] on the outbound queue for the liveLog topic and returns
     * immediately. Never blocks, never does I/O, safe from any thread. Silently drops the oldest
     * queued message (counting it in [droppedMessages]) if the queue is full.
     *
     * Safe to call even if [init] was never run: it becomes a no-op returning false, so a host
     * app that runs without MQTT entirely (no dashboard) loses nothing but the remote log - MDB
     * keeps working on the bus, nothing crashes. Returns true when the message was queued.
     */
    fun enqueue(payload: String): Boolean {
        if (!initialized) return false
        enqueueTo(logTopic, payload)
        return true
    }

    /** Same as [enqueue] but to "<topicPrefix>/<deviceId>/<topicSuffix>" for custom channels.
     * Also a no-op returning false before [init]; true when queued. */
    fun enqueue(topicSuffix: String, payload: String): Boolean {
        if (!initialized) return false
        enqueueTo("${config.topicPrefix}/${config.deviceId}/$topicSuffix", payload)
        return true
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
    /** Returns true (the listener is always registered). */
    fun addCommandListener(listener: (String) -> Boolean): Boolean = commandListeners.addIfAbsent(listener)

    /** Returns true when the listener was registered and is now removed; false otherwise. */
    fun removeCommandListener(listener: (String) -> Boolean): Boolean = commandListeners.remove(listener)

    /**
     * Low-level subscribe to "<topicPrefix>/<deviceId>/<topicSuffix>": [listener] gets each
     * message's payload as a string, on the MQTT reader thread. Safe to call any time (before
     * init, offline, connected - the subscription is sent now if connected and re-sent on every
     * reconnect). Prefer [RabbahMqtt.subscribeJson], which parses to JSON for you.
     */
    fun subscribeRaw(topicSuffix: String, listener: (String) -> Unit): Boolean {
        val listeners = topicListeners.getOrPut(topicSuffix) { CopyOnWriteArrayList() }
        listeners.add(listener)
        // Already connected: register with the broker immediately instead of waiting for a
        // reconnect. A dead socket here is fine - the reconnect path re-subscribes everything.
        val out = output
        if (initialized && out != null) {
            try {
                writePacket(out, subscribePacket(fullTopic(topicSuffix), nextPacketId.getAndIncrement()))
            } catch (t: Throwable) {
                Log.w(TAG, "subscribe($topicSuffix) will retry on reconnect: ${t.message}")
            }
        }
        return true
    }

    /** Removes one [subscribeRaw] listener. (The broker-side subscription stays until the next
     * reconnect - harmless, as payloads without listeners are simply dropped.) Returns true when
     * the listener was registered and is now removed; false otherwise. */
    fun unsubscribeRaw(topicSuffix: String, listener: (String) -> Unit): Boolean =
        topicListeners[topicSuffix]?.remove(listener) ?: false

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
            sessionSinceMs = 0L
            try {
                connectOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "MQTT connect/session failed: ${t.message}")
                val reason = (t.javaClass.simpleName + ": " + (t.message ?: "")).trim()
                val now = System.currentTimeMillis()
                if (sessionSinceMs > 0L) {
                    // the session was UP and died
                    val lasted = now - sessionSinceMs
                    lastSessionDurationMs = lasted
                    lastLossReason = reason
                    lastLossAtMs = now
                    if (alive(gen)) {
                        connectionLosses++
                        if (lasted < SHORT_SESSION_MS) {
                            consecutiveShortSessions++
                            consecutiveFailures++
                            if (consecutiveShortSessions >= TAKEOVER_STREAK && !takeoverWarned) {
                                takeoverWarned = true
                                enqueue("[mqtt] WARNING: " + consecutiveShortSessions + " sessions in a row closed within " +
                                    (SHORT_SESSION_MS / 1000) + " s of connecting (last: " + reason + ") - another client is " +
                                    "probably connected with client id '" + activeClientId + "' (session takeover). " +
                                    "Check for a duplicate device code or a second app instance. Backing off up to " +
                                    (RECONNECT_DELAY_MAX_MS / 1000) + " s between retries.")
                            }
                        } else if (lasted >= STABLE_SESSION_MS) {
                            consecutiveShortSessions = 0
                            consecutiveFailures = 0
                            takeoverWarned = false
                        }
                    }
                } else if (alive(gen)) {
                    failedConnectAttempts++
                    consecutiveFailures++
                    lastLossReason = "connect failed - " + reason
                    lastLossAtMs = now
                }
            }
            if (gen == generation) closeQuietly()
            if (alive(gen)) Thread.sleep(reconnectDelayMs())
        }
    }

    private fun connectOnce() {
        val s = Socket()
        s.connect(InetSocketAddress(config.brokerHost, config.brokerPort), 8000)
        // Dead-link detector: PINGREQ goes out every keepAliveSec/2, so a healthy broker sends
        // SOMETHING (at least PINGRESP) well inside 1.5x keepAlive. A read that stays silent
        // longer means the link is half-open (cellular/NAT drop) - time it out so the reader
        // throws and connectLoop() reconnects, instead of blocking on a dead socket for minutes.
        s.soTimeout = config.keepAliveSec * 1500
        val out = s.getOutputStream()
        val input = s.getInputStream()

        val clientId = activeClientId
        writePacket(out, connectPacket(
            clientId, config.keepAliveSec, config.username, config.password,
            willTopic = statusTopic, willPayload = "offline"
        ))
        readConnAck(input)
        // Presence birth message: retained, so any subscriber - now or later - sees the last
        // known state. The matching "offline" comes from the Will on a drop, or from stop().
        statusTopic?.let { writePacket(out, publishPacket(it, "online".toByteArray(Charsets.UTF_8), retained = true)) }

        writePacket(out, subscribePacket(commandTopic, packetId = 1))
        // The operator/dashboard passthrough channel - same listener chain, separate topic so
        // it can never collide with a backend-owned cmd topic.
        passthroughTopic?.let { writePacket(out, subscribePacket(it, nextPacketId.getAndIncrement())) }
        // Re-establish every extra subscription (RabbahMqtt.subscribeJson / subscribeRaw) on
        // this fresh session - clean-session connects forget broker-side state.
        for (suffix in topicListeners.keys) {
            writePacket(out, subscribePacket(fullTopic(suffix), nextPacketId.getAndIncrement()))
        }

        socket = s
        output = out
        sessionSinceMs = System.currentTimeMillis()
        sessionsEstablished++
        Log.d(TAG, "Connected to ${config.brokerHost}:${config.brokerPort} as $clientId, log=$logTopic cmd=$commandTopic")
        notifyConnection(true)
        // 2.7.0: one line per (re)connect so the dashboard shows link health without being asked.
        enqueue(
            if (sessionsEstablished == 1) "[mqtt] connected as " + clientId + " - first session since app start"
            else "[mqtt] reconnected as " + clientId + " - session #" + sessionsEstablished + ", losses=" + connectionLosses +
                ", failed attempts=" + failedConnectAttempts + ", previous session lasted " + (lastSessionDurationMs / 1000) +
                " s, last loss: " + lastLossReason +
                (if (consecutiveShortSessions >= TAKEOVER_STREAK) " - TAKEOVER SUSPECTED (" + consecutiveShortSessions + " short sessions in a row)" else "")
        )
        enqueue("MQTT_STATS:" + statsJson())

        if (config.autoAttachHardware && !autoAttachTried) {
            autoAttachTried = true
            if (attachHardware()) {
                Log.d(TAG, "hardware-lib auto-attached (remote commands + logs live)")
            }
        }

        thread(name = "MqttPing") {
            while (running && socket === s) {
                try {
                    Thread.sleep(config.keepAliveSec * 1000L / 2)
                    if (socket === s) writePacket(out, PACKET_PINGREQ)
                } catch (t: Throwable) {
                    // A failed PINGREQ means the link is dead. Close the socket so the blocked
                    // reader wakes immediately and connectLoop() reconnects NOW - previously the
                    // ping thread died silently and a half-open link could sit "offline" for
                    // minutes until the OS finally errored the read.
                    if (socket === s) closeQuietly()
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

        if (topic == commandTopic || (passthroughTopic != null && topic == passthroughTopic)) {
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

    /** Runs the listener chain. "ping", "version" and "attachHardware" are answered here so a
     * dashboard can verify liveness, identify library versions and rescue an app that forgot to
     * wire hardware-lib - all with zero listeners registered by the host app. */
    private fun dispatchCommand(payload: String) {
        val trimmed = payload.trim()
        if (trimmed == "ping") {
            enqueue("PONG")
            return
        }
        if (trimmed.equals("version", ignoreCase = true) ||
            trimmed.equals("getVersion", ignoreCase = true)
        ) {
            enqueue("[remote] mqtt-lib $VERSION, hardware-lib ${hardwareLibVersion()} - client id " + activeClientId +
                ", sessions=" + sessionsEstablished + " losses=" + connectionLosses + " failed attempts=" + failedConnectAttempts)
            return
        }
        if (trimmed.equals("mqttStats", ignoreCase = true) || trimmed.equals("linkStats", ignoreCase = true)) {
            enqueue("MQTT_STATS:" + statsJson())
            return
        }
        if (trimmed.equals("attachHardware", ignoreCase = true) ||
            trimmed.equals("attachHw", ignoreCase = true)
        ) {
            enqueue(
                if (attachHardware())
                    "[remote] hardware-lib attached - send 'help' for the full command list"
                else
                    "[remote] attachHardware FAILED: hardware-lib absent or older than 7.16.0 " +
                        "(bundled: ${hardwareLibVersion()})"
            )
            return
        }
        if (trimmed.equals("detachHardware", ignoreCase = true) ||
            trimmed.equals("detachHw", ignoreCase = true)
        ) {
            enqueue(
                if (detachHardware())
                    "[remote] hardware-lib DETACHED - hardware logs and commands unwired. " +
                        "attachHardware re-wires; an app restart auto-attaches again."
                else
                    "[remote] detachHardware: nothing attached (or hardware-lib older than 7.19.0)"
            )
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

    // --------------------------- hardware-lib bridge (reflection) ---------------------------

    /** Guard so the automatic attempt happens once per process, not on every reconnect. The
     * manual "attachHardware" command bypasses this - hardware-lib's own attach flag already
     * makes a second wiring a no-op. */
    @Volatile private var autoAttachTried = false

    /** Reflectively calls com.rabbah.mdb.HardwareLib.attachRabbahMqtt(). mqtt-lib has no
     * compile-time dependency on hardware-lib (it moves strings, nothing more), so the bridge
     * command has to find it at runtime. False when hardware-lib is not in the app or predates
     * 7.16.0 (no attachRabbahMqtt yet). */
    private fun attachHardware(): Boolean = try {
        val hw = Class.forName("com.rabbah.mdb.HardwareLib").getDeclaredField("INSTANCE").get(null)
        hw.javaClass.getMethod("attachRabbahMqtt").invoke(hw) == true
    } catch (t: Throwable) {
        Log.w(TAG, "attachHardware: hardware-lib not reachable (${t.javaClass.simpleName})")
        false
    }

    /** Reflectively calls HardwareLib.detachRabbahMqtt() (7.19.0+) - the true disconnect. */
    private fun detachHardware(): Boolean = try {
        val hw = Class.forName("com.rabbah.mdb.HardwareLib").getDeclaredField("INSTANCE").get(null)
        hw.javaClass.getMethod("detachRabbahMqtt").invoke(hw) == true
    } catch (t: Throwable) {
        Log.w(TAG, "detachHardware: not available (${t.javaClass.simpleName})")
        false
    }

    private fun hardwareLibVersion(): String = try {
        // const val VERSION compiles to a static field on the class, not a getter
        Class.forName("com.rabbah.mdb.HardwareLib")
            .getDeclaredField("VERSION").get(null) as? String ?: "unknown"
    } catch (t: Throwable) {
        "absent"
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (t: Throwable) {
        }
        socket = null
        output = null
        notifyConnection(false)
    }

    /** Serializes ALL socket writes. The publisher thread and the keep-alive ping thread share
     * one OutputStream; unsynchronized, a 2-byte PINGREQ could interleave MID-PUBLISH, corrupt
     * the MQTT stream, and make the broker drop the connection - which surfaced in the field as
     * random online/offline flapping on chatty devices. One lock ends that class of bug. */
    private val writeLock = Any()

    private fun writePacket(out: OutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            out.write(packet, 0, packet.size)
            out.flush()
        }
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
        password: String?,
        willTopic: String? = null,
        willPayload: String? = null
    ): ByteArray {
        val protocolName = encodeString("MQTT")
        val protocolLevel = byteArrayOf(0x04)
        // Clean session always; username/password flags only when provided. Per MQTT 3.1.1 a
        // password may only be sent together with a username. A Will (retained, QoS 0) is set
        // when presence is configured - the broker publishes it for us on an ungraceful drop.
        var flags = 0x02
        if (willTopic != null && willPayload != null) flags = flags or 0x04 or 0x20 // Will + Will Retain
        if (username != null) flags = flags or 0x80
        if (username != null && password != null) flags = flags or 0x40
        val connectFlags = byteArrayOf(flags.toByte())
        val keepAlive = byteArrayOf((keepAliveSec ushr 8).toByte(), keepAliveSec.toByte())
        var body = protocolName + protocolLevel + connectFlags + keepAlive + encodeString(clientId)
        if (willTopic != null && willPayload != null) {
            // Payload order per spec: client id, will topic, will message, username, password.
            body += encodeString(willTopic)
            body += encodeString(willPayload)
        }
        if (username != null) {
            body += encodeString(username)
            if (password != null) body += encodeString(password)
        }
        return byteArrayOf(0x10) + encodeRemainingLength(body.size) + body
    }

    private fun publishPacket(topic: String, payload: ByteArray, retained: Boolean = false): ByteArray {
        val body = encodeString(topic) + payload
        val header: Byte = if (retained) 0x31 else 0x30
        return byteArrayOf(header) + encodeRemainingLength(body.size) + body
    }

    private fun subscribePacket(topic: String, packetId: Int): ByteArray {
        val packetIdField = byteArrayOf((packetId ushr 8).toByte(), packetId.toByte())
        val topicFilter = encodeString(topic) + byteArrayOf(0x00) // requested QoS 0
        val body = packetIdField + topicFilter
        return byteArrayOf(0x82.toByte()) + encodeRemainingLength(body.size) + body
    }
}
