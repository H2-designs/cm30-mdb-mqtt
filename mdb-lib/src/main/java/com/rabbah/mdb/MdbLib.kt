package com.rabbah.mdb

import android.content.Context
import com.rabbah.mqtt.MdbLogEvent
import com.rabbah.mqtt.MqttLib
import com.rabbah.mqtt.RabbahLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The MDB Cashless Device #1 slave, packaged as a library. Owns the entire protocol state
 * machine (RESET -> SETUP -> EXPANSION -> READER ENABLE -> session/vend, with per-level setup
 * handshakes for MDB levels 1/2/3) and everything around it: persisted settings, the remotely
 * editable payload store ([MdbConfigStore]), and its own reporting.
 *
 * Integration is two calls after MqttLib is up:
 *
 *     MqttLib.init(MqttConfig(topicPrefix = "...", deviceId = myId)); MqttLib.start()
 *     MdbLib.init(applicationContext); MdbLib.start()
 *
 * From that point every RX/TX exchange, VMC status heartbeat, settings snapshot, and config ack
 * flows to the dashboard through MqttLib's outbound queue with no glue code in the app. MdbLib
 * also auto-registers a command listener on MqttLib, so all MDB remote commands (open/close/
 * vendApprove/setMdbLevel:... plus config JSON) work with nothing else wired.
 *
 * The optional [logListener] mirrors every reported line locally (for the app's own on-screen
 * log); [statusListener] mirrors the VMC status JSON. Neither is required for the MQTT path.
 */
object MdbLib {

    private enum class MdbState { INACTIVE_STATE, DISABLED_STATE, ENABLED_STATE, VEND_STATE }

    /** The two valid answers to "cancel this pending vend". The standing choice is normally set
     * from the dashboard ("setCancelMode:..."), and plain [cancelVend] uses it - but both the
     * mode setter and a per-call override are public too, so a standalone app with no dashboard
     * can control everything itself. */
    enum class CancelResponse(val label: String) {
        SESSION_CANCEL_REQUEST("SESSION CANCEL REQUEST"),
        VEND_DENIED("VEND DENIED")
    }

    /** AUTO (default): session begins by itself as soon as the reader is enabled, and again
     * after every session ends. MANUAL: none of that auto-arming happens; a session only starts
     * when [beginSession] is called. Exposed to apps as a plain boolean via [setAutoSession]. */
    private enum class SessionBeginMode { AUTO, MANUAL }

    /**
     * The business-level vend events, typed - THIS is what a payment integration hooks. The
     * intended flow:
     *
     *   1. [onVendRequest] fires with the price (and item number) the customer selected.
     *      Return IMMEDIATELY and run the payment-gateway call on your own thread/coroutine -
     *      the engine keeps ACKing the VMC's polls while it waits, exactly as the MDB spec's
     *      "ACK now, answer on a later POLL" pattern allows, so a gateway round trip of a few
     *      seconds is fine.
     *   2. Gateway approved  -> call [approveVend]  (VEND APPROVED goes out on the next POLL).
     *      Gateway declined  -> call [cancelVend] with an explicit response, typically
     *      [CancelResponse.VEND_DENIED].
     *   3. [onVendSuccess] fires when the VMC confirms the product was actually dispensed -
     *      capture/settle the payment here.
     *      [onVendFailure] fires if the product did NOT dispense - refund/void here.
     *   4. [onSessionEnded] fires when the session closes (END SESSION sent) - cleanup point.
     *
     * All callbacks run on a dedicated callback thread, NEVER on the bus thread itself - the bus
     * loop only queues the event and immediately goes back to answering the VMC, so even a slow
     * callback can not make a response miss the VMC's few-millisecond reply window. Still return
     * promptly and run gateway calls on your own thread: one queue serves all callbacks and log
     * delivery, so a long block delays later events. Exceptions thrown by callbacks are caught
     * and reported, never allowed to kill anything. All methods have default no-op bodies, so
     * override only what you need.
     */
    interface VendListener {
        /** Customer selected an item; the VMC asks permission to vend. The price arrives in two
         * forms, both pre-computed by the library from the scale factor / decimal places in
         * READER_CONFIG_DATA:
         *
         *  - [amount]: the decimal price (Double), e.g. 3.5 for SAR 3.50 - convenient for
         *    display; format with "%.2f" for showing two decimals.
         *  - [minorUnits]: the EXACT integer price in minor units (halalas/cents), e.g. 350 -
         *    use THIS for payment gateways and any money math. It is integer arithmetic all the
         *    way from the wire bytes, so it can never carry floating-point error.
         *
         * [itemNumber] is the raw 16-bit item code. All three are -1 / -1.0 when the VMC
         * omitted those bytes. */
        fun onVendRequest(amount: Double, minorUnits: Int, itemNumber: Int) {}

        /** The VMC reports the product WAS dispensed - safe to capture/settle the payment. */
        fun onVendSuccess(itemNumber: Int) {}

        /** The VMC reports the product was NOT dispensed - refund/void the payment. */
        fun onVendFailure() {}

        /** The session closed (END SESSION went out) - per-session cleanup point. */
        fun onSessionEnded() {}
    }

    /** Hook for payment/vend business logic - see [VendListener] for the full flow. */
    @JvmStatic
    var vendListener: VendListener? = null

    /** Optional local mirror of every line the engine reports. showOnScreen is false for
     * high-volume suppressible traffic (idle POLL/ACK, unhandled commands) - render those to a
     * screen at your own risk: redrawing a growing text view on every bus poll is exactly what
     * used to hang the demo app on real hardware. Lines reach the MQTT queue regardless. */
    @JvmStatic
    var logListener: ((line: String, showOnScreen: Boolean) -> Unit)? = null

    /** Optional local mirror of the VMC status JSON ({"state": ..., "recentActivity": ...}).
     * Fires immediately on EVERY state transition (INACTIVE/DISABLED/ENABLED/VEND) and as the
     * 3-second heartbeat in between, so an app UI tracks the vend state with no lag. */
    @JvmStatic
    var statusListener: ((stateJson: String) -> Unit)? = null

    /** The current MDB state, readable on demand from Android code:
     * "INACTIVE_STATE" (port closed / pre-setup), "DISABLED_STATE" (configured, not selling),
     * "ENABLED_STATE" (waiting for a session), "VEND_STATE" (session live). */
    val currentState: String
        get() = mdbState.name

    /** True while a session is live (VEND_STATE) - i.e. between BEGIN SESSION and END SESSION,
     * the only window where approveVend()/cancelVend() can actually do anything. */
    val isSessionActive: Boolean
        get() = mdbState == MdbState.VEND_STATE

    private const val CMD_RESET = 0x10
    private const val CMD_SETUP = 0x11
    private const val CMD_POLL = 0x12
    private const val CMD_VEND = 0x13
    private const val CMD_READER = 0x14
    private const val CMD_REVALUE = 0x15
    private const val CMD_EXPANSION = 0x17

