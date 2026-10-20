package com.ciontek.HardwareDemo

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.rabbah.mdb.HardwareLib
import com.rabbah.mqtt.MqttConfig
import com.rabbah.mqtt.MqttLib

/**
 * Demo / reference integration of the two libraries. This Activity is deliberately thin - it is
 * exactly what any Android developer consuming the AARs would write:
 *
 *   1. MqttLib.init(...) + start()          - the transport with its outbound queue
 *   2. MdbMqttBridge.attach()               - the ~30-line glue: HardwareLib events -> MQTT,
 *                                             MQTT commands -> HardwareLib.handleCommand()
 *   3. HardwareLib.init(context) + start()  - the MDB slave engine (contains NO networking)
 *   4. (optional) HardwareLib.logListener   - mirror the engine's lines into this app's own UI
 *   5. (optional) MqttLib.enqueue(...)      - the app's own log lines share the same queue
 *
 * No protocol logic and no MQTT packet code lives here - the libraries are fully decoupled and
 * MdbMqttBridge is the single meeting point.
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

        // 1. Transport up first, so the bridge has a queue to enqueue into.
        // Backend-contract topics, same as the production fleet: devices/<id>/logs out,
        // devices/<id>/passthrough in (operator commands; the backend owns devices/<id>/cmd),
        // retained online/offline presence on devices/<id>/status. The old
        // cm30-mdb/hamdan-rabbah/<id>/liveLog world is gone.
        MqttLib.init(MqttConfig(
            topicPrefix = "devices",
            deviceId = deviceId,
            // Broker + credentials come from BuildConfig, which build.gradle fills from
            // local.properties - the secret never lives in source or in any repo.
            brokerHost = BuildConfig.MQTT_HOST,
            brokerPort = BuildConfig.MQTT_PORT,
            username = BuildConfig.MQTT_USERNAME.ifEmpty { null },
            password = BuildConfig.MQTT_PASSWORD.ifEmpty { null },
            logTopicSuffix = "logs",
            commandTopicSuffix = "cmd",
            statusTopicSuffix = "status"
        ))
        MqttLib.start()

        // Connection visibility: one line in the log the moment the broker session comes up or
        // drops (edge-triggered, plus once immediately with the current state).
        MqttLib.addConnectionListener { connected ->
            showLocally(
                if (connected) "[mqtt] CONNECTED to ${BuildConfig.MQTT_HOST}"
                else "[mqtt] DISCONNECTED - reconnecting...",
                showOnScreen = true
            )
        }

        // 2. The bridge BEFORE HardwareLib.init(): init() publishes the first settings snapshot
        //    through controlListener, and only an attached bridge can put it on the wire.
        MdbMqttBridge.attach()

        // 3. The MDB slave engine (no networking inside). After these lines every exchange,
        //    heartbeat, settings snapshot, and config ack reaches the dashboard via the bridge.
        HardwareLib.init(applicationContext)

        // 4. Local mirror: same lines on this screen and on the USB/adb LogServer. The bridge
        //    already ships them to MQTT - this listener must NOT enqueue again.
        HardwareLib.logListener = { line, showOnScreen -> showLocally(line, showOnScreen) }

        // 5. Proof that the structured exchange feed works: every exchange also shows as a
        //    compact "[exchange] code=N ..." line. Registered via addExchangeListener, so the
        //    single-slot HardwareLib.exchangeListener stays free for integrator code.
        HardwareLib.addExchangeListener { e ->
            showLocally("[exchange] code=${e.code} ${e.cmd.name} rx=${e.rxHex} tx=${e.txName}", e.showOnScreen)
        }

        LogServer.start()
        announce("-- hardware-lib ${HardwareLib.VERSION} | live log + remote control over MQTT, device ID: $deviceId --")

        // Auto-start the MDB port on launch - this app runs unattended on the vending machine.
        HardwareLib.start()

        // One-shot self-test: pushes a fake RESET through the listener pipeline, so the screen
        // (and dashboard) always shows one "[exchange] code=111 RESET_ACK rx=10 10" moments
        // after boot - if you can see it, the exchange feed provably works on this device.
        HardwareLib.simulateExchange("10 10")
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
        HardwareLib.logListener = null
        MdbMqttBridge.detach()
        HardwareLib.stop()
        LogServer.stop()
        MqttLib.stop()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.open -> HardwareLib.start()
            R.id.close -> HardwareLib.stop()
            R.id.clear -> {
                log.clear()
                transContent.text = ""
            }
            R.id.vendApproved -> {
                if (HardwareLib.approveVend()) {
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
