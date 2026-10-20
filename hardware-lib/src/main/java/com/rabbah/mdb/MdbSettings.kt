package com.rabbah.mdb

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted device-behavior settings for the MDB engine: session-begin mode (auto/manual), the
 * VMC-initiated vend-cancel response mode, the MDB feature level, and the log-visibility flags.
 * Backed by SharedPreferences so every value survives app restarts. Values are the same string
 * tokens used on the wire by the "setSessionMode:..." / "setCancelMode:..." remote commands, so
 * nothing needs translating between storage and protocol.
 *
 * SAFE WITHOUT init() (7.23.0): a host app that never calls HardwareLib.init(context) (seen in
 * the field - an RS232-only flow) used to make every settings command throw on the lateinit
 * prefs, which the command chain swallowed as "unknown command", and the persisted log-mute was
 * never loaded. Now every getter/setter works from an in-memory table until init() provides the
 * store; [isPersistent] tells the engine to warn that values will reset on restart.
 *
 * Internal to hardware-lib: the app changes settings through [HardwareLib]'s typed setters.
 */
internal object MdbSettings {

    private const val PREFS_NAME = "mdb_settings"
    private const val KEY_SESSION_BEGIN_MODE = "sessionBeginMode"
    private const val KEY_VMC_CANCEL_MODE = "vmcCancelMode"
    private const val KEY_SHOW_POLL_TRAFFIC = "showPollTraffic"
    private const val KEY_SHOW_UNHANDLED_CMD = "showUnhandledCmd"
    private const val KEY_SHOW_OTHER_PERIPHERAL = "showOtherPeripheral"
    private const val KEY_SHOW_EMPTY_SESSIONS = "showEmptySessions"
    private const val KEY_MDB_LEVEL = "mdbLevel"
    private const val KEY_MQTT_LOGS_ENABLED = "mqttLogsEnabled"
    private const val KEY_AUTO_BEGIN_COOLDOWN = "autoBeginCooldownPolls"
    private const val KEY_READY_FB_ENABLED = "readyFeedbackEnabled"
    private const val KEY_READY_FB_VALUE = "readyFeedbackValue"
    private const val KEY_READY_FB_CHANNEL = "readyFeedbackChannel"
    private const val KEY_READY_FB_CHANNELS = "readyFeedbackChannels"
    private const val KEY_PULSE_MODE = "pulseMode"
    private const val KEY_DIRECT_VEND = "directVend"
    private const val KEY_FORCE_SESSION_END = "forceSessionEndAfterVend"

    /** Bounds for [autoBeginCooldownPolls]: 0 = arm on the very next POLL (pre-7.25.0 behavior). */
    const val AUTO_BEGIN_COOLDOWN_MIN = 0
    const val AUTO_BEGIN_COOLDOWN_MAX = 50
    const val AUTO_BEGIN_COOLDOWN_DEFAULT = 3