    /** Announce JUST RESET on up to this many POLLs after a RESET, then fall back to plain ACK
     * (still waiting for SETUP whenever it eventually comes, just no longer re-announcing). */
    private const val JUST_RESET_REPEAT_COUNT = 5

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var initialized = false

    // --- off-bus pipeline ---
    // The VMC allows only a few milliseconds between its command and our response. Anything that
    // is not receive/dispatch/send - hex formatting, listener invocations, JSON building, MQTT
    // enqueueing, vend callbacks, the heartbeat - is pushed onto this queue and executed by a
    // separate thread, so the bus loop never spends its reply window on bookkeeping. Bounded with
    // drop-oldest, same policy as MqttLib's queue: a stalled consumer costs log lines, never the bus.
    private val busOffloadQueue = LinkedBlockingQueue<Runnable>(2000)
    private var offloadWorker: Thread? = null

    /** After a command arrives, the bus thread spins (yield, no sleep) for this long waiting for
     * the next one - setup-phase commands come back-to-back and a 1ms sleep on Android routinely
     * oversleeps to 10-20ms, which is exactly the delay that made the VMC discard our READER
     * CONFIG responses and loop the handshake. Outside a burst the loop falls back to sleeping. */
    private const val BURST_WINDOW_MS = 15L
    @Volatile private var lastRxAtMs = 0L

    // --- MDB state machine fields ---
    @Volatile private var mdbState = MdbState.INACTIVE_STATE
    private var resetFlag = false
    private var justResetCount = 0
    private var priceHigh = 0
    private var priceLow = 0
    private var sessionBeginPending = false
    private var sessionBeginMode = SessionBeginMode.AUTO
    private var endSessionPending = false
    private var sessionCancelPending = false
    private var pendingCancelResponse = CancelResponse.SESSION_CANCEL_REQUEST
    private var vmcCancelResponseMode = CancelResponse.SESSION_CANCEL_REQUEST
    private var vendApprovePending = false
    private var vendRequestReceived = false
    private var showPollTraffic = false
    private var showUnhandledCmd = false
    private var mdbLevel = 2
    /** Gates ONLY the log lines going to MQTT (see [report]) - local listeners and the
     * control-plane messages (VMC_STATUS/SETTINGS_JSON/CONFIG_JSON) are never gated. */
    @Volatile private var mqttLogsEnabled = true

    /** Sentinel for "matched a real POLL, sent a plain ACK, suppressible from the log" -
     * distinct from null (no branch matched at all). */
    private const val SUPPRESSIBLE_ACK = " SUPPRESSIBLE_ACK"

    @Volatile private var lastMdbActivityAtMs = 0L
    private var lastVmcStatusPublishAtMs = 0L
    /** The state most recently reported through statusListener/VMC_STATUS - compared after each
     * processed command so any transition publishes IMMEDIATELY, not on the next 3s heartbeat. */
    @Volatile private var lastReportedState = MdbState.INACTIVE_STATE
    private const val VMC_ACTIVITY_TIMEOUT_MS = 5000L
    private const val VMC_HEARTBEAT_INTERVAL_MS = 3000L

    // ------------------------------------ lifecycle ------------------------------------

