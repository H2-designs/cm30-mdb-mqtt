package com.rabbah.mdb

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted device-behavior settings for the MDB engine: session-begin mode (auto/manual), the
 * VMC-initiated vend-cancel response mode, the MDB feature level, and the two log-visibility
 * flags. Backed by SharedPreferences so every value survives app restarts. Values are the same
 * string tokens used on the wire by the "setSessionMode:..." / "setCancelMode:..." remote
 * commands, so nothing needs translating between storage and protocol.
 *
 * Internal to mdb-lib: the app changes settings through [MdbLib]'s typed setters, never here.
 */
internal object MdbSettings {

    private const val PREFS_NAME = "mdb_settings"
    private const val KEY_SESSION_BEGIN_MODE = "sessionBeginMode"
    private const val KEY_VMC_CANCEL_MODE = "vmcCancelMode"
    private const val KEY_SHOW_POLL_TRAFFIC = "showPollTraffic"
    private const val KEY_SHOW_UNHANDLED_CMD = "showUnhandledCmd"
    private const val KEY_MDB_LEVEL = "mdbLevel"
    private const val KEY_MQTT_LOGS_ENABLED = "mqttLogsEnabled"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** "auto" or "manual". Defaults to "auto" \u2014 matches the original always-auto behavior. */
    var sessionBeginMode: String
        get() = prefs.getString(KEY_SESSION_BEGIN_MODE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_SESSION_BEGIN_MODE, value).apply()

    /** "sessionCancel" or "vendDenied". Defaults to "sessionCancel". */
    var vmcCancelMode: String
        get() = prefs.getString(KEY_VMC_CANCEL_MODE, "sessionCancel") ?: "sessionCancel"
        set(value) = prefs.edit().putString(KEY_VMC_CANCEL_MODE, value).apply()

    /** Whether idle POLL -> ACK exchanges show up in the log. Off by default \u2014 too noisy for
     * normal use, but sometimes exactly what you need for debugging a VMC's polling behavior. */
    var showPollTraffic: Boolean
        get() = prefs.getBoolean(KEY_SHOW_POLL_TRAFFIC, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_POLL_TRAFFIC, value).apply()

    /** Whether a command that matches no branch in the current state gets logged as "UNHANDLED"
     * instead of silently dropped. Off by default, same reasoning as [showPollTraffic]. */
    var showUnhandledCmd: Boolean
        get() = prefs.getBoolean(KEY_SHOW_UNHANDLED_CMD, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_UNHANDLED_CMD, value).apply()

    /** The MDB feature level (1, 2, or 3). Defaults to 2 \u2014 preserves the behavior devices in the
     * field already had before the level became configurable. */
    var mdbLevel: Int
        get() = prefs.getInt(KEY_MDB_LEVEL, 2)
        set(value) = prefs.edit().putInt(KEY_MDB_LEVEL, value).apply()

    /** Whether the engine's LOG LINES are published over MQTT. On by default. Control-plane
     * messages (VMC_STATUS, SETTINGS_JSON, CONFIG_JSON) always publish regardless, so the
     * dashboard stays able to see state and control the device even with logs muted. */
    var mqttLogsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_LOGS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_LOGS_ENABLED, value).apply()
}