    @Volatile private var prefs: SharedPreferences? = null
    /** Values set before init() (or forever, if init() never comes) live here. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** True once init() gave us a SharedPreferences store - i.e. values survive restarts. */
    val isPersistent: Boolean get() = prefs != null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Anything the app set before init() wins over what the store holds - it was the most
        // recent instruction - and is now persisted too.
        val e = p.edit()
        for ((k, v) in memory) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
        }
        e.apply()
        prefs = p
    }

    private fun str(key: String, def: String): String =
        prefs?.getString(key, def) ?: (memory[key] as? String) ?: def
    private fun bool(key: String, def: Boolean): Boolean =
        prefs?.getBoolean(key, def) ?: (memory[key] as? Boolean) ?: def
    private fun int(key: String, def: Int): Int =
        prefs?.getInt(key, def) ?: (memory[key] as? Int) ?: def
    private fun put(key: String, value: Any) {
        memory[key] = value
        val p = prefs ?: return
        when (value) {
            is String -> p.edit().putString(key, value).apply()
            is Boolean -> p.edit().putBoolean(key, value).apply()
            is Int -> p.edit().putInt(key, value).apply()
        }
    }

    /** "auto" or "manual". Defaults to "auto" - matches the original always-auto behavior. */
    var sessionBeginMode: String
        get() = str(KEY_SESSION_BEGIN_MODE, "auto")
        set(value) = put(KEY_SESSION_BEGIN_MODE, value)

    /** Idle POLLs auto mode waits after END SESSION before re-arming SESSION BEGIN (7.25.0).
     * Gives the VMC room to send its post-session READER ENABLE first. Default 3 (~300-600 ms);
     * 0 restores the old "next POLL" behavior. Same value the "setAutoBeginCooldown:N" command sets. */
    var autoBeginCooldownPolls: Int
        get() = int(KEY_AUTO_BEGIN_COOLDOWN, AUTO_BEGIN_COOLDOWN_DEFAULT).coerceIn(AUTO_BEGIN_COOLDOWN_MIN, AUTO_BEGIN_COOLDOWN_MAX)
        set(value) = put(KEY_AUTO_BEGIN_COOLDOWN, value.coerceIn(AUTO_BEGIN_COOLDOWN_MIN, AUTO_BEGIN_COOLDOWN_MAX))

    /** "sessionCancel" or "vendDenied". Defaults to "sessionCancel". */
    var vmcCancelMode: String
        get() = str(KEY_VMC_CANCEL_MODE, "sessionCancel")
        set(value) = put(KEY_VMC_CANCEL_MODE, value)

    /** Whether idle POLL -> ACK exchanges show up in the log. Off by default - too noisy for
     * normal use, but sometimes exactly what you need for debugging a VMC's polling behavior. */
    var showPollTraffic: Boolean
        get() = bool(KEY_SHOW_POLL_TRAFFIC, false)
        set(value) = put(KEY_SHOW_POLL_TRAFFIC, value)

    /** Whether a command that matches no branch in the current state gets logged as "UNHANDLED"
     * instead of silently dropped. Off by default, same reasoning as [showPollTraffic]. */
    var showUnhandledCmd: Boolean
        get() = bool(KEY_SHOW_UNHANDLED_CMD, false)
        set(value) = put(KEY_SHOW_UNHANDLED_CMD, value)

    /** Whether traffic addressed to OTHER peripherals on the bus (coin changer, bill validator,
     * anything outside our 0x10-0x17 range) is logged. Off by default: a real machine polls its
     * other peripherals constantly, and those lines drown the cashless conversation completely.
     * Deliberately separate from [showUnhandledCmd] so "show me unhandled" does not also mean
     * "show me every byte on the bus". */
    var showOtherPeripheral: Boolean
        get() = bool(KEY_SHOW_OTHER_PERIPHERAL, false)
        set(value) = put(KEY_SHOW_OTHER_PERIPHERAL, value)

    /** Whether session cycles WITHOUT a vend request (auto-session churn: BEGIN SESSION ->
     * SESSION COMPLETE -> END SESSION with no customer) are logged. Off by default: in auto
     * mode a machine produces one of these every re-arm and they bury the real transactions.
     * A session that receives a VEND REQUEST is always logged in full, including its BEGIN. */
    var showEmptySessions: Boolean
        get() = bool(KEY_SHOW_EMPTY_SESSIONS, false)
        set(value) = put(KEY_SHOW_EMPTY_SESSIONS, value)

    /** The MDB feature level (1, 2, or 3). Defaults to 2 - preserves the behavior devices in the
     * field already had before the level became configurable. */
    var mdbLevel: Int
        get() = int(KEY_MDB_LEVEL, 2)
        set(value) = put(KEY_MDB_LEVEL, value)

    /** Whether the engine's LOG LINES are published over MQTT. OFF BY DEFAULT since 7.23.0
     * (fleet decision: a device never streams remote logs unless an operator turned them on -
     * the dashboard's Logs toggle / "setMqttLogging:on", persisted per device). Control-plane
     * messages (VMC_STATUS, SETTINGS_JSON, CONFIG_JSON, command replies as snapshots) always
     * publish regardless, so the dashboard can see state and control the device while muted. */
    var mqttLogsEnabled: Boolean
        get() = bool(KEY_MQTT_LOGS_ENABLED, false)
        set(value) = put(KEY_MQTT_LOGS_ENABLED, value)

    /** Pulse ready-feedback gate (7.26.0, persisted). enabled=false: pulses always allowed. enabled=true:
     * digital_in(channel) must equal value before every train. value/channel are kept while disabled so
     * a plain "setReadyFeedback:on" re-arms with the last known pair. */
    var readyFeedbackEnabled: Boolean
        get() = bool(KEY_READY_FB_ENABLED, false)
        set(value) = put(KEY_READY_FB_ENABLED, value)
    var readyFeedbackValue: Int
        get() = int(KEY_READY_FB_VALUE, 1)
        set(value) = put(KEY_READY_FB_VALUE, value)
    var readyFeedbackChannel: Int
        get() = int(KEY_READY_FB_CHANNEL, 0)
        set(value) = put(KEY_READY_FB_CHANNEL, value)
    /** 8.1.0: the multi-channel gate as "channel=value,channel=value" (e.g. "0=1,2=0"). Empty = use the
     *  single readyFeedbackValue/readyFeedbackChannel pair above (older devices / older dashboards). */
    var readyFeedbackChannels: String
        get() = str(KEY_READY_FB_CHANNELS, "")
        set(value) = put(KEY_READY_FB_CHANNELS, value)

    /** Pulse polarity (7.27.0, persisted): "high" (HIGH pulses, idle LOW), "low" (LOW pulses, idle HIGH)
     * or "unset" (never initialised - sendPulse is refused). Written by a SUCCESSFUL PulseLib.initPulse
     * only; re-applied by HardwareLib.init(). */
    var pulseMode: String
        get() = str(KEY_PULSE_MODE, "unset")
        set(value) = put(KEY_PULSE_MODE, value)

    /** Direct vend (7.28.0, persisted, default OFF): accept a VEND REQUEST in ANY state (INACTIVE, DISABLED,
     * ENABLED) - for VMCs that skip the setup / READER ENABLE / SESSION BEGIN handshake and send 13 00
     * straight away. Same as the "setDirectVend:on|off" remote command. */
    var directVend: Boolean
        get() = bool(KEY_DIRECT_VEND, false)
        set(value) = put(KEY_DIRECT_VEND, value)

    /** 8.2.0: after VEND SUCCESS the reader sends SESSION CANCEL REQUEST on the next POLL, so a
     *  VMC that never sends SESSION COMPLETE still closes the session. Same as "setForceSessionEnd:on|off". */
    var forceSessionEndAfterVend: Boolean
        get() = bool(KEY_FORCE_SESSION_END, false)
        set(value) = put(KEY_FORCE_SESSION_END, value)
}