    /**
     * Initializes persistence, restores settings, and registers this library's command listener
     * on [MqttLib] so all MDB remote commands and config JSON work with no app glue. Idempotent.
     *
     * MqttLib is OPTIONAL: call MqttLib.init()+start() before this for the dashboard link, or
     * skip MQTT entirely - MdbLib then runs fully offline (bus, vend flow, all listeners, all
     * settings and hex configs still work; only the remote log/commands are absent, and every
     * enqueue is a harmless no-op).
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        MdbConfigStore.init(context.applicationContext)
        MdbSettings.init(context.applicationContext)
        loadPersistedSettings()
        MqttLib.addCommandListener(commandListener)
        // Announce the real, persisted settings immediately so any dashboard already open (or one
        // that asks via "getSettings" a moment later) sees the truth, never assumed defaults.
        publishSettingsSnapshot()
    }

    /** Opens the MDB port and starts the worker loop. The engine state is fully reset and the
     * INACTIVE status is announced immediately (not on the next heartbeat tick). */
    fun start() {
        if (running.getAndSet(true)) return
        if (!MdbSlaveWrapper.open()) {
            running.set(false)
            report("open() failed \u2014 could not open MDB slave port", true)
            return
        }

        mdbState = MdbState.INACTIVE_STATE
        resetFlag = false
        justResetCount = 0
        sessionBeginPending = false
        endSessionPending = false
        sessionCancelPending = false
        vendApprovePending = false
        vendRequestReceived = false

        publishVmcStatusNow()

        // The offload thread: runs everything the bus loop queued (log formatting, listeners,
        // vend callbacks, status JSON) in order, and owns the 3s heartbeat. Started before the
        // bus loop so nothing queued can ever wait for a consumer.
        offloadWorker = thread(name = "MdbOffload", isDaemon = true) {
            while (running.get()) {
                val task = busOffloadQueue.poll(250, TimeUnit.MILLISECONDS)
                try {
                    task?.run()
                } catch (t: Throwable) {
                    report("[mdb] offloaded task threw: ${t.message}", true)
                }
                maybePublishVmcHeartbeat()
            }
            // Drain so lines reported just before stop() still reach the log.
            while (true) {
                val task = busOffloadQueue.poll() ?: break
                try { task.run() } catch (_: Throwable) {}
            }
        }

        // The bus loop: receive -> dispatch -> send, and NOTHING else. Runs at urgent priority
        // and spins through command bursts, because the response window the VMC gives us is only
        // a few milliseconds - a delayed READER CONFIG gets discarded and the VMC loops setup.
        worker = thread(name = "MdbSlaveLoop") {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
                // Priority is an optimization, never a requirement.
            }
            while (running.get()) {
                val cmd = MdbSlaveWrapper.receiveCommand()
                if (cmd != null && cmd.isNotEmpty()) {
                    lastRxAtMs = System.currentTimeMillis()
                    processCommand(cmd)
                    // A state transition (e.g. ENABLED -> VEND on BEGIN SESSION) is reported the
                    // instant it happens - but the JSON building and delivery run on the offload
                    // thread; only the snapshot is captured here.
                    val state = mdbState
                    if (state != lastReportedState) {
                        lastReportedState = state
                        offloadFromBus { publishVmcStatus(state) }
                    }
                    continue // burst: the next setup command may already be waiting
                }
                if (System.currentTimeMillis() - lastRxAtMs <= BURST_WINDOW_MS) {
                    Thread.yield() // stay hot mid-handshake instead of oversleeping the window
                } else {
                    Thread.sleep(1)
                }
            }
        }
    }

    /** Queues [task] for the offload thread, dropping the oldest queued task when full - the bus
     * loop must never block on its own bookkeeping. */
    private fun offloadFromBus(task: Runnable) {
        if (!busOffloadQueue.offer(task)) {
            busOffloadQueue.poll()
            busOffloadQueue.offer(task)
        }
    }

    /** Closes the port, resets to INACTIVE, and announces the state change immediately. */
    fun stop() {
        running.set(false)
        worker = null
        offloadWorker = null
        MdbSlaveWrapper.close()
        mdbState = MdbState.INACTIVE_STATE
        lastMdbActivityAtMs = 0L
        publishVmcStatusNow()
    }

    // ------------------------------------ session / vend control ------------------------------------

    /** Arms BEGIN SESSION for the next POLL (the "card tap"). In AUTO mode sessions arm
     * themselves so this never needs calling; in MANUAL mode this - or the dashboard's
     * "beginSession" command, which calls the same thing - is what starts a session. */
    fun beginSession() {
        sessionBeginPending = true
    }

    /**
     * Arms VEND APPROVED - only if a VEND REQUEST has actually arrived (checked here, at call
     * time). Calling before any request exists is discarded outright, never queued, so it cannot
     * silently auto-approve whatever the customer selects next. Returns whether it armed.
     */
    fun approveVend(): Boolean {
        if (!vendRequestReceived) return false
        vendApprovePending = true
        return true
    }

    /**
     * The simple cancel: sends the STANDING response that was configured once via
     * [setCancelMode] (or the dashboard's "setCancelMode:..." command). Configure once, then
     * just call this - no per-call choice needed. Not gated on a VEND REQUEST having arrived:
     * the point is being able to abort a session before the customer picks an item. No-op
     * unless a session is actually live. Returns whether it armed.
     */
    fun cancelVend(): Boolean = cancelVend(vmcCancelResponseMode)

    /** Per-call override: cancels sending exactly [response] this one time, without touching the
     * standing mode. Same thing the dashboard's "cancelVend:sessionCancel" /
     * "cancelVend:vendDenied" commands do. */
    fun cancelVend(response: CancelResponse): Boolean {
        if (mdbState != MdbState.VEND_STATE) return false
        pendingCancelResponse = response
        sessionCancelPending = true
        vendApprovePending = false
        return true
    }

    // ------------------------------------ settings ------------------------------------

    /**
     * Session mode as a plain boolean: true = sessions begin by themselves (auto), false = only
     * [beginSession] starts one (manual). Persisted. Same thing the dashboard's
     * "setSessionMode:auto|manual" command does. Changing it nudges the state machine so it
     * takes effect immediately: a live session is cancelled (the new mode then governs the next
     * one), and in ENABLED_STATE the pending flag is armed/cleared on the spot instead of
     * waiting for a reader disable/enable cycle.
     */
    fun setAutoSession(enabled: Boolean) {
        setSessionBeginMode(if (enabled) SessionBeginMode.AUTO else SessionBeginMode.MANUAL)
    }

    /** True when sessions currently begin automatically ([setAutoSession]). */
    val isAutoSession: Boolean
        get() = sessionBeginMode == SessionBeginMode.AUTO

    private fun setSessionBeginMode(mode: SessionBeginMode) {
        val changed = sessionBeginMode != mode
        sessionBeginMode = mode
        MdbSettings.sessionBeginMode = if (mode == SessionBeginMode.MANUAL) "manual" else "auto"
        publishSettingsSnapshot()
        if (!changed) return
        when (mdbState) {
            MdbState.VEND_STATE -> cancelVend(vmcCancelResponseMode)
            MdbState.ENABLED_STATE -> sessionBeginPending = (mode == SessionBeginMode.AUTO)
            else -> {}
        }
    }

    /** The standing choice of what a plain [cancelVend] sends and how the VMC's own VEND CANCEL
     * (13H 01H) gets answered. Persisted. Same thing the dashboard's "setCancelMode:..." does. */
    fun setCancelMode(mode: CancelResponse) {
        vmcCancelResponseMode = mode
        MdbSettings.vmcCancelMode = if (mode == CancelResponse.VEND_DENIED) "vendDenied" else "sessionCancel"
        publishSettingsSnapshot()
    }

    /**
     * Master on/off for publishing the engine's LOG LINES over MQTT. Persisted. Same as the
     * dashboard's "setMqttLogging:on|off". While OFF: local [logListener] still gets every line
     * (your on-device log keeps working), and the control plane (VMC_STATUS heartbeat,
     * SETTINGS_JSON, CONFIG_JSON, command acks' snapshots, PONG) still publishes - so the
     * dashboard still sees the device state and can still send commands, including the one that
     * turns logging back on. Only the log stream itself goes quiet.
     */
    fun setMqttLogging(enabled: Boolean) {
        if (!enabled) {
            // Send the goodbye line BEFORE muting, so the dashboard sees why the log went quiet.
            report("[mdb] MQTT log publishing disabled - control plane stays active", true)
        }
        mqttLogsEnabled = enabled
        MdbSettings.mqttLogsEnabled = enabled
        if (enabled) {
            report("[mdb] MQTT log publishing enabled", true)
        }
        publishSettingsSnapshot()
    }

    /** True while the engine's log lines are being published over MQTT. */
    val isMqttLoggingEnabled: Boolean
        get() = mqttLogsEnabled

    /** Log-debugging toggle: report idle POLL/ACK exchanges. Persisted. Same as the dashboard's
     * "setPollVisibility:on|off". */
    fun setPollVisibility(show: Boolean) {
        showPollTraffic = show
        MdbSettings.showPollTraffic = show
        publishSettingsSnapshot()
    }

    /** Log-debugging toggle: report commands unrecognized in the current state. Persisted. Same
     * as the dashboard's "setUnhandledVisibility:on|off". */
    fun setUnhandledVisibility(show: Boolean) {
        showUnhandledCmd = show
        MdbSettings.showUnhandledCmd = show
        publishSettingsSnapshot()
    }

    /** [level] must be 1, 2, or 3: the feature level reported in READER CONFIG DATA, which
     * setup-phase handshake runs, and which BEGIN SESSION / PERIPHERAL ID payloads are sent.
     * Persisted. Same thing the dashboard's "setMdbLevel:1|2|3" does. */
    fun setMdbLevel(level: Int) {
        require(level in 1..3) { "MDB level must be 1, 2, or 3 (got $level)" }
        mdbLevel = level
        MdbSettings.mdbLevel = level
        publishSettingsSnapshot()
    }

    private fun loadPersistedSettings() {
        sessionBeginMode = if (MdbSettings.sessionBeginMode == "manual") {
            SessionBeginMode.MANUAL
        } else {
            SessionBeginMode.AUTO
        }
        vmcCancelResponseMode = if (MdbSettings.vmcCancelMode == "vendDenied") {
            CancelResponse.VEND_DENIED
        } else {
            CancelResponse.SESSION_CANCEL_REQUEST
        }
        showPollTraffic = MdbSettings.showPollTraffic
        showUnhandledCmd = MdbSettings.showUnhandledCmd
        mdbLevel = MdbSettings.mdbLevel
        mqttLogsEnabled = MdbSettings.mqttLogsEnabled
    }

    // ------------------------------------ hex payload config ------------------------------------

    /** The configurable payload names, as constants so app code never typos a raw string. */
    object ConfigName {
        const val JUST_RESET = "JUST_RESET"
        const val CAN = "CAN"
        const val READER_CONFIG_DATA = "READER_CONFIG_DATA"
        const val READER_CONFIG_INFO = "READER_CONFIG_INFO"
        const val READER_CONFIG_INFO_L3 = "READER_CONFIG_INFO_L3"
        const val VEND_APPROVED = "VEND_APPROVED"
        const val VEND_DENIED = "VEND_DENIED"
        const val END_SESSION = "END_SESSION"
        const val SESSION_CANCEL = "SESSION_CANCEL"
        const val SESSION_BEGIN = "SESSION_BEGIN"
        const val SESSION_BEGIN_L2 = "SESSION_BEGIN_L2"
        const val REVALUE_LIMIT = "REVALUE_LIMIT"
        const val REVALUE_DENIED = "REVALUE_DENIED"
    }

    /**
     * Converts a raw MDB price (as delivered in [VendListener.onVendRequest]) into the real
     * decimal amount, using the scale factor (byte 4) and decimal places (byte 5) currently
     * configured in READER_CONFIG_DATA - the same values the VMC itself was told to use, so the
     * two sides can never disagree. With the default config (scale 1, decimals 2):
     * raw 350 -> 3.50, raw 1000 -> 10.00. A raw of -1 (VMC omitted the bytes) returns -1.0.
     */
    fun priceToAmount(raw: Int): Double {
        if (raw < 0) return -1.0
        val cfg = MdbConfigStore.get(ConfigName.READER_CONFIG_DATA)
        val scale = cfg.getOrNull(4)?.toInt()?.and(0xFF) ?: 1
        val decimals = cfg.getOrNull(5)?.toInt()?.and(0xFF) ?: 2
        var divisor = 1.0
        repeat(decimals) { divisor *= 10.0 }
        return raw * scale / divisor
    }

    /**
     * Converts a raw MDB price to EXACT integer minor units (halalas/cents): raw x scale factor,
     * pure integer arithmetic, no floating point anywhere - what payment gateways want.
     * With the default config (scale 1, decimals 2): raw 350 -> 350 minor units = SAR 3.50.
     * Returns -1 for a raw of -1 (VMC omitted the bytes).
     */
    fun priceToMinorUnits(raw: Int): Int {
        if (raw < 0) return -1
        val cfg = MdbConfigStore.get(ConfigName.READER_CONFIG_DATA)
        val scale = cfg.getOrNull(4)?.toInt()?.and(0xFF) ?: 1
        return raw * scale
    }

    /** Every configurable payload name (same set the dashboard's Config panel edits). */
    fun configNames(): List<String> = MdbConfigStore.names()

    /** The current bytes of [name] as a hex string, e.g. "03 FF FF". */
    fun getConfigHex(name: String): String = MdbConfigStore.toHex(MdbConfigStore.get(name))

    /**
     * Sets the raw wire payload for [name] from Android code - the same edit the dashboard's
     * Config panel makes over MQTT. [hex] tolerates spaces/commas/0x prefixes; the byte COUNT
     * must exactly match the payload's locked length. Persisted; the engine uses the new bytes
     * on its very next send, no restart. An ack line and a fresh CONFIG_JSON snapshot are
     * published so any watching dashboard stays in sync. Returns null on success, or a
     * human-readable error ("Wrong length for X: got 2 byte(s), need 3", ...).
     */
    fun setConfigHex(name: String, hex: String): String? {
        val error = MdbConfigStore.set(name, hex)
        if (error != null) {
            report("[app] setConfig $name failed: $error", true)
        } else {
            report("[app] $name updated -> ${getConfigHex(name)}", true)
        }
        publishConfigSnapshot()
        return error
    }

    /** Resets [name] back to the library default, publishing the ack + snapshot like
     * [setConfigHex]. Returns false for an unknown name. */
    fun resetConfig(name: String): Boolean {
        if (name !in MdbConfigStore.names()) {
            report("[app] resetConfig: unknown config $name", true)
            return false
        }
        MdbConfigStore.resetToDefault(name)
        report("[app] $name reset to default -> ${getConfigHex(name)}", true)
        publishConfigSnapshot()
        return true
    }

    /** Every current payload as JSON (name -> hex string) - same content as CONFIG_JSON. */
    fun configSnapshotJson(): String = MdbConfigStore.snapshotJson()

    /** Current settings as JSON - what the "SETTINGS_JSON:" tagged message carries. */
    fun currentSettingsJson(): String = org.json.JSONObject().apply {
        put("sessionBeginMode", MdbSettings.sessionBeginMode)
        put("vmcCancelMode", MdbSettings.vmcCancelMode)
        put("showPollTraffic", MdbSettings.showPollTraffic)
        put("showUnhandledCmd", MdbSettings.showUnhandledCmd)
        put("mdbLevel", MdbSettings.mdbLevel)
        put("mqttLogsEnabled", MdbSettings.mqttLogsEnabled)
    }.toString()

    private fun publishSettingsSnapshot() {
        MqttLib.enqueue("SETTINGS_JSON:" + currentSettingsJson())
    }

    private fun publishConfigSnapshot() {
        MqttLib.enqueue("CONFIG_JSON:" + MdbConfigStore.snapshotJson())
    }

    /** Whether "Always Idle" is in effect - read live from bit 5 of Z34, the last of the 4
     * optional-feature-bits bytes in the Level 3 PERIPHERAL ID payload, instead of a separate
     * setting that could drift from what is actually declared to the VMC on the wire. Per the
     * MDB/ICP spec (7.4.4) the 32 bits are sent MSB-first, so Z34 holds bits 7-0 and bit 5 is
     * "Always Idle". Only meaningful at Level 3. */
    private fun alwaysIdleDeclared(): Boolean {
        if (mdbLevel != 3) return false
        val z34 = MdbConfigStore.get("READER_CONFIG_INFO_L3").getOrNull(33)?.toInt()?.and(0xFF) ?: 0
        return (z34 and 0x20) != 0
    }

    // ------------------------------------ remote commands ------------------------------------

    /**
     * Auto-registered on MqttLib's command chain. Consumes every MDB command it recognizes
     * (returning true stops the chain); anything else returns false so the host app's own
     * listeners get a look. Ack wordings are kept byte-identical to what the dashboard already
     * matches on.
     */
    private val commandListener: (String) -> Boolean = listener@{ trimmed ->
        // Config JSON: { "setConfig": {...} } / { "resetConfig": [...] } / { "getConfig": true }.
        // Parsing, validation, and persistence all happen inside MdbConfigStore.
        if (trimmed.startsWith("{")) {
            val result = MdbConfigStore.applyJson(trimmed)
            if (!result.handled) return@listener false
            result.ackLines.forEach { report(it, true) }
            publishConfigSnapshot()
            return@listener true
        }

        when {
            trimmed == "open" -> start()
            trimmed == "close" -> stop()
            trimmed == "vendApprove" -> {
                if (approveVend()) {
                    report("[remote] vend approved requested", true)
                } else {
                    report("[remote] vend approved ignored \u2014 no VEND REQUEST pending", true)
                }
            }
            trimmed == "cancelVend" -> doRemoteCancelVend(null)
            trimmed == "cancelVend:sessionCancel" -> doRemoteCancelVend(CancelResponse.SESSION_CANCEL_REQUEST)
            trimmed == "cancelVend:vendDenied" -> doRemoteCancelVend(CancelResponse.VEND_DENIED)
            trimmed == "setCancelMode:sessionCancel" -> {
                setCancelMode(CancelResponse.SESSION_CANCEL_REQUEST)
                report("[remote] VMC-initiated VEND CANCEL will now be answered with ${CancelResponse.SESSION_CANCEL_REQUEST.label}", true)
            }
            trimmed == "setCancelMode:vendDenied" -> {
                setCancelMode(CancelResponse.VEND_DENIED)
                report("[remote] VMC-initiated VEND CANCEL will now be answered with ${CancelResponse.VEND_DENIED.label}", true)
            }
            trimmed == "beginSession" -> {
                beginSession()
                report("[remote] begin session requested", true)
            }
            trimmed == "setSessionMode:auto" -> {
                setSessionBeginMode(SessionBeginMode.AUTO)
                report("[remote] session begin mode set to auto \u2014 sessions begin by themselves", true)
            }
            trimmed == "setSessionMode:manual" -> {
                setSessionBeginMode(SessionBeginMode.MANUAL)
                report("[remote] session begin mode set to manual \u2014 only the Begin Session command starts one", true)
            }
            trimmed == "getSettings" -> publishSettingsSnapshot()
            trimmed == "setMqttLogging:on" -> setMqttLogging(true)
            trimmed == "setMqttLogging:off" -> setMqttLogging(false)
            trimmed == "setPollVisibility:on" -> setPollVisibilityRemote(true)
            trimmed == "setPollVisibility:off" -> setPollVisibilityRemote(false)
            trimmed == "setUnhandledVisibility:on" -> setUnhandledVisibilityRemote(true)
            trimmed == "setUnhandledVisibility:off" -> setUnhandledVisibilityRemote(false)
            trimmed == "setMdbLevel:1" -> setMdbLevelRemote(1)
            trimmed == "setMdbLevel:2" -> setMdbLevelRemote(2)
            trimmed == "setMdbLevel:3" -> setMdbLevelRemote(3)
            trimmed == "getConfig" -> publishConfigSnapshot()
            trimmed.startsWith("setConfig:") -> handleLegacySetConfig(trimmed.removePrefix("setConfig:"))
            trimmed.startsWith("resetConfig:") -> handleLegacyResetConfig(trimmed.removePrefix("resetConfig:"))
            else -> return@listener false
        }
        true
    }

    private fun doRemoteCancelVend(response: CancelResponse?) {
        // The dashboard's plain "cancelVend" (no suffix) uses the standing mode it configured.
        val armed = cancelVend(response ?: vmcCancelResponseMode)
        if (armed) {
            val label = response?.label ?: MdbSettings.vmcCancelMode
            report("[remote] vend cancel requested \u2014 will send $label", true)
        } else {
            report("[remote] vend cancel ignored \u2014 no session in progress", true)
        }
    }

    private fun setPollVisibilityRemote(show: Boolean) {
        setPollVisibility(show)
        report("[remote] POLL/ACK traffic will now be ${if (show) "shown" else "hidden"} in the log", true)
    }

    private fun setUnhandledVisibilityRemote(show: Boolean) {
        setUnhandledVisibility(show)
        report("[remote] unhandled commands will now be ${if (show) "shown" else "hidden"} in the log", true)
    }

    private fun setMdbLevelRemote(level: Int) {
        setMdbLevel(level)
        report("[remote] MDB level set to $level", true)
    }

    /** Legacy plain-text form ("NAME:hexBytes") kept working so the current dashboard needs no
     * flag-day migration to the JSON contract. */
    private fun handleLegacySetConfig(nameAndHex: String) {
        val parts = nameAndHex.split(":", limit = 2)
        if (parts.size != 2) {
            report("[remote] setConfig malformed, expected NAME:hexBytes \u2014 got: $nameAndHex", true)
            return
        }
        val (name, hex) = parts
        val error = MdbConfigStore.set(name, hex)
        if (error != null) {
            report("[remote] setConfig $name failed: $error", true)
        } else {
            report("[remote] $name updated -> ${MdbConfigStore.toHex(MdbConfigStore.get(name))}", true)
        }
        publishConfigSnapshot()
    }

    private fun handleLegacyResetConfig(name: String) {
        if (name !in MdbConfigStore.names()) {
            report("[remote] resetConfig: unknown config $name", true)
            return
        }
        MdbConfigStore.resetToDefault(name)
        report("[remote] $name reset to default -> ${MdbConfigStore.toHex(MdbConfigStore.get(name))}", true)
        publishConfigSnapshot()
    }

    // ------------------------------------ reporting ------------------------------------

    /** Every line the engine produces goes to the optional local [logListener] always, and onto
     * MqttLib's outbound queue only while [setMqttLogging] is on. Control-plane messages do NOT
     * pass through here (they enqueue directly), so muting logs never blinds the dashboard. */
    private fun report(line: String, showOnScreen: Boolean) {
        logListener?.invoke(line, showOnScreen)
        if (mqttLogsEnabled) MqttLib.enqueue(line)
    }

    /** Fires one [VendListener] callback ON THE OFFLOAD THREAD - the bus loop only queues it, so
     * a slow or throwing listener can never delay a bus response. Exceptions are caught and
     * surfaced as a log line. */
    private fun notifyVend(action: (VendListener) -> Unit) {
        offloadFromBus {
            val l = vendListener ?: return@offloadFromBus
            try {
                action(l)
            } catch (t: Throwable) {
                report("[mdb] vendListener threw: ${t.message}", true)
            }
        }
    }

    /** Combines two raw MDB bytes into a 16-bit value, or -1 if either byte was absent. */
    private fun word(hi: Int, lo: Int): Int = if (hi < 0 || lo < 0) -1 else hi * 256 + lo

    private fun maybePublishVmcHeartbeat() {
        val now = System.currentTimeMillis()
        if (now - lastVmcStatusPublishAtMs < VMC_HEARTBEAT_INTERVAL_MS) return
        publishVmcStatusNow()
    }

    private fun publishVmcStatusNow() = publishVmcStatus(mdbState)

    /** Builds and delivers one status snapshot for [state]. Runs on the offload thread for
     * bus-triggered transitions; the state is captured at queue time so a rapid second transition
     * can never make the first one report the wrong state. */
    private fun publishVmcStatus(state: MdbState) {
        val now = System.currentTimeMillis()
        lastVmcStatusPublishAtMs = now
        lastReportedState = state
        val recentActivity = running.get() &&
            lastMdbActivityAtMs != 0L &&
            (now - lastMdbActivityAtMs) < VMC_ACTIVITY_TIMEOUT_MS
        val json = org.json.JSONObject().apply {
            put("state", state.name)
            put("recentActivity", recentActivity)
        }.toString()
        statusListener?.invoke(json)
        MqttLib.enqueue("VMC_STATUS:$json")
    }

    // ------------------------------------ the state machine ------------------------------------
    // Ported behavior-for-behavior from the proven app engine. Every incoming command passes
    // three gates in order: the address filter, the global RESET check, and the per-state (and,
    // inside INACTIVE_STATE, per-level) dispatch.

    private fun ByteArray.byteAt(index: Int): Int =
        if (index < size) this[index].toInt() and 0xFF else -1

    private fun processCommand(cmd: ByteArray) {
        val b0 = cmd.byteAt(0)
        // Any traffic at all - ours or not - proves the bus itself is alive.
        lastMdbActivityAtMs = System.currentTimeMillis()

        // Cashless Device #1 owns 0x10-0x17. Everything outside that is the VMC talking to other
        // peripherals (bill validator, coin changer, cashless #2, ...) - never ours to answer,
        // but visible when unhandled-visibility is on, distinct from "UNHANDLED" (addressed to us
        // but unrecognized in the current state).
        if (b0 !in 0x10..0x17) {
            if (showUnhandledCmd) queueExchange(cmd, "(ignored \u2014 other peripheral, not us)", showOnScreen = false)
            return
        }

        val b1 = cmd.byteAt(1)
        val b2 = cmd.byteAt(2)
        val b3 = cmd.byteAt(3)

        var sent: String? = null

        // RESET is honored from EVERY state, checked once before the per-state dispatch so no
        // state can ever forget it. Re-arms the JUST RESET announcement so the handshake on the
        // next POLLs genuinely restarts.
        if (b0 == CMD_RESET) {
            MdbSlaveWrapper.ack()
            resetFlag = true
            justResetCount = 0
            mdbState = MdbState.INACTIVE_STATE
            sent = "ACK"
        } else when (mdbState) {
            MdbState.INACTIVE_STATE -> {
                if (b0 == CMD_POLL && justResetCount < JUST_RESET_REPEAT_COUNT) {
                    MdbSlaveWrapper.jstReset()
                    justResetCount++
                    sent = "JUST RESET"
                } else if (b0 == CMD_POLL) {
                    sent = idlePollAck()
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                } else {
                    // Setup differs enough between levels that each gets its own dedicated
                    // switch instead of one shared block full of level checks.
                    sent = when (mdbLevel) {
                        1 -> processInactiveStateLevel1(b0, b1)
                        2 -> processInactiveStateLevel2(b0, b1)
                        else -> processInactiveStateLevel3(b0, b1)
                    }
                    // Any answered setup command proves the VMC consumed JUST RESET and the
                    // handshake is progressing - stop re-announcing it on later POLLs. The
                    // up-to-5 repeat is only a nudge for a VMC that ignored the first one;
                    // repeating it mid-handshake makes the VMC restart setup in a loop.
                    if (sent != null) justResetCount = JUST_RESET_REPEAT_COUNT
                }
            }

            MdbState.DISABLED_STATE -> {
                // Mostly waiting for READER ENABLE - but real VMCs keep resending their last
                // setup command even after it was answered, and will not proceed to READER
                // ENABLE unless every retry gets a reply (observed on real hardware). So every
                // setup command is re-answered here without changing state again: EXPANSION for
                // Level 2/3, and SETUP CONFIG / SETUP MAX-MIN for machines whose last setup step
                // was SETUP itself (Level 1, where the INACTIVE->DISABLED transition happens on
                // the first 11 01 and its retries land here).
                if (b0 == CMD_POLL) {
                    sent = idlePollAck()
                } else if (b0 == CMD_SETUP && b1 == 0x00) {
                    MdbSlaveWrapper.readerConfigData(mdbLevel, 0x0E)
                    sent = "READER CONFIG DATA"
                } else if (b0 == CMD_SETUP && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    mdbState = MdbState.ENABLED_STATE
                    if (sessionBeginMode == SessionBeginMode.AUTO) sessionBeginPending = true
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    MdbSlaveWrapper.can()
                    sent = "CAN"
                } else if (b0 == CMD_EXPANSION && b1 == 0x00 && mdbLevel >= 2) {
                    MdbSlaveWrapper.readerConfigInfo(mdbLevel)
                    sent = "READER CONFIG INFO"
                } else if (b0 == CMD_EXPANSION && b1 == 0x04 && mdbLevel >= 3) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                }
            }

            MdbState.ENABLED_STATE -> {
                if (b0 == CMD_POLL) {
                    if (sessionBeginPending) {
                        MdbSlaveWrapper.sessionBegin(mdbLevel)
                        sessionBeginPending = false
                        mdbState = MdbState.VEND_STATE
                        sent = "SESSION BEGIN"
                    } else {
                        sent = idlePollAck()
                    }
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    // Reader disabled between sessions - drop back so a later READER ENABLE is
                    // handled the same way as the very first one.
                    MdbSlaveWrapper.ack()
                    sessionBeginPending = false
                    mdbState = MdbState.DISABLED_STATE
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    if (sessionBeginMode == SessionBeginMode.AUTO) sessionBeginPending = true
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    MdbSlaveWrapper.can()
                    sent = "CAN"
                } else if (b0 == CMD_VEND && b1 == 0x00 && alwaysIdleDeclared()) {
                    // "Always Idle" (spec 7.2.5): a VEND REQUEST may arrive directly here with no
                    // prior BEGIN SESSION - but only once bit 5 of the declared Level 3 feature
                    // bytes is actually set. With it clear (the default) this branch never
                    // matches and Level 3 requires BEGIN SESSION first, same as Level 1/2.
                    MdbSlaveWrapper.ack()
                    priceHigh = b2
                    priceLow = b3
                    vendRequestReceived = true
                    mdbState = MdbState.VEND_STATE
                    // Same payment hook as VEND_STATE's own branch - the app should not care
                    // which path the request arrived through.
                    notifyVend {
                        val raw = word(b2, b3)
                        it.onVendRequest(priceToAmount(raw), priceToMinorUnits(raw), word(cmd.byteAt(4), cmd.byteAt(5)))
                    }
                    sent = "ACK"
                }
            }

            MdbState.VEND_STATE -> {
                if (b0 == CMD_VEND && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    priceHigh = b2
                    priceLow = b3
                    vendRequestReceived = true
                    // The payment hook: hand the price/item to the app, which charges its
                    // gateway asynchronously and then calls approveVend() or cancelVend().
                    // Meanwhile the engine keeps ACKing polls, per the spec's delayed-response
                    // pattern, so the gateway round trip costs nothing on the bus.
                    notifyVend {
                        val raw = word(b2, b3)
                        it.onVendRequest(priceToAmount(raw), priceToMinorUnits(raw), word(cmd.byteAt(4), cmd.byteAt(5)))
                    }
                    sent = "ACK"
                } else if (b0 == CMD_VEND && b1 == 0x02) {
                    MdbSlaveWrapper.ack()
                    // Product actually dispensed - the app should capture/settle the payment.
                    notifyVend { it.onVendSuccess(word(b2, b3)) }
                    sent = "ACK (VEND SUCCESS received)"
                } else if (b0 == CMD_VEND && b1 == 0x03) {
                    // VEND FAILURE - ACK immediately (refund handshake); the idle-POLL default
                    // ACK covers the "refund done" phase. The app refunds/voids via the listener.
                    MdbSlaveWrapper.ack()
                    notifyVend { it.onVendFailure() }
                    sent = "ACK (VEND FAILURE received)"
                } else if (b0 == CMD_VEND && b1 == 0x04) {
                    MdbSlaveWrapper.ack()
                    endSessionPending = true
                    sent = "ACK"
                } else if (b0 == CMD_VEND && b1 == 0x01) {
                    // The VMC's own VEND CANCEL - answered per the configured mode, no
                    // substitution.
                    MdbSlaveWrapper.ack()
                    pendingCancelResponse = vmcCancelResponseMode
                    sessionCancelPending = true
                    vendApprovePending = false
                    sent = "ACK"
                } else if (b0 == CMD_REVALUE && b1 == 0x01 && mdbLevel >= 2) {
                    // REVALUE LIMIT REQUEST - Level 2+ only (Level 1 has no revalue at all).
                    // Answered straight from the REVALUE_LIMIT config.
                    MdbSlaveWrapper.revalueLimit()
                    sent = "REVALUE LIMIT AMOUNT"
                } else if (b0 == CMD_REVALUE && b1 == 0x00 && mdbLevel >= 2) {
                    // REVALUE REQUEST - the VMC wants to credit funds onto the payment media.
                    // This device is a payment reader, not a stored-value card: always denied
                    // (the REVALUE_DENIED payload is still config-editable).
                    MdbSlaveWrapper.revalueDenied()
                    sent = "REVALUE DENIED"
                } else if (b0 == CMD_POLL) {
                    sent = if (endSessionPending) {
                        mdbState = MdbState.ENABLED_STATE
                        MdbSlaveWrapper.endSession()
                        endSessionPending = false
                        if (sessionBeginMode == SessionBeginMode.AUTO) sessionBeginPending = true
                        notifyVend { it.onSessionEnded() }
                        "END SESSION"
                    } else if (sessionCancelPending) {
                        when (pendingCancelResponse) {
                            CancelResponse.SESSION_CANCEL_REQUEST -> MdbSlaveWrapper.sessionCancel()
                            CancelResponse.VEND_DENIED -> MdbSlaveWrapper.vendDenied()
                        }
                        sessionCancelPending = false
                        vendRequestReceived = false
                        priceHigh = 0
                        priceLow = 0
                        pendingCancelResponse.label
                    } else if (vendApprovePending && vendRequestReceived) {
                        MdbSlaveWrapper.vendApproved(priceHigh, priceLow)
                        vendApprovePending = false
                        vendRequestReceived = false
                        priceHigh = 0
                        priceLow = 0
                        "VEND APPROVED"
                    } else {
                        idlePollAck()
                    }
                }
            }
        }

        // Three distinct outcomes: a normal line, a suppressible idle ACK (reported only when
        // POLL visibility is on), or UNHANDLED (reported only when unhandled visibility is on).
        // Suppressible lines never go to the screen (showOnScreen = false) - see [logListener].
        // Formatting and delivery run on the offload thread; the response bytes already went out.
        when (sent) {
            null -> if (showUnhandledCmd) queueExchange(cmd, "UNHANDLED", showOnScreen = false)
            SUPPRESSIBLE_ACK -> if (showPollTraffic) queueExchange(cmd, "ACK", showOnScreen = false)
            else -> queueExchange(cmd, sent)
        }
    }

    /** Level 1 setup: no EXPANSION commands exist at all, so SETUP MAX/MIN is the last setup
     * step and is where INACTIVE -> DISABLED happens. */
    private fun processInactiveStateLevel1(b0: Int, b1: Int): String? = when {
        b0 == CMD_SETUP && b1 == 0x00 -> {
            MdbSlaveWrapper.readerConfigData(1, 0x0E)
            "READER CONFIG DATA"
        }
        b0 == CMD_SETUP && b1 == 0x01 -> {
            MdbSlaveWrapper.ack()
            mdbState = MdbState.DISABLED_STATE
            "ACK"
        }
        else -> null
    }

    /** Level 2 setup: EXPANSION REQUEST ID / PERIPHERAL ID is the extra, last step - the
     * transition happens there, one command later than Level 1. */
    private fun processInactiveStateLevel2(b0: Int, b1: Int): String? = when {
        b0 == CMD_SETUP && b1 == 0x00 -> {
            MdbSlaveWrapper.readerConfigData(2, 0x0E)
            "READER CONFIG DATA"
        }
        b0 == CMD_SETUP && b1 == 0x01 -> {
            MdbSlaveWrapper.ack()
            // Stays in INACTIVE_STATE - one more setup step to go at this level.
            "ACK"
        }
        b0 == CMD_EXPANSION && b1 == 0x00 -> {
            MdbSlaveWrapper.readerConfigInfo(2)
            mdbState = MdbState.DISABLED_STATE
            "READER CONFIG INFO"
        }
        else -> null
    }

    /** Level 3 setup: one more step than Level 2 - EXPANSION ENABLE OPTIONS (17H 04H) is the
     * genuine last setup command, so INACTIVE -> DISABLED happens there. */
    private fun processInactiveStateLevel3(b0: Int, b1: Int): String? = when {
        b0 == CMD_SETUP && b1 == 0x00 -> {
            MdbSlaveWrapper.readerConfigData(3, 0x0E)
            "READER CONFIG DATA"
        }
        b0 == CMD_SETUP && b1 == 0x01 -> {
            MdbSlaveWrapper.ack()
            // Stays in INACTIVE_STATE - two more setup steps still to go at this level.
            "ACK"
        }
        b0 == CMD_EXPANSION && b1 == 0x00 -> {
            MdbSlaveWrapper.readerConfigInfo(3)
            // Stays in INACTIVE_STATE - EXPANSION ENABLE OPTIONS still has to arrive next.
            "READER CONFIG INFO"
        }
        b0 == CMD_EXPANSION && b1 == 0x04 -> {
            MdbSlaveWrapper.ack()
            mdbState = MdbState.DISABLED_STATE
            "ACK"
        }
        else -> null
    }

    /** Sends a real ACK for an idle POLL but marks it suppressible - the final dispatch in
     * [processCommand] decides visibility based on the POLL-visibility setting. */
    private fun idlePollAck(): String? {
        MdbSlaveWrapper.ack()
        return SUPPRESSIBLE_ACK
    }

    /** Queues one RX/TX exchange for the offload thread - classification, formatting and
     * listener/MQTT delivery never run on the bus thread. [cmd] is a per-receive copy. */
    private fun queueExchange(cmd: ByteArray, sent: String, showOnScreen: Boolean = true) {
        offloadFromBus { showExchange(cmd, sent, showOnScreen) }
    }

    /**
     * One unified log item per exchange: what the VMC sent us (rx) and what we replied (tx)
     * travel together in the SAME messageCode - never split across two lines. The local
     * listener gets the decoded sentence (the same template the dashboard renders, so screen
     * and dashboard always read identically); MQTT gets the compact codebook item, gated by
     * [setMqttLogging] exactly as the old plain lines were.
     */
    private fun showExchange(cmd: ByteArray, sent: String, showOnScreen: Boolean = true) {
        val (event, params) = classifyExchange(cmd, sent)
        // A fresh session id is armed BEFORE the SESSION BEGIN line ships and cleared only
        // AFTER the END SESSION line ships, so both endpoints carry the id they belong to.
        // Ordering holds because every exchange rides this one offload queue in order.
        if (event == MdbLogEvent.MDB_POLL_SESSION_BEGIN) {
            RabbahLog.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8)
        }
        logListener?.invoke(RabbahLog.format(event, params), showOnScreen)
        if (mqttLogsEnabled) RabbahLog.log(event, params)
        if (event == MdbLogEvent.MDB_POLL_END_SESSION) RabbahLog.sessionId = null
    }

    /** Maps one (received frame, reply name) pair onto its unified codebook event + params:
     * p[0] is always the rx frame as hex, p[1] always the tx reply name. */
    private fun classifyExchange(cmd: ByteArray, sent: String): Pair<MdbLogEvent, List<String>> {
        val rx = cmd.joinToString(" ") { String.format("%02X", it) }
        val base = listOf(rx, sent)
        val b0 = cmd.byteAt(0)
        val b1 = cmd.byteAt(1)
        return when {
            b0 !in 0x10..0x17 -> MdbLogEvent.MDB_OTHER_PERIPHERAL to base
            sent == "UNHANDLED" -> MdbLogEvent.MDB_UNHANDLED to base
            b0 == CMD_RESET -> MdbLogEvent.MDB_RESET to base
            b0 == CMD_SETUP && b1 == 0x00 -> MdbLogEvent.MDB_SETUP_CONFIG to base + mdbLevel.toString()
            b0 == CMD_SETUP && b1 == 0x01 -> MdbLogEvent.MDB_SETUP_PRICES to base
            b0 == CMD_POLL -> when (sent) {
                "JUST RESET" -> MdbLogEvent.MDB_POLL_JUST_RESET
                "SESSION BEGIN" -> MdbLogEvent.MDB_POLL_SESSION_BEGIN
                "END SESSION" -> MdbLogEvent.MDB_POLL_END_SESSION
                "VEND APPROVED" -> MdbLogEvent.MDB_POLL_VEND_APPROVED
                "SESSION CANCEL REQUEST" -> MdbLogEvent.MDB_POLL_SESSION_CANCEL
                "VEND DENIED" -> MdbLogEvent.MDB_POLL_VEND_DENIED
                else -> MdbLogEvent.MDB_POLL
            } to base
            b0 == CMD_VEND && b1 == 0x00 -> MdbLogEvent.MDB_VEND_REQUEST to base + listOf(
                priceToMinorUnits(word(cmd.byteAt(2), cmd.byteAt(3))).toString(),
                word(cmd.byteAt(4), cmd.byteAt(5)).toString()
            )
            b0 == CMD_VEND && b1 == 0x01 -> MdbLogEvent.MDB_VEND_CANCEL to base
            b0 == CMD_VEND && b1 == 0x02 ->
                MdbLogEvent.MDB_VEND_SUCCESS to base + word(cmd.byteAt(2), cmd.byteAt(3)).toString()
            b0 == CMD_VEND && b1 == 0x03 -> MdbLogEvent.MDB_VEND_FAILURE to base
            b0 == CMD_VEND && b1 == 0x04 -> MdbLogEvent.MDB_SESSION_COMPLETE to base
            b0 == CMD_VEND && b1 == 0x05 -> MdbLogEvent.MDB_CASH_SALE to base + listOf(
                priceToMinorUnits(word(cmd.byteAt(2), cmd.byteAt(3))).toString(),
                word(cmd.byteAt(4), cmd.byteAt(5)).toString()
            )
            b0 == CMD_READER && b1 == 0x00 -> MdbLogEvent.MDB_READER_DISABLE to base
            b0 == CMD_READER && b1 == 0x01 -> MdbLogEvent.MDB_READER_ENABLE to base
            b0 == CMD_READER && b1 == 0x02 -> MdbLogEvent.MDB_READER_CANCEL to base
            b0 == CMD_REVALUE && b1 == 0x00 -> MdbLogEvent.MDB_REVALUE_REQUEST to base
            b0 == CMD_REVALUE && b1 == 0x01 -> MdbLogEvent.MDB_REVALUE_LIMIT_REQUEST to base
            b0 == CMD_EXPANSION && b1 == 0x00 -> MdbLogEvent.MDB_EXPANSION_REQUEST_ID to base
            b0 == CMD_EXPANSION && b1 == 0x04 -> MdbLogEvent.MDB_EXPANSION_ENABLE_OPTIONS to base
            b0 == CMD_EXPANSION -> MdbLogEvent.MDB_EXPANSION_OTHER to base
            else -> MdbLogEvent.MDB_EXCHANGE to base
        }
    }
}
