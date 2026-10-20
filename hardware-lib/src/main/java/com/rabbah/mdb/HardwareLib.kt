package com.rabbah.mdb

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Hardware Library: the MDB Cashless Device #1 slave, pure hardware/protocol - it contains
 * NO networking of any kind. It owns the entire protocol state machine (RESET -> SETUP ->
 * EXPANSION -> READER ENABLE -> session/vend, with per-level setup handshakes for MDB levels
 * 1/2/3), persisted settings, and the editable payload store ([MdbConfigStore]).
 *
 * Everything it produces comes out through listeners; everything it accepts goes in through
 * plain functions. How data travels (MQTT, HTTP, BLE, nothing at all) is the host app's choice:
 *
 *     HardwareLib.init(applicationContext)
 *     HardwareLib.stateListener    = { state -> ... }          // INACTIVE/DISABLED/ENABLED/VEND
 *     HardwareLib.exchangeListener = { e -> ... }              // every exchange, CMD-coded (MdbCmd)
 *     HardwareLib.logListener      = { line, show -> ... }     // human-readable lines
 *     HardwareLib.controlListener  = { tag, json -> ... }      // SETTINGS_JSON/CONFIG_JSON/VMC_STATUS/LOG
 *     HardwareLib.vendListener     = object : VendListener {}  // the payment hooks
 *     HardwareLib.start()
 *
 * Remote control from ANY transport is one call: feed the received text to [handleCommand] -
 * it consumes every command it recognizes (open/close/vendApprove/setMdbLevel:.../config JSON)
 * and returns false for anything that is not its business.
 *
 * Every exchange carries a stable integer CMD code (see [MdbCmd]): 111 = RESET->ACK,
 * 112 = POLL->JUST RESET, 113 = SETUP->READER CONFIG (hex editable), 114 = EXPANSION->
 * PERIPHERAL ID (hex editable, request captured), and so on through 134; 135 = other
 * peripheral, 136 = unhandled. [setReplyHex]/[getReplyHex] edit a reply by code;
 * [lastReceivedHex] returns the last frame the VMC sent for a code.
 */
object HardwareLib {

    /** This build of the library - log it at startup so a stale bundled AAR is caught in one
     * glance instead of a debugging session. */
    const val VERSION = "8.2.1"

    private enum class MdbState { INACTIVE_STATE, DISABLED_STATE, ENABLED_STATE, VEND_STATE }

    /**
     * Why the last vend did NOT end in [VendListener.onVendSuccess] (8.0.0). Readable as
     * [lastVendFailureReason] from inside [VendListener.onVendFailure] when the app cares.
     */
    enum class VendFailureReason {
        /** The MACHINE cancelled (VEND CANCEL 13 01 or READER CANCEL 14 02) - customer pressed cancel / selection timed out. */
        CANCELLED_BY_VMC,
        /** Your own cancelVend() (or the dashboard's cancelVend / a mode switch mid-session). */
        CANCELLED_BY_APP,
        /** VEND FAILURE 13 03 after approval - product NOT dispensed; refund/void. */
        FAILED,
        /** The session ended (END SESSION went out) while the vend was still open - the machine sent
         *  neither VEND CANCEL nor VEND FAILURE nor VEND SUCCESS (some VMCs just close the session).
         *  If you had approved, treat it like FAILED; if not, treat it like a cancel. */
        SESSION_ENDED,
        /** Bus RESET or HardwareLib.stop() while the vend was open. */
        RESET
    }

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
     *      [onVendFailure] fires for EVERY other outcome (8.0.0): machine cancel, dispense failure,
     *      session ended with the vend still open, your own cancel, reset - refund/void and reset
     *      the UI here. Exactly one of the two fires per VEND REQUEST, never both, never neither.
     *
     * Each callback runs on its own callback thread (7.24.0), NEVER on the bus thread - the bus
     * loop only queues the event and goes straight back to answering the VMC, so a slow callback
     * can not make a response miss the VMC's few-millisecond reply window, and (since 7.24.0) a
     * BLOCKED callback no longer stalls logs, exchange events or later callbacks - onVendFailure
     * reaches you even while your onVendRequest is still waiting on a gateway. Still return
     * promptly: a callback running longer than 3 s is reported as a warning line. Exceptions thrown by callbacks are caught
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

        /**
         * THE ONE "this vend did not complete" callback (8.0.0). Fires EXACTLY ONCE for every VEND
         * REQUEST that does not end in [onVendSuccess], whatever happened: the machine cancelled
         * (VEND CANCEL / READER CANCEL), the product failed to dispense (VEND FAILURE), the machine
         * simply ended the session with the vend still open (some VMCs send no cancel/failure at all),
         * your own cancelVend(), or a bus RESET / stop(). Every vend request therefore ends in exactly
         * one of onVendSuccess or onVendFailure: reset the payment UI here, void any hold, and refund
         * if you had already approved. [lastVendFailureReason] says which case it was.
         */
        fun onVendFailure() {}



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
     * EVENT-DRIVEN, never polled: fires immediately on every state transition
     * (INACTIVE/DISABLED/ENABLED/VEND), whenever bus activity appears or disappears
     * (recentActivity flips), and on start/stop. */
    @JvmStatic
    var statusListener: ((stateJson: String) -> Unit)? = null
        set(value) { field = value; if (value != null) replayStatus(value) }

    /** THE current-state listener: fires with the plain state name ("INACTIVE_STATE",
     * "DISABLED_STATE", "ENABLED_STATE", "VEND_STATE") once immediately on registration-time
     * publish and then on every transition - never on heartbeats, so it is pure edge-triggered
     * state tracking. Runs on the offload thread. */
    @JvmStatic
    var stateListener: ((state: String) -> Unit)? = null
        set(value) { field = value; if (value != null) replayState(value) }

    /** One [MdbExchangeEvent] per handled bus exchange, CMD-coded per [MdbCmd] - the structured
     * feed a transport bridge or analytics hook consumes. Fires for EVERY exchange including
     * suppressible ones (check [MdbExchangeEvent.showOnScreen]/[MdbExchangeEvent.publishRemote]
     * to honor the visibility settings). Runs on the offload thread, never the bus thread. */
    @JvmStatic
    var exchangeListener: ((event: MdbExchangeEvent) -> Unit)? = null

    /** The control-plane feed - everything that used to go straight onto the MQTT queue now
     * exits here as (tag, payload): ("SETTINGS_JSON", json), ("CONFIG_JSON", json),
     * ("VMC_STATUS", json), and ("LOG", line) for plain report lines (already gated by
     * [setMqttLogging]). A transport bridge typically forwards "LOG" as the bare line and
     * everything else as "TAG:payload". */
    @JvmStatic
    var controlListener: ((tag: String, payload: String) -> Unit)? = null
        set(value) { field = value; if (value != null) replayControl(value) }

    // --- multi-listener registration ---
    // The var slots above are single-assignment conveniences: a second `X = { ... }` silently
    // REPLACES the first, which bites the moment two parties want the same feed (the app's own
    // code plus MdbMqttBridge, most commonly). The add/remove functions below register any
    // number of listeners that all receive every event, independent of the var slot - the
    // bridge uses these, so the var slots stay free for the host app. A listener that throws
    // is caught and skipped, never fatal to the others.
    private val logListeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val exchangeListeners = CopyOnWriteArrayList<(MdbExchangeEvent) -> Unit>()
    private val controlListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    /** Registers an additional exchange listener (see [exchangeListener]). Returns true. */
    @JvmStatic
    fun addExchangeListener(listener: (MdbExchangeEvent) -> Unit): Boolean = exchangeListeners.addIfAbsent(listener)

    /** Removes one [addExchangeListener] registration. True when it was registered. */
    @JvmStatic
    fun removeExchangeListener(listener: (MdbExchangeEvent) -> Unit): Boolean = exchangeListeners.remove(listener)

    /** Registers an additional log-line listener (see [logListener]). Returns true. */
    @JvmStatic
    fun addLogListener(listener: (line: String, showOnScreen: Boolean) -> Unit): Boolean = logListeners.addIfAbsent(listener)

    /** Removes one [addLogListener] registration. True when it was registered. */
    @JvmStatic
    fun removeLogListener(listener: (line: String, showOnScreen: Boolean) -> Unit): Boolean = logListeners.remove(listener)

    /** Registers an additional state listener (see [stateListener]). Returns true. */
    @JvmStatic
    fun addStateListener(listener: (state: String) -> Unit): Boolean {
        val added = stateListeners.addIfAbsent(listener)
        if (added) replayState(listener)
        return added
    }

    /** 8.2.1: a listener registered AFTER the last transition used to wait for the next one - with direct vend ON
     *  (reported state held at ENABLED_STATE) that could be forever. Now every new state/status listener gets the
     *  current reported state at once. Delivered on the caller's thread, once, before any further events. */
    private fun replayState(listener: (String) -> Unit) {
        try { listener(reportedState(mdbState).name) } catch (_: Throwable) {}
    }
    private fun replayStatus(listener: (String) -> Unit) {
        try { listener(statusJson(mdbState, computeRecentActivity())) } catch (_: Throwable) {}
    }

    /** The state the app is told about: ENABLED_STATE while direct vend is ON and the bus is still
     *  INACTIVE/DISABLED (the machine may never send READER ENABLE), the real state otherwise. */
    private fun reportedState(state: MdbState): MdbState =
        if (directVendEnabled && (state == MdbState.INACTIVE_STATE || state == MdbState.DISABLED_STATE)) MdbState.ENABLED_STATE else state

    private fun statusJson(state: MdbState, recentActivity: Boolean): String {
        val reported = reportedState(state)
        return org.json.JSONObject().apply {
            put("state", reported.name)
            if (reported != state) {
                put("busState", state.name)
                put("directVend", true)
            }
            put("recentActivity", recentActivity)
        }.toString()
    }

    /** Removes one [addStateListener] registration. True when it was registered. */
    @JvmStatic
    fun removeStateListener(listener: (state: String) -> Unit): Boolean = stateListeners.remove(listener)

    /** Registers an additional control-plane listener (see [controlListener]). Returns true. */
    @JvmStatic
    fun addControlListener(listener: (tag: String, payload: String) -> Unit): Boolean {
        val added = controlListeners.addIfAbsent(listener)
        if (added) replayControl(listener)
        return added
    }

    /** 8.2.1: a control-plane reader registered late gets the current VMC_STATUS and SETTINGS_JSON at once. */
    private fun replayControl(listener: (String, String) -> Unit) {
        try { listener("VMC_STATUS", statusJson(mdbState, computeRecentActivity())) } catch (_: Throwable) {}
        try { listener("SETTINGS_JSON", currentSettingsJson()) } catch (_: Throwable) {}
    }

