package com.ciontek.HardwareDemo

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.rabbah.mdb.MdbLib
import com.rabbah.mqtt.MqttConfig
import com.rabbah.mqtt.MqttLib

/**
 * Demo / reference integration of the two libraries. This Activity is deliberately thin - it is
 * exactly what any Android developer consuming the AARs would write:
 *
 *   1. MqttLib.init(...) + start()          - the transport with its outbound queue
 *   2. MdbLib.init(context) + start()       - the MDB slave; from here all MDB data flows to the
 *                                             dashboard on its own, remote commands included
 *   3. (optional) MdbLib.logListener        - mirror the engine's lines into this app's own UI
 *   4. (optional) MqttLib.enqueue(...)      - the app's own log lines share the same queue
 *
 * No protocol logic, no MQTT packet code, and no config handling lives here anymore - all of
 * that is inside the libraries.
 */
class MdbSlaveActivity : Activity(), View.OnClickListener {

    private lateinit var scrollView: ScrollView
    private lateinit var transContent: TextView
    private val log = StringBuilder()

    /** Scopes every MQTT topic this device uses so multiple machines can share the broker.
     * ANDROID_ID is per-device, needs no runtime permission, and survives app reinstalls.
     * Truncated to 6 hex chars so it's readable off the on-screen version label. */
    private val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        (androidId ?: "unknown").takeLast(6).uppercase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mdb_slave)

        scrollView = findViewById(R.id.Recscroll)
        transContent = findViewById(R.id.transmossion_content)
        findViewById<Button>(R.id.open).setOnClickListener(this)
        findViewById<Button>(R.id.close).setOnClickListener(this)
        findViewById<Button>(R.id.clear).setOnClickListener(this)
        findViewById<Button>(R.id.vendApproved).setOnClickListener(this)
        findViewById<TextView>(R.id.versionLabel).text = buildVersionLabel()

        // 1. Transport up first - MdbLib.init() registers its command listener on it.
        MqttLib.init(MqttConfig(topicPrefix = "cm30-mdb/hamdan-rabbah", deviceId = deviceId))
        MqttLib.start()

        // 2. The MDB slave. After these two lines every exchange, heartbeat, settings snapshot,
        //    and config ack reaches the dashboard through the queue with no further glue.
        MdbLib.init(applicationContext)

        // 3. Local mirror: same lines on this screen and on the USB/adb LogServer. The library
        //    already enqueued them to MQTT - this listener must NOT enqueue again.
        MdbLib.logListener = { line, showOnScreen -> showLocally(line, showOnScreen) }

        LogServer.start()
        announce("-- live log + remote control over MQTT (broker.hivemq.com), device ID: $deviceId --")

        // Auto-start the MDB port on launch - this app runs unattended on the vending machine.
        MdbLib.start()
    }

    /** e.g. "v2.0.0 (build 31) - DEVICE - ID:A1B2C3" - build + machine identifiable at a glance.
     * The ID is also what the dashboard's device dropdown shows for this machine. */
    private fun buildVersionLabel(): String {
        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        }
        return "v${pkgInfo.versionName} (build $versionCode) - DEVICE - ID:$deviceId"
    }

    override fun onDestroy() {
        super.onDestroy()
        MdbLib.logListener = null
        MdbLib.stop()
        LogServer.stop()
        MqttLib.stop()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.open -> MdbLib.start()
            R.id.close -> MdbLib.stop()
            R.id.clear -> {
                log.clear()
                transContent.text = ""
            }
            R.id.vendApproved -> {
                if (MdbLib.approveVend()) {
                    announce("[button] vend approved requested")
                } else {
                    announce("[button] vend approved ignored \u2014 no VEND REQUEST pending")
                }
            }
        }
    }

    /** For lines the LIBRARY reported (it already enqueued them to MQTT itself): show on screen
     * (unless suppressed) and mirror to the USB/adb LogServer. High-volume traffic passes
     * showOnScreen = false - re-rendering the whole growing log on every bus poll is what used
     * to hang the app on real hardware, so those lines never touch the TextView. */
    private fun showLocally(line: String, showOnScreen: Boolean) {
        if (showOnScreen) {
            log.append(line).append("\n")
            runOnUiThread {
                transContent.text = log.toString()
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        LogServer.broadcast(line)
    }

    /** For lines THIS APP originates (banner, button presses): same local handling, plus a push
     * onto the shared outbound queue - the exact same enqueue() any app developer would use for
     * their own logs. */
    private fun announce(line: String) {
        showLocally(line, showOnScreen = true)
        MqttLib.enqueue(line)
    }
}