    // ------------------------------------ direct-vend state heartbeat (8.2.1) ------------------------------------
    // While direct vend is ON and the bus is still INACTIVE/DISABLED, the reported ENABLED_STATE is synthetic and
    // the bus may stay there for a long time. A state listener that somehow missed the announcement (registered in
    // between, wrong thread, its own timeout) would otherwise wait forever - so the reported state is re-sent to the
    // STATE listeners every STATE_HEARTBEAT_MS until the bus state becomes real (ENABLED/VEND) or direct vend goes
    // off. Local listeners only: VMC_STATUS over MQTT stays event-driven.
    private const val STATE_HEARTBEAT_MS = 5000L
    private val stateHeartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MdbStateHeartbeat").apply { isDaemon = true }
    }
    @Volatile private var stateHeartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null

    private fun updateStateHeartbeat() {
        if (directVendEnabled) {
            if (stateHeartbeatTask == null) {
                stateHeartbeatTask = stateHeartbeat.scheduleWithFixedDelay({
                    val real = mdbState
                    val reported = reportedState(real)
                    if (directVendEnabled && reported != real) dispatchState(reported.name)
                }, STATE_HEARTBEAT_MS, STATE_HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } else {
            stateHeartbeatTask?.cancel(false)
            stateHeartbeatTask = null
        }
    }

    /** Removes one [addControlListener] registration. True when it was registered. */
    @JvmStatic
    fun removeControlListener(listener: (tag: String, payload: String) -> Unit): Boolean = controlListeners.remove(listener)

    /**
     * RS232 codebook events - one structured (eventName, params) pair per serial exchange,
     * mirroring how MDB exchanges reach the wire as coded RABBAH_LOG items. Names map 1:1 onto
     * mqtt-lib's Rs232LogEvent, so a bridge forwards with RabbahLog.rs232(name, params).
     * While NO listener is registered, Rs232Lib falls back to plain "[rs232] ..." report lines
     * (the pre-schema wire format), so nothing changes for apps that never wire this.
     */
    private val rs232EventListeners = java.util.concurrent.CopyOnWriteArrayList<(String, List<String>) -> Unit>()

    @JvmStatic
    fun addRs232EventListener(listener: (eventName: String, params: List<String>) -> Unit): Boolean =
        rs232EventListeners.addIfAbsent(listener)

    @JvmStatic
    fun removeRs232EventListener(listener: (eventName: String, params: List<String>) -> Unit): Boolean =
        rs232EventListeners.remove(listener)

    /** True when at least one listener took the event (Rs232Lib then keeps its plain line
     * LOCAL-only, so the wire never carries the same exchange twice). */
    internal fun dispatchRs232Event(eventName: String, params: List<String>): Boolean {
        if (rs232EventListeners.isEmpty()) return false
        for (l in rs232EventListeners) try { l(eventName, params) } catch (_: Throwable) {}
        return true
    }

    /** Local-listeners-only line: shown on screen/logcat via logListener but never shipped on
     * the LOG control tag - used when the same information already left as a coded event. */
    internal fun reportLocal(line: String) = dispatchLog(line, true)

    /**
     * One call that pipes EVERYTHING the library says into any transport the host app already
     * has: every log line ("[rs232] rx=...", "[pulse] sent...", "[remote] ...") plus every
     * control snapshot ("CONFIG_JSON:{...}", "SETTINGS_JSON:{...}", "RS232_RULES_JSON:[...]").
     * Pair it with [handleCommand] fed from the app's command channel and a dashboard can see
     * and control the whole library remotely with zero other wiring:
     *
     *   HardwareLib.attachLogSink { line -> myMqtt.publish(logTopic, line) }   // everything out
     *   myMqtt.onCommand { cmd -> HardwareLib.handleCommand(cmd) }             // everything in
     */
    @JvmStatic
    fun attachLogSink(sink: (line: String) -> Unit): Boolean {
        addLogListener { line, _ ->
            // setMqttLogging:off must silence THIS path too - it is the debugging kill switch
            // for a remotely-attached device. Control snapshots below keep flowing while muted
            // (PONG/SETTINGS_JSON/commands stay alive), exactly like the demo bridge.
            if (!mqttLogsEnabled) return@addLogListener
            // When RS232 events also leave as coded items (a listener is wired), the plain
            // "[rs232] ..." twin stays local - shipping both would double every exchange.
            if (line.startsWith("[rs232]") && rs232EventListeners.isNotEmpty()) return@addLogListener
            sink(line)
        }
        // Non-LOG tags only: report() already fans LOG lines to the log listener above, so
        // forwarding the control-plane LOG twin as well shipped every line twice.
        addControlListener { tag, payload -> if (tag != "LOG") sink("$tag:$payload") }
        return true
    }

    /**
     * Wires the full remote surface onto mqtt-lib in one call, WITHOUT a compile-time
     * dependency (reflection - hardware-lib stays transport-free). When com.rabbah.mqtt.MqttLib
     * is on the classpath and initialized: every command arriving on the commands topic goes
     * through [handleCommand] (unrecognized ones fall through to the app's own listeners), and
     * every library line/control snapshot is enqueued to the log topic. Returns false, with a
     * reported reason, when mqtt-lib is absent. Safe to call once from the host app right after
     * MqttLib.init(); calling it twice wires nothing twice.
     */
    @Volatile private var rabbahMqttAttached = false
    // Every listener attach registers is kept here so detachRabbahMqtt() can unwire EXACTLY
    // what attach wired - nothing more (the demo bridge or the app's own listeners stay).
    private var rabbahMqttLib: Any? = null
    private var rabbahRemoveCmd: java.lang.reflect.Method? = null
    private var rabbahCmdListener: Any? = null
    private var rabbahRs232Listener: ((String, List<String>) -> Unit)? = null
    private var rabbahExchangeListener: ((MdbExchangeEvent) -> Unit)? = null
    private var rabbahLogListener: ((String, Boolean) -> Unit)? = null
    private var rabbahControlListener: ((String, String) -> Unit)? = null

    @JvmStatic
    fun attachRabbahMqtt(): Boolean {
        if (rabbahMqttAttached) return true
        return try {
            val mqttLib = Class.forName("com.rabbah.mqtt.MqttLib").getDeclaredField("INSTANCE").get(null)
            val enqueue = mqttLib.javaClass.getMethod("enqueue", String::class.java)
            val addCmd = mqttLib.javaClass.getMethod("addCommandListener", Function1::class.java)
            @Suppress("UNCHECKED_CAST")
            val cmdListener = { cmd: String -> handleCommand(cmd) } as Function1<String, Boolean>
            addCmd.invoke(mqttLib, cmdListener)
            // RS232 codebook events -> RabbahLog.rs232(name, params), when this mqtt-lib has it
            // (2.2.0+). Registered BEFORE the log listener so its [rs232] filter is active.
            var rs232Listener: ((String, List<String>) -> Unit)? = null
            try {
                val rabbahLog = Class.forName("com.rabbah.mqtt.RabbahLog").getDeclaredField("INSTANCE").get(null)
                val rs232 = rabbahLog.javaClass.getMethod("rs232", String::class.java, List::class.java)
                val listener: (String, List<String>) -> Unit = { name, params ->
                    // same kill switch as the line path: setMqttLogging:off mutes coded RS232 too
                    if (mqttLogsEnabled) {
                        try { rs232.invoke(rabbahLog, name, params) } catch (_: Throwable) {}
                    }
                }
                addRs232EventListener(listener)
                rs232Listener = listener
            } catch (_: Throwable) {
                // older mqtt-lib: RS232 stays on plain report lines, exactly as before
            }
            // 8.2.1: MDB exchanges go out as CODED RabbahLog items from HERE (what the app-side
            // MdbMqttBridge used to do), so attach is a complete bridge on its own. Their plain text
            // twin stays local (see deliveringExchange) - one wire item per exchange, never two.
            var exchangeFwd: ((MdbExchangeEvent) -> Unit)? = null
            try {
                val rabbahLog = Class.forName("com.rabbah.mqtt.RabbahLog").getDeclaredField("INSTANCE").get(null)
                val eventClass = Class.forName("com.rabbah.mqtt.MdbLogEvent")
                val valueOf = eventClass.getMethod("valueOf", String::class.java)
                val logEventType = Class.forName("com.rabbah.mqtt.RabbahLogEvent")
                val log = rabbahLog.javaClass.getMethod("log", logEventType, List::class.java)
                val raw = rabbahLog.javaClass.getMethod("raw", String::class.java)
                val setSession = rabbahLog.javaClass.getMethod("setSessionId", String::class.java)
                val fwd: (MdbExchangeEvent) -> Unit = { e ->
                    if (e.publishRemote) {
                        try {
                            setSession.invoke(rabbahLog, e.sessionId)
                            val ev = try { valueOf.invoke(null, e.logEventName) } catch (_: Throwable) { null }
                            if (ev != null) log.invoke(rabbahLog, ev, e.params) else raw.invoke(rabbahLog, e.message)
                        } catch (_: Throwable) {}
                    }
                }
                addExchangeListener(fwd)
                exchangeFwd = fwd
            } catch (_: Throwable) {
                // older mqtt-lib without the codebook: exchanges ship as plain lines below, as before
            }
            val codedExchanges = exchangeFwd != null
            // Same wiring attachLogSink() does, registered directly so detach can remove it.
            val logListener: (String, Boolean) -> Unit = { line, _ ->
                if (mqttLogsEnabled &&
                    !(line.startsWith("[rs232]") && rs232EventListeners.isNotEmpty()) &&
                    !(codedExchanges && deliveringExchange)
                ) {
                    try { enqueue.invoke(mqttLib, line) } catch (_: Throwable) {}
                }
            }
            addLogListener(logListener)
            val controlListener: (String, String) -> Unit = { tag, payload ->
                if (tag != "LOG") {
                    try { enqueue.invoke(mqttLib, "$tag:$payload") } catch (_: Throwable) {}
                }
            }
            addControlListener(controlListener)
            rabbahMqttLib = mqttLib
            rabbahRemoveCmd = try {
                mqttLib.javaClass.getMethod("removeCommandListener", Function1::class.java)
            } catch (_: Throwable) { null }
            rabbahCmdListener = cmdListener
            rabbahExchangeListener = exchangeFwd
            rabbahRs232Listener = rs232Listener
            rabbahLogListener = logListener
            rabbahControlListener = controlListener
            rabbahMqttAttached = true
            report("[remote] attached to mqtt-lib - all remote commands and logs are live", true)
            true
        } catch (t: Throwable) {
            report("[remote] attachRabbahMqtt: mqtt-lib not available (${t.javaClass.simpleName})", true)
            false
        }
    }

    /**
     * The true disconnect: removes every listener [attachRabbahMqtt] registered - command
     * handling, log lines, control snapshots, coded RS232 events. After this the device is
     * back to mqtt-lib-only (ping/version/attachHardware still answer; hardware commands
     * report unknown; no hardware logs ship). Re-wire any time with attachHardware, and an
     * app restart auto-attaches again when MqttConfig.autoAttachHardware is on (default).
     * Remote command: detachHardware (answered by mqtt-lib 2.5.0+ itself).
     */
    @JvmStatic
    fun detachRabbahMqtt(): Boolean {
        if (!rabbahMqttAttached) return false
        try { rabbahRemoveCmd?.invoke(rabbahMqttLib, rabbahCmdListener) } catch (_: Throwable) {}
        rabbahRs232Listener?.let { removeRs232EventListener(it) }
        rabbahExchangeListener?.let { removeExchangeListener(it) }
        rabbahLogListener?.let { removeLogListener(it) }
        rabbahControlListener?.let { removeControlListener(it) }
        rabbahMqttLib = null
        rabbahRemoveCmd = null
        rabbahCmdListener = null
        rabbahRs232Listener = null
        rabbahExchangeListener = null
        rabbahLogListener = null
        rabbahControlListener = null
        rabbahMqttAttached = false
        report("[remote] hardware-lib detached from mqtt-lib", true)
        return true
    }

    private fun dispatchLog(line: String, showOnScreen: Boolean) {
        try { logListener?.invoke(line, showOnScreen) } catch (_: Throwable) {}
        for (l in logListeners) try { l(line, showOnScreen) } catch (_: Throwable) {}
    }

    private fun dispatchState(state: String) {
        try { stateListener?.invoke(state) } catch (_: Throwable) {}
        for (l in stateListeners) try { l(state) } catch (_: Throwable) {}
    }

    private fun dispatchExchange(event: MdbExchangeEvent) {
        try { exchangeListener?.invoke(event) } catch (_: Throwable) {}
        for (l in exchangeListeners) try { l(event) } catch (_: Throwable) {}
    }

    private fun dispatchControl(tag: String, payload: String) {
        try { controlListener?.invoke(tag, payload) } catch (_: Throwable) {}
        for (l in controlListeners) try { l(tag, payload) } catch (_: Throwable) {}
    }

    /** The current MDB state, readable on demand from Android code:
     * "INACTIVE_STATE" (port closed / pre-setup), "DISABLED_STATE" (configured, not selling),
     * "ENABLED_STATE" (waiting for a session), "VEND_STATE" (session live).
     * 8.2.1: the same REPORTED state the listeners deliver - with direct vend ON this is
     * ENABLED_STATE while the bus is still INACTIVE/DISABLED, so a health check that polls
     * instead of listening can never disagree with one that listens. [busState] is the real one. */
    val currentState: String
        get() = reportedState(mdbState).name

    /** The real bus state regardless of direct vend (what VMC_STATUS carries as busState). */
    val busState: String
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
    // drop-oldest: a stalled consumer costs log lines, never the bus.
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
    // 7.25.0: polls still to wait after END SESSION before auto mode re-arms SESSION BEGIN. Some
    // VMCs (Sielaff) send READER ENABLE *after* their first post-session POLL; arming on that POLL
    // put the engine in VEND_STATE before the enable arrived, which then went unanswered and the
    // VMC retried it forever. A READER ENABLE during the cooldown arms immediately (ENABLED_STATE).
    private var autoBeginCooldownPolls = 0
    /** Configured cooldown length (polls) - mirror of MdbSettings.autoBeginCooldownPolls, cached for the bus thread. */
    @Volatile private var autoBeginCooldownSetting = MdbSettings.AUTO_BEGIN_COOLDOWN_DEFAULT
    /** 7.28.0: accept VEND REQUEST in any state (mirror of MdbSettings.directVend, cached for the bus thread). */
    @Volatile private var directVendEnabled = false
    /** 8.2.0: force SESSION CANCEL REQUEST after every VEND SUCCESS / FAILURE (mirror of MdbSettings.forceSessionEndAfterVend). */
    @Volatile private var forceSessionEndEnabled = false
    /** 8.0.0: a VEND REQUEST arrived and no outcome (success / failure) has been reported to the app yet. */
    @Volatile private var vendOpen = false

    /** Why the most recent [VendListener.onVendFailure] fired (8.0.0); null until the first one. */
    @Volatile var lastVendFailureReason: VendFailureReason? = null
        private set
    // Written from app threads (gateway callbacks -> approveVend/cancelVend) and read on the bus
    // thread every POLL: volatile so an approval can never sit invisible in another core cache.
    @Volatile private var sessionCancelPending = false
    @Volatile private var pendingCancelResponse = CancelResponse.SESSION_CANCEL_REQUEST
    @Volatile private var vmcCancelResponseMode = CancelResponse.SESSION_CANCEL_REQUEST
    @Volatile private var vendApprovePending = false
    @Volatile private var vendRequestReceived = false
    private var showPollTraffic = false
    private var showUnhandledCmd = false
    private var showOtherPeripheral = false
    @Volatile private var showEmptySessions = false
    private var mdbLevel = 2
    /** Gates ONLY the log lines going to MQTT (see [report]) - local listeners and the
     * control-plane messages (VMC_STATUS/SETTINGS_JSON/CONFIG_JSON) are never gated. */
    @Volatile private var mqttLogsEnabled = true

    /** Sentinel for "matched a real POLL, sent a plain ACK, suppressible from the log" -
     * distinct from null (no branch matched at all). */
    private const val SUPPRESSIBLE_ACK = " SUPPRESSIBLE_ACK"

    /** Correlates every exchange inside one vend: armed right before the SESSION BEGIN exchange
     * is delivered, cleared right after END SESSION ships - both on the ordered offload queue,
     * so every event of one session carries the id it belongs to. */
    @JvmStatic
    @Volatile
    var sessionId: String? = null
        private set

    /** The last frame the VMC sent for each CMD code, as hex - "save what we receive", so e.g.
     * the EXPANSION REQUEST ID payload (code 4) or the max/min prices (code 5) can be read back
     * any time. Written on the offload thread, safe to read from anywhere. */
    private val lastRxByCode = ConcurrentHashMap<Int, String>()

    @Volatile private var lastMdbActivityAtMs = 0L
    /** The state most recently reported through statusListener/VMC_STATUS - compared after each
     * processed command so any transition publishes IMMEDIATELY. */
    @Volatile private var lastReportedState = MdbState.INACTIVE_STATE
    /** The recentActivity value most recently reported - VMC_STATUS is EVENT-DRIVEN: it
     * publishes when the state changes or when bus activity appears/disappears, never on a
     * periodic timer. */
    @Volatile private var lastReportedActivity = false
    private const val VMC_ACTIVITY_TIMEOUT_MS = 5000L

    // ------------------------------------ lifecycle ------------------------------------

    /**
     * Initializes persistence and restores settings. Idempotent. Attach the listeners you need
     * BEFORE calling this - the initial settings snapshot fires here, and a listener attached
     * later only sees the next one (or ask again via handleCommand("getSettings")).
     *
     * There is deliberately NO transport registration in here: to remote-control this library,
     * feed whatever text your transport receives to [handleCommand].
     *
     * Returns true when initialization ran (or had already run - idempotent success).
     */
    fun init(context: Context): Boolean {
        if (initialized) return true
        initialized = true
        MdbConfigStore.init(context.applicationContext)
        MdbSettings.init(context.applicationContext)
        Rs232Lib.init(context.applicationContext) // restore persisted RS232 rules
        loadPersistedSettings()
        // Announce the real, persisted settings immediately so any dashboard already open (or one
        // that asks via "getSettings" a moment later) sees the truth, never assumed defaults.
        publishSettingsSnapshot()
        return true
    }

    /** Opens the MDB port and starts the worker loop. The engine state is fully reset and the
     * INACTIVE status is announced immediately. Returns true when the port opened and the loops
     * started (or were already running); false when the MDB port could not be opened. */
    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        // A device without the CM30 runtime (emulator, other hardware) must degrade to a
        // reported failure, never crash the host app - the vendor class loads its native
        // library on first touch, and that throw is caught here like any other open failure.
        val opened = try {
            MdbSlaveWrapper.open()
        } catch (t: Throwable) {
            report("open() failed \u2014 CM30 MDB hardware unavailable (${t.javaClass.simpleName})", true)
            false
        }
        if (!opened) {
            running.set(false)
            report("open() failed \u2014 could not open MDB slave port", true)
            return false
        }

        mdbState = MdbState.INACTIVE_STATE
        resetFlag = false
        justResetCount = 0
        sessionBeginPending = false
        endSessionPending = false
        autoBeginCooldownPolls = 0
        vendOpen = false
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
                maybePublishActivityEdge()
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
        return true
    }

    /** Queues [task] for the offload thread, dropping the oldest queued task when full - the bus
     * loop must never block on its own bookkeeping. */
    private fun offloadFromBus(task: Runnable) {
        if (!busOffloadQueue.offer(task)) {
            busOffloadQueue.poll()
            busOffloadQueue.offer(task)
        }
    }

    /** Closes the port, resets to INACTIVE, and announces the state change immediately.
     * Returns true when a running engine was stopped; false when it was not running. */
    fun stop(): Boolean {
        val wasRunning = running.getAndSet(false)
        worker = null
        offloadWorker = null
        try { MdbSlaveWrapper.close() } catch (_: Throwable) { /* no CM30 runtime - see start() */ }
        mdbState = MdbState.INACTIVE_STATE
        lastMdbActivityAtMs = 0L
        abortOpenVend(VendFailureReason.RESET)
        publishVmcStatusNow()
        return wasRunning
    }

    // ------------------------------------ session / vend control ------------------------------------

    /** Arms BEGIN SESSION for the next POLL (the "card tap"). In AUTO mode sessions arm
     * themselves so this never needs calling; in MANUAL mode this - or the dashboard's
     * "beginSession" command, which calls the same thing - is what starts a session.
     * Returns true when it armed; false when a session is already live (nothing to arm). */
    fun beginSession(): Boolean {
        if (mdbState == MdbState.VEND_STATE) return false
        sessionBeginPending = true
        return true
    }

    /**
     * Arms VEND APPROVED - only if a VEND REQUEST has actually arrived (checked here, at call
     * time). Calling before any request exists is discarded outright, never queued, so it cannot
     * silently auto-approve whatever the customer selects next. Returns whether it armed.
     */
    fun approveVend(): Boolean {
        if (!vendRequestReceived) {
            report("[app] approveVend IGNORED - no VEND REQUEST pending (state=$mdbState) - " +
                "the machine cancelled/timed out first, or a different engine instance got the request", true)
            return false
        }
        vendApprovePending = true
        report("[app] approveVend armed - VEND APPROVED goes out on the next POLL", true)
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
        if (mdbState != MdbState.VEND_STATE) {
            report("[app] cancelVend IGNORED - no session live (state=$mdbState)", true)
            return false
        }
        pendingCancelResponse = response
        sessionCancelPending = true
        vendApprovePending = false
        report("[app] cancelVend armed - ${response.label} goes out on the next POLL", true)
        abortOpenVend(VendFailureReason.CANCELLED_BY_APP)
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
    fun setAutoSession(enabled: Boolean): Boolean {
        setSessionBeginMode(if (enabled) SessionBeginMode.AUTO else SessionBeginMode.MANUAL)
        return true
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
            MdbState.ENABLED_STATE -> {
                autoBeginCooldownPolls = 0
                sessionBeginPending = (mode == SessionBeginMode.AUTO)
            }
            else -> {}
        }
    }

    /** Idle POLLs auto mode waits after END SESSION before re-arming SESSION BEGIN (7.25.0).
     * Persisted. Same thing the dashboard's "setAutoBeginCooldown:N" does. 0 = re-arm on the
     * very next POLL (pre-7.25.0 behavior); default 3. Range 0..50. */
    fun setAutoBeginCooldown(polls: Int) {
        val clamped = polls.coerceIn(MdbSettings.AUTO_BEGIN_COOLDOWN_MIN, MdbSettings.AUTO_BEGIN_COOLDOWN_MAX)
        MdbSettings.autoBeginCooldownPolls = clamped
        autoBeginCooldownSetting = clamped
        publishSettingsSnapshot()
        report(
            if (clamped == 0) "[remote] auto-begin cooldown set to 0 - SESSION BEGIN goes out on the first POLL after END SESSION"
            else "[remote] auto-begin cooldown set to $clamped POLLs - after END SESSION the VMC gets $clamped idle POLLs (or its READER ENABLE) before SESSION BEGIN",
            true
        )
    }

    /** Current auto-begin cooldown in POLLs ([setAutoBeginCooldown]). */
    val autoBeginCooldown: Int
        get() = autoBeginCooldownSetting

    /**
     * Direct vend (7.28.0). Persisted. Same as the dashboard "setDirectVend:on|off". OFF (default):
     * a VEND REQUEST is only accepted inside a session (after READER ENABLE + SESSION BEGIN), per spec.
     * ON: a VEND REQUEST (13 00) is accepted in INACTIVE, DISABLED and ENABLED state too - the engine
     * ACKs it, captures the price/item, jumps straight to the session state and fires
     * [VendListener.onVendRequest]; [approveVend] / [cancelVend] then work exactly as usual. For VMCs
     * that skip the setup / enable / begin-session handshake and send the request immediately.
     */
    fun setDirectVend(enabled: Boolean): Boolean {
        MdbSettings.directVend = enabled
        directVendEnabled = enabled
        updateStateHeartbeat()
        publishSettingsSnapshot()
        report(
            if (enabled) "[remote] direct vend ON - a VEND REQUEST is accepted in ANY state (no handshake / session required); state listener reports ENABLED_STATE from now on"
            else "[remote] direct vend OFF - a VEND REQUEST is only accepted inside a session (spec behavior)",
            true
        )
        // 8.1.0: re-evaluate the reported state right away, so an app waiting on the state listener for
        // ENABLED_STATE passes its health check the moment direct vend is switched on (and drops back to
        // the real bus state when it is switched off). The listener fires only when the name changes.
        publishVmcStatusNow()
        return true
    }

    /** True when VEND REQUESTs are accepted in any state ([setDirectVend]). */
    val isDirectVend: Boolean
        get() = directVendEnabled

    /**
     * Force session end after vend (8.2.0). Persisted. Same as the dashboard "setForceSessionEnd:on|off".
     * OFF (default): after VEND SUCCESS the reader waits for the VMC's SESSION COMPLETE and
     * then sends END SESSION, per spec. ON: as soon as VEND SUCCESS is ACKed the reader arms
     * SESSION CANCEL REQUEST (04) for the next POLL - the VMC answers with SESSION COMPLETE and the session
     * closes with END SESSION as usual. For VMCs that keep the session open after a vend and never send
     * SESSION COMPLETE on their own. The VendListener callbacks are unchanged (onVendSuccess / onVendFailure
     * fire exactly as before); only the wire-level close is forced.
     */
    fun setForceSessionEnd(enabled: Boolean): Boolean {
        MdbSettings.forceSessionEndAfterVend = enabled
        forceSessionEndEnabled = enabled
        publishSettingsSnapshot()
        report(
            if (enabled) "[remote] force session end ON - SESSION CANCEL REQUEST goes out on the POLL after every VEND SUCCESS"
            else "[remote] force session end OFF - the session closes on the VMC's SESSION COMPLETE (spec behavior)",
            true
        )
        return true
    }

    /** True when the reader forces the session closed after every vend ([setForceSessionEnd]). */
    val isForceSessionEnd: Boolean
        get() = forceSessionEndEnabled

    /** The standing choice of what a plain [cancelVend] sends and how the VMC's own VEND CANCEL
     * (13H 01H) gets answered. Persisted. Same thing the dashboard's "setCancelMode:..." does. */
    fun setCancelMode(mode: CancelResponse): Boolean {
        vmcCancelResponseMode = mode
        MdbSettings.vmcCancelMode = if (mode == CancelResponse.VEND_DENIED) "vendDenied" else "sessionCancel"
        publishSettingsSnapshot()
        return true
    }

    /**
     * Master on/off for publishing the engine's LOG LINES over MQTT. Persisted. Same as the
     * dashboard's "setMqttLogging:on|off". While OFF: local [logListener] still gets every line
     * (your on-device log keeps working), and the control plane (VMC_STATUS heartbeat,
     * SETTINGS_JSON, CONFIG_JSON, command acks' snapshots, PONG) still publishes - so the
     * dashboard still sees the device state and can still send commands, including the one that
     * turns logging back on. Only the log stream itself goes quiet.
     */
    fun setMqttLogging(enabled: Boolean): Boolean {
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
        return true
    }

    /** True while the engine's log lines are being published over MQTT. */
    val isMqttLoggingEnabled: Boolean
        get() = mqttLogsEnabled

    /** Log-debugging toggle: report idle POLL/ACK exchanges. Persisted. Same as the dashboard's
     * "setPollVisibility:on|off". */
    fun setPollVisibility(show: Boolean): Boolean {
        showPollTraffic = show
        MdbSettings.showPollTraffic = show
        publishSettingsSnapshot()
        return true
    }

    /** Log-debugging toggle: report commands unrecognized in the current state. Persisted. Same
     * as the dashboard's "setUnhandledVisibility:on|off". */
    fun setUnhandledVisibility(show: Boolean): Boolean {
        showUnhandledCmd = show
        MdbSettings.showUnhandledCmd = show
        publishSettingsSnapshot()
        return true
    }

    /** Log-debugging toggle: report bus traffic addressed to OTHER peripherals (coin changer,
     * bill validator - anything outside our 0x10-0x17 range). Persisted, OFF by default: a real
     * machine polls its other peripherals many times a second and those lines bury the cashless
     * conversation. Separate from [setUnhandledVisibility] on purpose, so you can watch every
     * cashless command including UNHANDLED ones without the rest of the bus. Same as the
     * dashboard's "setPeripheralVisibility:on|off". */
    fun setPeripheralVisibility(show: Boolean): Boolean {
        showOtherPeripheral = show
        MdbSettings.showOtherPeripheral = show
        publishSettingsSnapshot()
        return true
    }

    /** Log-debugging toggle: report session cycles that never received a VEND REQUEST. OFF by
     * default - in AUTO session mode the machine produces a BEGIN SESSION -> SESSION COMPLETE ->
     * END SESSION cycle on every re-arm with no customer, and those bury the real transactions.
     * While off, the BEGIN SESSION event is held back and delivered ONLY when a vend request
     * arrives in that session - a real transaction still logs its complete chain in order, an
     * empty cycle logs nothing. Persisted. Same as the dashboard's
     * "setEmptySessionVisibility:on|off". */
    fun setEmptySessionVisibility(show: Boolean): Boolean {
        showEmptySessions = show
        MdbSettings.showEmptySessions = show
        publishSettingsSnapshot()
        return true
    }

    /** [level] must be 1, 2, or 3: the feature level reported in READER CONFIG DATA, which
     * setup-phase handshake runs, and which BEGIN SESSION / PERIPHERAL ID payloads are sent.
     * Persisted. Same thing the dashboard's "setMdbLevel:1|2|3" does.
     * Returns true when applied; false (with a reported line) for any other level. */
    fun setMdbLevel(level: Int): Boolean {
        if (level !in 1..3) {
            report("[app] setMdbLevel rejected: MDB level must be 1, 2, or 3 (got $level)", true)
            return false
        }
        mdbLevel = level
        MdbSettings.mdbLevel = level
        publishSettingsSnapshot()
        return true
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
        showOtherPeripheral = MdbSettings.showOtherPeripheral
        showEmptySessions = MdbSettings.showEmptySessions
        mdbLevel = MdbSettings.mdbLevel
        autoBeginCooldownSetting = MdbSettings.autoBeginCooldownPolls
        directVendEnabled = MdbSettings.directVend
        updateStateHeartbeat()
        forceSessionEndEnabled = MdbSettings.forceSessionEndAfterVend
        PulseLib.loadPersistedReadyFeedback()
        PulseLib.loadPersistedPulseMode()
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
        put("showOtherPeripheral", MdbSettings.showOtherPeripheral)
        put("showEmptySessions", MdbSettings.showEmptySessions)
        put("mdbLevel", MdbSettings.mdbLevel)
        put("mqttLogsEnabled", MdbSettings.mqttLogsEnabled)
        put("autoBeginCooldownPolls", MdbSettings.autoBeginCooldownPolls)
        put("readyFeedbackEnabled", MdbSettings.readyFeedbackEnabled)
        put("readyFeedbackValue", MdbSettings.readyFeedbackValue)
        put("readyFeedbackChannel", MdbSettings.readyFeedbackChannel)
        put("readyFeedbackChannels", org.json.JSONObject().also { o -> for ((ch, v) in PulseLib.readyFeedbackChannels) o.put(ch.toString(), v) })
        put("pulseMode", MdbSettings.pulseMode)
        put("directVend", MdbSettings.directVend)
        put("forceSessionEndAfterVend", MdbSettings.forceSessionEndAfterVend)
    }.toString()

    @Volatile private var warnedNotPersistent = false

    internal fun publishSettingsSnapshot() {
        // Settings work from memory without init(), but then reset on every restart - say so
        // ONCE instead of letting the operator wonder why the device forgot its switches.
        if (!MdbSettings.isPersistent && !warnedNotPersistent) {
            warnedNotPersistent = true
            report("[remote] WARNING: HardwareLib.init(context) was never called - settings are " +
                "in-memory only and will reset on app restart", true)
        }
        dispatchControl("SETTINGS_JSON", currentSettingsJson())
    }

    private fun publishConfigSnapshot() {
        dispatchControl("CONFIG_JSON", MdbConfigStore.snapshotJson())
    }

    /** Whether "Always Idle" is in effect - read live from bit 5 of Z34, the last of the 4
     * optional-feature-bits bytes in the Level 3 PERIPHERAL ID payload, instead of a separate
     * setting that could drift from what is actually declared to the VMC on the wire. Per the
     * MDB/ICP spec (7.4.4) the 32 bits are sent MSB-first, so Z34 holds bits 7-0 and bit 5 is
     * "Always Idle". Only meaningful at Level 3. */
    /**
     * Accepts a VEND REQUEST that arrived OUTSIDE a session (Always Idle, or direct vend mode): ACK,
     * capture price/item, jump to the session state and fire the same payment hook as the in-session
     * branch - the app never has to care which path the request took. Returns the tx label.
     */
    private fun acceptDirectVendRequest(cmd: ByteArray, b2: Int, b3: Int): String {
        val from = mdbState
        MdbSlaveWrapper.ack()
        priceHigh = b2
        priceLow = b3
        vendRequestReceived = true
        vendOpen = true
        vendApprovePending = false
        sessionCancelPending = false
        sessionBeginPending = false
        autoBeginCooldownPolls = 0
        mdbState = MdbState.VEND_STATE
        if (directVendEnabled) {
            report("[mdb] VEND REQUEST accepted directly in $from (direct vend ON, no session handshake) - waiting for approveVend()/cancelVend()", true)
        }
        notifyVend {
            val raw = word(b2, b3)
            it.onVendRequest(priceToAmount(raw), priceToMinorUnits(raw), word(cmd.byteAt(4), cmd.byteAt(5)))
        }
        return "ACK"
    }

    /**
     * Reports the end of an open vend exactly once (8.0.0): no-op unless a VEND REQUEST is still
     * unresolved. Every non-success exit of the vend cycle calls this, so the app always gets ONE
     * onVendFailure per vend request - including the machines that end the session silently.
     */
    private fun abortOpenVend(reason: VendFailureReason) {
        if (!vendOpen) return
        vendOpen = false
        lastVendFailureReason = reason
        report("[mdb] vend failed: $reason - onVendFailure fires", true)
        notifyVend { it.onVendFailure() }
    }

    /**
     * 8.1.0: RS232 rule "event" -> the same VendListener the MDB engine drives, with the same
     * guarantee (exactly one onVendSuccess/onVendFailure per onVendRequest). [price] is the value
     * the rule extracted from the frame, treated as minor units; -1 when the frame carried none.
     */
    internal fun rs232VendEvent(event: String, price: Int, ruleName: String, frameHex: String) {
        when (event) {
            Rs232Lib.EVENT_VEND_REQUEST -> {
                if (price < 0) {
                    report("[rs232] rule $ruleName is event=vendRequest but no price was found in $frameHex - onVendRequest NOT fired", true)
                    return
                }
                // A new request while one is still open: close the old one first, like the MDB
                // engine does when a session ends with a vend open.
                abortOpenVend(VendFailureReason.SESSION_ENDED)
                vendOpen = true
                report("[rs232] $ruleName -> onVendRequest price=$price (minor units)", true)
                notifyVend { it.onVendRequest(price / 100.0, price, -1) }
            }
            Rs232Lib.EVENT_VEND_SUCCESS -> {
                if (!vendOpen) { report("[rs232] $ruleName (vendSuccess) with no vend open - ignored", true); return }
                vendOpen = false
                report("[rs232] $ruleName -> onVendSuccess", true)
                notifyVend { it.onVendSuccess(-1) }
            }
            Rs232Lib.EVENT_VEND_FAILURE -> {
                if (!vendOpen) { report("[rs232] $ruleName (vendFailure) with no vend open - ignored", true); return }
                abortOpenVend(VendFailureReason.FAILED)
            }
            Rs232Lib.EVENT_VEND_CANCEL -> {
                if (!vendOpen) { report("[rs232] $ruleName (vendCancel) with no vend open - ignored", true); return }
                abortOpenVend(VendFailureReason.CANCELLED_BY_VMC)
            }
        }
    }

    /**
     * 8.2.1: a vend-group command (13 01 VEND CANCEL, 13 02 VEND SUCCESS, 13 03 VEND FAILURE, 13 04 SESSION
     * COMPLETE) arriving OUTSIDE VEND_STATE - typically a VMC still finishing a session after a RESET or an app
     * restart. Before 8.2.1 these went UNHANDLED and some VMCs re-sent SESSION COMPLETE forever. Now every one is
     * ACKed, and SESSION COMPLETE additionally gets END SESSION on the next POLL so the VMC can close its side.
     * The engine's own state does not change: it has no session open.
     */
    private fun straySessionCommand(b1: Int): String {
        MdbSlaveWrapper.ack()
        val name = when (b1) { 0x01 -> "VEND CANCEL"; 0x02 -> "VEND SUCCESS"; 0x03 -> "VEND FAILURE"; else -> "SESSION COMPLETE" }
        if (b1 == 0x04) {
            endSessionPending = true
            report("[mdb] $name received with no session open (state=$mdbState) - ACK, END SESSION goes out on the next POLL", true)
            return "ACK (stray SESSION COMPLETE - END SESSION next POLL)"
        }
        report("[mdb] $name received with no session open (state=$mdbState) - ACK only", true)
        return "ACK (stray $name)"
    }

    /** The END SESSION promised by [straySessionCommand], sent on the next POLL; the state stays as it is. */
    private fun strayEndSession(): String {
        MdbSlaveWrapper.endSession()
        endSessionPending = false
        sessionCancelPending = false
        vendApprovePending = false
        vendRequestReceived = false
        return "END SESSION (closing a stray session, state unchanged)"
    }

    /** 8.2.0: with force-session-end ON, arm SESSION CANCEL REQUEST for the next POLL - only after VEND SUCCESS (a failed vend keeps the spec flow). Returns whether it armed. */
    private fun armForcedSessionEnd(after: String): Boolean {
        if (!forceSessionEndEnabled) return false
        if (endSessionPending) return false   // the VMC already sent SESSION COMPLETE - END SESSION is on its way
        pendingCancelResponse = CancelResponse.SESSION_CANCEL_REQUEST
        sessionCancelPending = true
        report("[mdb] force session end: SESSION CANCEL REQUEST goes out on the next POLL (after $after)", true)
        return true
    }

    private fun alwaysIdleDeclared(): Boolean {
        if (mdbLevel != 3) return false
        val z34 = MdbConfigStore.get("READER_CONFIG_INFO_L3").getOrNull(33)?.toInt()?.and(0xFF) ?: 0
        return (z34 and 0x20) != 0
    }

    // ------------------------------------ remote commands ------------------------------------

    /**
     * THE remote-control entry point, transport-agnostic: feed it whatever text arrives from
     * MQTT, HTTP, BLE, adb, a debug UI - anywhere. Consumes every command it recognizes
     * (returning true); anything else returns false so the caller's own handlers get a look.
     * Ack wordings are kept byte-identical to what the dashboard already matches on.
     *
     * Recognized: open/close, vendApprove, cancelVend[:mode], setCancelMode:..., beginSession,
     * setSessionMode:auto|manual, getSettings, setMqttLogging:on|off, setPollVisibility:on|off,
     * setPeripheralVisibility:on|off, setUnhandledVisibility:on|off, setMdbLevel:1|2|3,
     * getConfig, setConfig:NAME:hex, resetConfig:NAME, the config JSON contract
     * ({"setConfig":{...}} / {"resetConfig":[...]} / {"getConfig":true}),
     * simulateExchange:HEX, initPulse:true|false, sendPulse:width,period,count,
     * setReadyFeedback:value[,channel]|off, checkMachineReady, and the RS232 set:
     * rs232Open[:baud[,dataBits,stopBits,parity]], rs232Close, rs232Send:HEX,
     * rs232SendFrame:HEADER;DATAHEX, rs232SendAscii:HEADER;TEXT, rs232Simulate:HEX,
     * rs232Crc:on|off, rs232Gap:MS, getRs232Rules,
     * clearRs232Rules, plus the rule-table JSON {"setRs232Rules":[{name,rx,tx,priceHi,priceLo}]}.
     */
    @JvmStatic
    fun handleCommand(command: String): Boolean {
        val trimmed = command.trim()
        // Config JSON: { "setConfig": {...} } / { "resetConfig": [...] } / { "getConfig": true }.
        // Parsing, validation, and persistence all happen inside MdbConfigStore.
        if (trimmed.startsWith("{")) {
            val result = MdbConfigStore.applyJson(trimmed)
            if (result.handled) {
                result.ackLines.forEach { report(it, true) }
                publishConfigSnapshot()
                return true
            }
            // RS232 rule table: { "setRs232Rules": [ {name, rx, tx, priceHi?, priceLo?}, ... ] }
            val rulesArr = try {
                org.json.JSONObject(trimmed).optJSONArray("setRs232Rules")
            } catch (_: Exception) {
                null
            }
            if (rulesArr != null) {
                val err = Rs232Lib.setRulesJson(rulesArr.toString())
                report(
                    if (err == null) "[remote] RS232 rules updated (${rulesArr.length()} rule(s))"
                    else "[remote] setRs232Rules failed: $err",
                    true
                )
                dispatchControl("RS232_RULES_JSON", Rs232Lib.rulesJson())
                return true
            }
            return false
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
            trimmed == "setDirectVend:on" || trimmed == "setDirectVend:true" -> setDirectVend(true)
            trimmed == "setDirectVend:off" || trimmed == "setDirectVend:false" -> setDirectVend(false)
            trimmed == "setForceSessionEnd:on" || trimmed == "setForceSessionEnd:true" -> setForceSessionEnd(true)
            trimmed == "setForceSessionEnd:off" || trimmed == "setForceSessionEnd:false" -> setForceSessionEnd(false)
            trimmed.startsWith("setAutoBeginCooldown:") -> {
                val n = trimmed.removePrefix("setAutoBeginCooldown:").trim().toIntOrNull()
                if (n == null) report("[remote] setAutoBeginCooldown needs a number of POLLs (0-50), e.g. setAutoBeginCooldown:3", true)
                else setAutoBeginCooldown(n)
            }
            trimmed == "getSettings" -> publishSettingsSnapshot()
            trimmed == "setMqttLogging:on" -> setMqttLogging(true)
            trimmed == "setMqttLogging:off" -> setMqttLogging(false)
            trimmed == "setPollVisibility:on" -> setPollVisibilityRemote(true)
            trimmed == "setPollVisibility:off" -> setPollVisibilityRemote(false)
            trimmed == "setPeripheralVisibility:on" -> setPeripheralVisibilityRemote(true)
            trimmed == "setPeripheralVisibility:off" -> setPeripheralVisibilityRemote(false)
            trimmed == "setEmptySessionVisibility:on" -> {
                setEmptySessionVisibility(true)
                report("[remote] empty (no-vend) session cycles will now be shown in the log", true)
            }
            trimmed == "setEmptySessionVisibility:off" -> {
                setEmptySessionVisibility(false)
                report("[remote] empty (no-vend) session cycles will now be hidden from the log", true)
            }
            trimmed == "setUnhandledVisibility:on" -> setUnhandledVisibilityRemote(true)
            trimmed == "setUnhandledVisibility:off" -> setUnhandledVisibilityRemote(false)
            trimmed == "setMdbLevel:1" -> setMdbLevelRemote(1)
            trimmed == "setMdbLevel:2" -> setMdbLevelRemote(2)
            trimmed == "setMdbLevel:3" -> setMdbLevelRemote(3)
            trimmed == "getConfig" -> publishConfigSnapshot()
            trimmed.startsWith("setConfig:") -> handleLegacySetConfig(trimmed.removePrefix("setConfig:"))
            trimmed.startsWith("resetConfig:") -> handleLegacyResetConfig(trimmed.removePrefix("resetConfig:"))
            trimmed.startsWith("simulateExchange:") -> {
                if (!simulateExchange(trimmed.removePrefix("simulateExchange:"))) {
                    report("[remote] simulateExchange: could not parse hex", true)
                }
            }
            trimmed == "initPulse:true" -> {
                if (PulseLib.initPulse(true)) report("[remote] pulse mode set to HIGH (idle LOW)", true)
            }
            trimmed == "initPulse:false" -> {
                if (PulseLib.initPulse(false)) report("[remote] pulse mode set to LOW (idle HIGH)", true)
            }
            trimmed.startsWith("sendPulse:") -> handleRemoteSendPulse(trimmed.removePrefix("sendPulse:"))
            trimmed == "setReadyFeedback:off" || trimmed == "setReadyFeedback:no" ||
                trimmed == "setReadyFeedback:false" -> PulseLib.setReadyFeedback(null)
            trimmed == "setReadyFeedback:on" || trimmed == "setReadyFeedback:yes" ||
                trimmed == "setReadyFeedback:true" -> PulseLib.enableReadyFeedback() // last persisted value/channel
            trimmed.startsWith("setReadyFeedback:") -> {
                // Accepted forms: "value[,channel]", "yes,value[,channel]" (the dashboard flag),
                // and no|false|off above for machines without ready feedback.
                var parts = trimmed.removePrefix("setReadyFeedback:").split(",").map { it.trim() }
                if (parts.getOrNull(0) == "yes" || parts.getOrNull(0) == "true") parts = parts.drop(1)
                val body = parts.joinToString(",")
                if (body.contains("=")) {
                    // 8.1.0 multi-channel form: setReadyFeedback:0=1,2=0  (digital_in(0) must be 1 AND digital_in(2) must be 0)
                    val map = PulseLib.parseReadyChannels(body)
                    if (map == null || map.isEmpty()) report("[remote] setReadyFeedback malformed, expected channel=value pairs like 0=1,2=0 (channels 0-7, values 0|1)", true)
                    else PulseLib.setReadyFeedbackChannels(map)
                } else {
                    val expected = parts.getOrNull(0)?.toIntOrNull()
                    val channel = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (expected == null) {
                        report("[remote] setReadyFeedback malformed, expected [yes,]value[,channel] | channel=value,... | no|off", true)
                    } else {
                        PulseLib.setReadyFeedback(expected, channel)
                    }
                }
            }
            trimmed == "checkMachineReady" -> PulseLib.isMachineReady() // reports its own details line
            trimmed == "rs232Open" -> Rs232Lib.open()
            trimmed.startsWith("rs232Open:") -> {
                // rs232Open:baud[,dataBits,stopBits,parity] e.g. rs232Open:9600 or rs232Open:9600,8,1,N
                val p = trimmed.removePrefix("rs232Open:").split(",").map { it.trim() }
                val baud = p.getOrNull(0)?.toIntOrNull()
                if (baud == null) {
                    report("[remote] rs232Open malformed, expected baud[,dataBits,stopBits,parity]", true)
                } else {
                    Rs232Lib.open(
                        baud = baud,
                        dataBits = p.getOrNull(1)?.toIntOrNull() ?: 8,
                        stopBits = p.getOrNull(2)?.toIntOrNull() ?: 1,
                        parity = p.getOrNull(3)?.firstOrNull() ?: 'N'
                    )
                }
            }
            trimmed == "rs232Close" -> Rs232Lib.close()
            trimmed.startsWith("rs232Send:") -> Rs232Lib.sendHex(trimmed.removePrefix("rs232Send:"))
            trimmed.startsWith("rs232SendFrame:") -> {
                // rs232SendFrame:HEADER;DATAHEX  e.g. rs232SendFrame:A1 02;53 55 43 43 45 53 53
                val p = trimmed.removePrefix("rs232SendFrame:").split(";")
                Rs232Lib.sendXorFrame(p[0], p.getOrElse(1) { "" })
            }
            trimmed.startsWith("rs232SendAscii:") -> {
                // rs232SendAscii:HEADER;TEXT  e.g. rs232SendAscii:A1 02;SUCCESS
                val p = trimmed.removePrefix("rs232SendAscii:").split(";", limit = 2)
                if (p.size < 2) report("[remote] rs232SendAscii malformed, expected HEADER;TEXT", true)
                else Rs232Lib.sendXorAscii(p[0], p[1])
            }
            trimmed.startsWith("rs232Simulate:") -> Rs232Lib.simulateFrame(trimmed.removePrefix("rs232Simulate:"))
            trimmed == "rs232Crc:on" -> Rs232Lib.setCrcCheck(true)
            trimmed == "rs232Crc:off" -> Rs232Lib.setCrcCheck(false)
            trimmed == "help" || trimmed == "commands" -> {
                report(
                    "[remote] commands: open | close | beginSession | vendApprove | " +
                        "cancelVend[:sessionCancel|:vendDenied] | setCancelMode:sessionCancel|vendDenied | " +
                        "setSessionMode:auto|manual | setAutoBeginCooldown:0-50 | setDirectVend:on|off | setForceSessionEnd:on|off | setMdbLevel:1|2|3 | setMqttLogging:on|off | " +
                        "setPollVisibility:on|off | setUnhandledVisibility:on|off | setPeripheralVisibility:on|off | " +
                        "setEmptySessionVisibility:on|off | getSettings | getConfig | setConfig:NAME:hex | " +
                        "resetConfig:NAME | simulateExchange:HEX | initPulse:true|false | sendPulse:w,p,c | " +
                        "setReadyFeedback:[yes,]value[,channel]|ch=val,ch=val|on|off | checkMachineReady | " +
                        "rs232Open[:baud[,dataBits,stopBits,parity]] | rs232Close | rs232Send:HEX | " +
                        "rs232SendFrame:HEADER;DATAHEX | rs232SendAscii:HEADER;TEXT | rs232Simulate:HEX | " +
                        "rs232Crc:on|off | rs232Gap:MS | getRs232Rules | clearRs232Rules | " +
                        "{\"setRs232Rules\":[...]} | {\"setConfig\":{...}} | help",
                    true
                )
            }
            trimmed.startsWith("rs232Gap:") -> {
                val ms = trimmed.removePrefix("rs232Gap:").trim().toIntOrNull()
                if (ms == null || ms < 1 || ms > 10_000) {
                    report("[remote] rs232Gap malformed, expected 1..10000 (ms)", true)
                } else {
                    Rs232Lib.frameGapMs = ms
                    report("[rs232] frame gap set to ${ms}ms", true)
                }
            }
            trimmed == "getRs232Rules" -> {
                report("[rs232] rules: ${Rs232Lib.rulesJson()}", true)
                dispatchControl("RS232_RULES_JSON", Rs232Lib.rulesJson())
            }
            trimmed == "clearRs232Rules" -> {
                Rs232Lib.clearRules()
                dispatchControl("RS232_RULES_JSON", Rs232Lib.rulesJson())
            }
            else -> return false
        }
        return true
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

    private fun setPeripheralVisibilityRemote(show: Boolean) {
        setPeripheralVisibility(show)
        report("[remote] other-peripheral traffic will now be ${if (show) "shown" else "hidden"} in the log", true)
    }

    private fun setMdbLevelRemote(level: Int) {
        if (setMdbLevel(level)) report("[remote] MDB level set to $level", true)
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

    /** "sendPulse:width,period,count" - the blocking sendPulse() runs on a detached thread so
     * the transport's reader thread is never stalled for the train's duration; the result
     * arrives as the usual "[pulse] sent ..." / "[pulse] FAILED ..." line. */
    private fun handleRemoteSendPulse(args: String) {
        val parts = args.split(",").map { it.trim() }
        val w = parts.getOrNull(0)?.toIntOrNull()
        val p = parts.getOrNull(1)?.toIntOrNull()
        val c = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 3 || w == null || p == null || c == null) {
            report("[remote] sendPulse malformed, expected width,period,count - got: $args", true)
            return
        }
        report("[remote] pulse train requested: width=${w}ms period=${p}ms count=$c", true)
        thread(name = "PulseRemote", isDaemon = true) { PulseLib.sendPulse(w, p, c) }
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

    /** Every line the engine produces goes to the optional local [logListener] always, and out
     * through [controlListener] as ("LOG", line) only while [setMqttLogging] is on. The other
     * control-plane tags do NOT pass through here, so muting logs never blinds the dashboard. */
    private fun report(line: String, showOnScreen: Boolean) {
        dispatchLog(line, showOnScreen)
        if (mqttLogsEnabled) dispatchControl("LOG", line)
    }

    /** Log entry point for sibling modules in this library (PulseLib) - same pipeline as the
     * engine's own lines: local listeners always, remote via the "LOG" control tag when
     * remote logging is enabled. */
    internal fun reportLine(line: String) = report(line, true)

    /** Fires one [VendListener] callback ON THE OFFLOAD THREAD - the bus loop only queues it, so
     * a slow or throwing listener can never delay a bus response. Exceptions are caught and
     * surfaced as a log line. */
    // Vend callbacks get their OWN threads (7.24.0). They used to ride the log/exchange offload
    // queue, so an app that blocked inside onVendRequest (waiting for its payment gateway) froze
    // every log line, every exchange event AND every later callback - including the
    // onVendFailure that would have told it the machine gave up. Seen in the field. Now a
    // blocked callback hurts only itself, the pipeline keeps flowing, and cancel/success/failure
    // still reach the app while its request handler is stuck. A watchdog reports the offender.
    private val vendCallbackExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "MdbVendCallback").apply { isDaemon = true }
    }
    private val vendWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MdbVendWatchdog").apply { isDaemon = true }
    }
    private const val VEND_CALLBACK_WARN_MS = 3000L

    private fun notifyVend(action: (VendListener) -> Unit) {
        val l = vendListener ?: return
        vendCallbackExecutor.execute {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val watch = vendWatchdog.schedule({
                if (!done.get()) report("[mdb] WARNING: a VendListener callback has been running for " +
                    "${VEND_CALLBACK_WARN_MS} ms - callbacks must return immediately; hop to your own " +
                    "thread/coroutine for gateway work", true)
            }, VEND_CALLBACK_WARN_MS, TimeUnit.MILLISECONDS)
            try {
                action(l)
            } catch (t: Throwable) {
                report("[mdb] vendListener threw: ${t.message}", true)
            } finally {
                done.set(true)
                watch.cancel(false)
            }
        }
    }

    /** Combines two raw MDB bytes into a 16-bit value, or -1 if either byte was absent. */
    private fun word(hi: Int, lo: Int): Int = if (hi < 0 || lo < 0) -1 else hi * 256 + lo

    /** Event-driven liveness: publishes a status snapshot ONLY when the recentActivity value
     * actually flips (bus traffic appeared, or went quiet past the 5 s window) - together with
     * the on-transition publish this replaces the old 3-second heartbeat, so nothing polls. */
    private fun maybePublishActivityEdge() {
        if (computeRecentActivity() != lastReportedActivity) publishVmcStatusNow()
    }

    private fun computeRecentActivity(): Boolean = running.get() &&
        lastMdbActivityAtMs != 0L &&
        (System.currentTimeMillis() - lastMdbActivityAtMs) < VMC_ACTIVITY_TIMEOUT_MS

    private fun publishVmcStatusNow() = publishVmcStatus(mdbState)

    /** The state most recently delivered to [stateListener] - so the plain state listener fires
     * on transitions only, never on heartbeats. */
    @Volatile private var lastListenerState: String? = null
    /** The real bus state last announced - with direct vend ON the reported name can stay ENABLED_STATE across
     *  INACTIVE -> DISABLED, and the app still gets the announcement again on every real transition (8.2.1). */
    @Volatile private var lastListenerBusState: MdbState? = null

    /** Builds and delivers one status snapshot for [state]. Runs on the offload thread for
     * bus-triggered transitions; the state is captured at queue time so a rapid second transition
     * can never make the first one report the wrong state. */
    private fun publishVmcStatus(state: MdbState) {
        lastReportedState = state
        val recentActivity = computeRecentActivity()
        lastReportedActivity = recentActivity
        // 8.1.0: with direct vend ON the machine may never send READER ENABLE, yet it is fully usable
        // (a VEND REQUEST is accepted in any state). Apps that gate their "ready" screen on the state
        // listener saying ENABLED_STATE would wait forever - so the REPORTED state is ENABLED_STATE
        // whenever the bus state is INACTIVE/DISABLED and direct vend is on. The real bus state still
        // travels in VMC_STATUS as "busState" for the dashboard/log readers that want it.
        val reported = reportedState(state)
        val json = statusJson(state, recentActivity)
        if (reported.name != lastListenerState || state != lastListenerBusState) {
            lastListenerState = reported.name
            lastListenerBusState = state
            dispatchState(reported.name)
        }
        statusListener?.invoke(json)
        dispatchControl("VMC_STATUS", json)
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
            if (showOtherPeripheral) queueExchange(cmd, "(ignored \u2014 other peripheral, not us)", showOnScreen = false)
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
            abortOpenVend(VendFailureReason.RESET)
            // 8.2.1: a RESET wipes every per-session flag - nothing armed before it may fire into the
            // new handshake (a pending END SESSION, SESSION BEGIN, cancel reply or approval).
            sessionBeginPending = false
            endSessionPending = false
            sessionCancelPending = false
            vendApprovePending = false
            vendRequestReceived = false
            autoBeginCooldownPolls = 0
            sent = "ACK"
        } else when (mdbState) {
            MdbState.INACTIVE_STATE -> {
                if (b0 == CMD_POLL && justResetCount < JUST_RESET_REPEAT_COUNT) {
                    MdbSlaveWrapper.jstReset()
                    justResetCount++
                    sent = "JUST RESET"
                } else if (b0 == CMD_POLL && endSessionPending) {
                    sent = strayEndSession()
                } else if (b0 == CMD_POLL) {
                    sent = idlePollAck()
                } else if (b0 == CMD_VEND && b1 in 0x01..0x04) {
                    sent = straySessionCommand(b1)
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x01 && directVendEnabled) {
                    // 8.2.1: with DIRECT VEND on, a READER ENABLE that arrives before setup is complete
                    // (or re-sent after a RESET) is ACKed - the machine keeps the reader enabled and can
                    // send its VEND REQUEST straight away. Without direct vend INACTIVE stays spec-strict:
                    // READER ENABLE is only meaningful after the last setup step (answered in DISABLED).
                    MdbSlaveWrapper.ack()
                    sent = "ACK (READER ENABLE in INACTIVE - direct vend)"
                } else if (b0 == CMD_READER && b1 == 0x02 && directVendEnabled) {
                    MdbSlaveWrapper.can()
                    sent = "CAN"
                } else if (b0 == CMD_VEND && b1 == 0x00 && directVendEnabled) {
                    sent = acceptDirectVendRequest(cmd, b2, b3)
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
                if (b0 == CMD_POLL && endSessionPending) {
                    sent = strayEndSession()
                } else if (b0 == CMD_POLL) {
                    sent = idlePollAck()
                } else if (b0 == CMD_VEND && b1 in 0x01..0x04) {
                    sent = straySessionCommand(b1)
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
                    sent = "PERIPHERAL ID"
                } else if (b0 == CMD_VEND && b1 == 0x00 && directVendEnabled) {
                    sent = acceptDirectVendRequest(cmd, b2, b3)
                } else if (b0 == CMD_EXPANSION && b1 == 0x04 && mdbLevel >= 3) {
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                }
            }

            MdbState.ENABLED_STATE -> {
                if (b0 == CMD_POLL && endSessionPending) {
                    sent = strayEndSession()
                } else if (b0 == CMD_VEND && b1 in 0x01..0x04) {
                    sent = straySessionCommand(b1)
                } else if (b0 == CMD_POLL) {
                    if (autoBeginCooldownPolls > 0 && --autoBeginCooldownPolls == 0) sessionBeginPending = true
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
                    autoBeginCooldownPolls = 0
                    mdbState = MdbState.DISABLED_STATE
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    autoBeginCooldownPolls = 0
                    if (sessionBeginMode == SessionBeginMode.AUTO) sessionBeginPending = true
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    MdbSlaveWrapper.can()
                    sent = "CAN"
                } else if (b0 == CMD_VEND && b1 == 0x00 && (alwaysIdleDeclared() || directVendEnabled)) {
                    // "Always Idle" (spec 7.2.5): a VEND REQUEST may arrive directly here with no
                    // prior BEGIN SESSION - once bit 5 of the declared Level 3 feature bytes is set,
                    // or (7.28.0) whenever the operator turned direct vend on for a VMC that skips
                    // the handshake. Otherwise a VEND REQUEST needs SESSION BEGIN first.
                    sent = acceptDirectVendRequest(cmd, b2, b3)
                }
            }

            MdbState.VEND_STATE -> {
                if (b0 == CMD_VEND && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    priceHigh = b2
                    priceLow = b3
                    vendRequestReceived = true
                    vendOpen = true
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
                    vendOpen = false
                    notifyVend { it.onVendSuccess(word(b2, b3)) }
                    sent = if (armForcedSessionEnd("VEND SUCCESS")) "ACK (VEND SUCCESS received) - forcing session end" else "ACK (VEND SUCCESS received)"
                } else if (b0 == CMD_VEND && b1 == 0x03) {
                    // VEND FAILURE - ACK immediately (refund handshake); the idle-POLL default
                    // ACK covers the "refund done" phase. The app refunds/voids via the listener.
                    MdbSlaveWrapper.ack()
                    abortOpenVend(VendFailureReason.FAILED)
                    sent = "ACK (VEND FAILURE received)"
                } else if (b0 == CMD_VEND && b1 == 0x04) {
                    MdbSlaveWrapper.ack()
                    endSessionPending = true
                    sent = "ACK"
                } else if (b0 == CMD_VEND && b1 == 0x01) {
                    // The VMC's own VEND CANCEL - answered per the configured mode, no
                    // substitution. The typed callback fires so the app can abort any
                    // in-flight payment UI (gateway popup, card read) and void its hold.
                    MdbSlaveWrapper.ack()
                    pendingCancelResponse = vmcCancelResponseMode
                    sessionCancelPending = true
                    vendApprovePending = false
                    abortOpenVend(VendFailureReason.CANCELLED_BY_VMC)
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    // 7.25.0: READER ENABLE while a session is open. Previously unhandled here, so
                    // the VMC never got its ACK and retried "14 01" forever (D-0222). Just ACK.
                    MdbSlaveWrapper.ack()
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    // READER DISABLE mid-session: ACK and close the session cleanly on the next
                    // POLL (END SESSION); a later READER ENABLE re-arms as usual.
                    MdbSlaveWrapper.ack()
                    vendApprovePending = false
                    endSessionPending = true
                    sent = "ACK"
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    // READER CANCEL mid-session: same reply as outside a session, plus the
                    // session is cancelled per the configured mode and the app is told.
                    MdbSlaveWrapper.can()
                    pendingCancelResponse = vmcCancelResponseMode
                    sessionCancelPending = true
                    vendApprovePending = false
                    abortOpenVend(VendFailureReason.CANCELLED_BY_VMC)
                    sent = "CAN"
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
                        // 8.2.0: nothing from this session may leak into the next one - a forced cancel that
                        // was still armed, or an approve that arrived after the machine cancelled.
                        sessionCancelPending = false
                        vendApprovePending = false
                        vendRequestReceived = false
                        // 7.25.0: do NOT arm SESSION BEGIN on the very next POLL - give the VMC a
                        // few polls to send its READER ENABLE first (see autoBeginCooldownPolls).
                        if (sessionBeginMode == SessionBeginMode.AUTO) {
                            val cooldown = autoBeginCooldownSetting
                            if (cooldown <= 0) sessionBeginPending = true else autoBeginCooldownPolls = cooldown
                        }
                        // 8.0.0: a vend still open here means the machine closed the session without
                        // VEND SUCCESS / FAILURE / CANCEL - the app is told via onVendFailure.
                        abortOpenVend(VendFailureReason.SESSION_ENDED)
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
            "PERIPHERAL ID"
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
            "PERIPHERAL ID"
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
     * TEST HOOK: pushes one FAKE received frame through the exact same classify -> template ->
     * listener pipeline a real bus frame takes, so listener wiring can be verified with no
     * vending machine and no open port. Nothing is sent on the bus and the protocol state
     * machine is untouched (only the pipeline side effects follow, exactly as for a real
     * frame: lastReceivedHex captures it, and a simulated BEGIN/END SESSION arms/clears the
     * session id).
     *
     *     HardwareLib.simulateExchange("10 10")                     // -> code 111, RESET rx=10 10 tx=ACK
     *     HardwareLib.simulateExchange("13 00 01 F4 00 03")         // -> code 121, VEND REQUEST price=500
     *     HardwareLib.simulateExchange("12 12", "SESSION BEGIN")    // -> code 120 (POLL replies need txName)
     *
     * Dashboard equivalent: the command "simulateExchange:10 10". Listeners run SYNCHRONOUSLY
     * on the calling thread here (a real frame arrives on the offload thread), so it works even
     * when the engine is not started. Returns false for hex it cannot parse.
     */
    @JvmStatic
    @JvmOverloads
    fun simulateExchange(rxHex: String, txName: String = "ACK"): Boolean {
        val parts = rxHex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val bytes = ByteArray(parts.size)
        for (i in parts.indices) {
            val v = parts[i].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return false
            if (v !in 0..0xFF) return false
            bytes[i] = v.toByte()
        }
        report("[test] simulateExchange rx=${bytes.joinToString(" ") { String.format("%02X", it) }} tx=$txName", true)
        showExchange(bytes, txName)
        return true
    }

    /**
     * One unified event per exchange: what the VMC sent us (rx) and what we replied (tx) travel
     * together in the SAME event - never split across two lines. The local [logListener] gets
     * the decoded sentence; [exchangeListener] gets the full structured [MdbExchangeEvent]
     * (CMD-coded, with the codebook-ready params) for whatever transport the app wired up.
     */
    private fun showExchange(cmd: ByteArray, sent: String, showOnScreen: Boolean = true) {
        val (mdbCmd, params) = classifyExchange(cmd, sent)
        // A fresh session id is armed BEFORE the SESSION BEGIN event ships and cleared only
        // AFTER the END SESSION event ships, so both endpoints carry the id they belong to.
        // Ordering holds because every exchange rides this one offload queue in order.
        if (mdbCmd == MdbCmd.POLL_BEGIN_SESSION) {
            sessionId = java.util.UUID.randomUUID().toString().substring(0, 8)
        }
        // The RAW frame is captured from the bytes themselves - the {0} param may carry the
        // human command name instead (see classifyExchange), but rxHex/lastReceivedHex always
        // hold the true wire bytes.
        val rxHex = cmd.joinToString(" ") { String.format("%02X", it) }
        lastRxByCode[mdbCmd.code] = rxHex
        var message = mdbCmd.template
        for (i in params.indices) message = message.replace("{$i}", params[i])
        // 7.26.0: the log line is dispatched ONLY by deliver() below (once, in order with the
        // structured event). Dispatching it here as well printed every exchange twice - and made
        // a held BEGIN SESSION show up immediately AND again when its VEND REQUEST flushed it.
        val event = MdbExchangeEvent(
            cmd = mdbCmd,
            rxHex = rxHex,
            txName = params[1],
            params = params,
            message = message,
            sessionId = sessionId,
            publishRemote = mqttLogsEnabled,
            showOnScreen = showOnScreen,
            timestampMs = System.currentTimeMillis()
        )
        deliverOrDefer(mdbCmd, event)
        if (mdbCmd == MdbCmd.POLL_END_SESSION) sessionId = null
    }

    // --- empty-session suppression (offload-thread state, ordered by the offload queue) ---
    // AUTO session mode makes the machine cycle BEGIN SESSION -> SESSION COMPLETE -> END SESSION
    // endlessly with no customer, flooding every log sink. Unless [showEmptySessions] is on,
    // the BEGIN SESSION event is HELD BACK and delivered only when a VEND REQUEST arrives in
    // that session - so a real transaction still logs its complete chain in order, and an empty
    // cycle logs nothing at all. dispatchLog for the held event happens at flush time.
    private var sessionHadVend = false
    private var heldSessionBegin: MdbExchangeEvent? = null

    /** True while deliver() is handing an exchange's plain text to the log listeners - attach's remote
     *  log forwarder uses it to keep that text local when the same exchange goes out as a coded item. */
    @Volatile private var deliveringExchange = false

    private fun deliver(e: MdbExchangeEvent) {
        deliveringExchange = true
        try { dispatchLog(e.message, e.showOnScreen) } finally { deliveringExchange = false }
        dispatchExchange(e)
    }

    private fun deliverOrDefer(cmd: MdbCmd, e: MdbExchangeEvent) {
        if (showEmptySessions) {
            deliver(e)
            return
        }
        when (cmd) {
            MdbCmd.POLL_BEGIN_SESSION -> {
                sessionHadVend = false
                heldSessionBegin = e
            }
            MdbCmd.VEND_REQUEST_ACK -> {
                heldSessionBegin?.let { deliver(it) }
                heldSessionBegin = null
                sessionHadVend = true
                deliver(e)
            }
            MdbCmd.SESSION_COMPLETE_ACK -> if (sessionHadVend) deliver(e)
            MdbCmd.POLL_END_SESSION -> {
                if (sessionHadVend) deliver(e)
                heldSessionBegin = null
                sessionHadVend = false
            }
            else -> deliver(e)
        }
    }

    /** Maps one (received frame, reply name) pair onto its CMD schema entry + params:
     * p[0] is the HUMAN NAME of the received command ("POLL", "RESET", "SETUP CONFIG", ...) -
     * never raw bytes like "12 12" - except for unknown frames (UNHANDLED, other peripheral,
     * unrecognized EXPANSION), where the hex IS the information. p[1] is always the tx reply
     * name. The raw frame is always available via [MdbExchangeEvent.rxHex]/[lastReceivedHex]. */
    private fun classifyExchange(cmd: ByteArray, sent: String): Pair<MdbCmd, List<String>> {
        val rx = cmd.joinToString(" ") { String.format("%02X", it) }
        val b0 = cmd.byteAt(0)
        val b1 = cmd.byteAt(1)
        val mdbCmd = when {
            b0 !in 0x10..0x17 -> MdbCmd.OTHER_PERIPHERAL
            sent == "UNHANDLED" -> MdbCmd.UNHANDLED
            b0 == CMD_RESET -> MdbCmd.RESET_ACK
            b0 == CMD_SETUP && b1 == 0x00 -> MdbCmd.SETUP_READER_CONFIG
            b0 == CMD_SETUP && b1 == 0x01 -> MdbCmd.SETUP_PRICES_ACK
            b0 == CMD_POLL -> when (sent) {
                "JUST RESET" -> MdbCmd.POLL_JUST_RESET
                "SESSION BEGIN" -> MdbCmd.POLL_BEGIN_SESSION
                "END SESSION" -> MdbCmd.POLL_END_SESSION
                "VEND APPROVED" -> MdbCmd.POLL_VEND_APPROVED
                "SESSION CANCEL REQUEST" -> MdbCmd.POLL_SESSION_CANCEL
                "VEND DENIED" -> MdbCmd.POLL_VEND_DENIED
                else -> MdbCmd.POLL_ACK
            }
            b0 == CMD_VEND && b1 == 0x00 -> MdbCmd.VEND_REQUEST_ACK
            b0 == CMD_VEND && b1 == 0x01 -> MdbCmd.VEND_CANCEL_ACK
            b0 == CMD_VEND && b1 == 0x02 -> MdbCmd.VEND_SUCCESS_ACK
            b0 == CMD_VEND && b1 == 0x03 -> MdbCmd.VEND_FAILURE_ACK
            b0 == CMD_VEND && b1 == 0x04 -> MdbCmd.SESSION_COMPLETE_ACK
            b0 == CMD_VEND && b1 == 0x05 -> MdbCmd.CASH_SALE_ACK
            b0 == CMD_READER && b1 == 0x00 -> MdbCmd.READER_DISABLE_ACK
            b0 == CMD_READER && b1 == 0x01 -> MdbCmd.READER_ENABLE_ACK
            b0 == CMD_READER && b1 == 0x02 -> MdbCmd.READER_CANCEL_CANCELLED
            b0 == CMD_REVALUE && b1 == 0x00 -> MdbCmd.REVALUE_REQUEST_DENIED
            b0 == CMD_REVALUE && b1 == 0x01 -> MdbCmd.REVALUE_LIMIT_REPLY
            b0 == CMD_EXPANSION && b1 == 0x00 -> MdbCmd.EXPANSION_PERIPHERAL_ID
            b0 == CMD_EXPANSION && b1 == 0x04 -> MdbCmd.EXPANSION_OPTIONS_ACK
            b0 == CMD_EXPANSION -> MdbCmd.EXPANSION_OTHER
            else -> MdbCmd.GENERIC
        }
        // The {0} slot: command name for everything (POLL, RESET, READER ENABLE, ...) except
        // the entries flagged rxShowsHex in the schema itself - frames whose payload bytes ARE
        // the information (SETUP, EXPANSION, REVALUE, unknown). Single source of truth: MdbCmd.
        val rxShown = if (mdbCmd.rxShowsHex) rx else mdbCmd.received.substringBefore(" (")
        val base = listOf(rxShown, sent)
        val extras = when (mdbCmd) {
            MdbCmd.SETUP_READER_CONFIG -> listOf(mdbLevel.toString())
            MdbCmd.VEND_REQUEST_ACK, MdbCmd.CASH_SALE_ACK -> listOf(
                priceToMinorUnits(word(cmd.byteAt(2), cmd.byteAt(3))).toString(),
                word(cmd.byteAt(4), cmd.byteAt(5)).toString()
            )
            MdbCmd.VEND_SUCCESS_ACK -> listOf(word(cmd.byteAt(2), cmd.byteAt(3)).toString())
            else -> emptyList()
        }
        return mdbCmd to base + extras
    }

    // ------------------------------------ CMD-code hex access ------------------------------------

    /**
     * Which editable payload answers CMD [code], resolved against the CURRENT MDB level (the
     * same choice the engine makes when it sends). Null for codes whose reply is a fixed
     * single-byte ACK or that have no reply at all.
     */
    @JvmStatic
    fun replyConfigName(code: Int): String? = when (code) {
        MdbCmd.POLL_JUST_RESET.code -> ConfigName.JUST_RESET
        MdbCmd.SETUP_READER_CONFIG.code -> ConfigName.READER_CONFIG_DATA
        MdbCmd.EXPANSION_PERIPHERAL_ID.code ->
            if (mdbLevel >= 3) ConfigName.READER_CONFIG_INFO_L3 else ConfigName.READER_CONFIG_INFO
        MdbCmd.READER_CANCEL_CANCELLED.code -> ConfigName.CAN
        MdbCmd.POLL_BEGIN_SESSION.code ->
            if (mdbLevel >= 2) ConfigName.SESSION_BEGIN_L2 else ConfigName.SESSION_BEGIN
        MdbCmd.POLL_VEND_APPROVED.code -> ConfigName.VEND_APPROVED
        MdbCmd.POLL_VEND_DENIED.code -> ConfigName.VEND_DENIED
        MdbCmd.POLL_END_SESSION.code -> ConfigName.END_SESSION
        MdbCmd.POLL_SESSION_CANCEL.code -> ConfigName.SESSION_CANCEL
        MdbCmd.REVALUE_REQUEST_DENIED.code -> ConfigName.REVALUE_DENIED
        MdbCmd.REVALUE_LIMIT_REPLY.code -> ConfigName.REVALUE_LIMIT
        else -> null
    }

    /** The hex this device currently answers CMD [code] with, or null when the reply is a fixed
     * ACK / not editable. Example: getReplyHex(4) -> the PERIPHERAL ID payload. */
    @JvmStatic
    fun getReplyHex(code: Int): String? = replyConfigName(code)?.let { getConfigHex(it) }

    /**
     * Edits the reply for CMD [code] by number - setReplyHex(3, "01 ...") updates READER CONFIG
     * DATA, setReplyHex(4, ...) the PERIPHERAL ID for the current level, and so on. Same
     * validation, persistence, and ack/snapshot publishing as [setConfigHex]. Returns null on
     * success, an error sentence otherwise ("CMD 7 has a fixed reply (ACK) - nothing to edit").
     */
    @JvmStatic
    fun setReplyHex(code: Int, hex: String): String? {
        val name = replyConfigName(code)
            ?: return "CMD $code has a fixed reply - nothing to edit"
        return setConfigHex(name, hex)
    }

    /**
     * The wire bytes CMD [code] is CURRENTLY answered with, checksum included:
     *  - "00" for every fixed bare-ACK reply (the single handshake byte, no CHK);
     *  - the live config bytes + computed mod-256 CHK for editable replies (note: the level
     *    byte of READER CONFIG DATA and the price bytes of VEND APPROVED are overwritten at
     *    send time);
     *  - null for codes that send nothing (110/134/135/136).
     * Always reflects the current config store, so it never drifts from what really goes out.
     */
    @JvmStatic
    fun replyWireHex(code: Int): String? {
        val cmd = MdbCmd.fromCode(code) ?: return null
        if (cmd.reply.startsWith("(") || cmd.reply == "any") return null
        val name = replyConfigName(code) ?: return "00"
        val data = MdbConfigStore.get(name)
        var sum = 0
        for (b in data) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        return MdbConfigStore.toHex(data) + " " + String.format("%02X", sum)
    }

    /** The last frame the VMC sent for CMD [code], as hex - "what we received, saved". Null
     * until that exchange happens once. Example: lastReceivedHex(4) -> the VMC's EXPANSION
     * REQUEST ID payload, exactly as it arrived. */
    @JvmStatic
    fun lastReceivedHex(code: Int): String? = lastRxByCode[code]

    /** Every captured last-received frame as JSON: {"4": "17 00 ...", "5": "11 01 ..."}. */
    @JvmStatic
    fun lastReceivedJson(): String = org.json.JSONObject().apply {
        for ((code, hex) in lastRxByCode) put(code.toString(), hex)
    }.toString()
}

/** The library's previous name - existing integrations written against MdbLib keep compiling.
 * New code should use [HardwareLib]. */
@Deprecated("Renamed - use HardwareLib", ReplaceWith("HardwareLib"))
typealias MdbLib = HardwareLib
