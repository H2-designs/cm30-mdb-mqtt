package com.rabbah.logsample

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rabbah.mqtt.MdbLogEvent
import com.rabbah.mqtt.MqttConfig
import com.rabbah.mqtt.MqttLib
import com.rabbah.mqtt.RabbahLog
import com.rabbah.mqtt.RabbahLogEvent
import com.rabbah.mqtt.RabbahMqtt
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal proof that data is flowing: connect to YOUR broker (all settings editable on
 * screen, prefilled with the server's values), then press the send buttons and watch the
 * status line — "queued 0" after a send means the broker actually accepted the bytes.
 *
 * Verify server-side with:  mosquitto_sub -h <host> -u rabbah -P '<password>' -t '#' -v
 * Send something back with: mosquitto_pub ... -t '<prefix>/<deviceId>/inbox' -m '{"hi":1}'
 */
class MainActivity : Activity() {

    private lateinit var hostIn: EditText
    private lateinit var portIn: EditText
    private lateinit var userIn: EditText
    private lateinit var passIn: EditText
    private lateinit var prefixIn: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private var started = false
    private var subscribed = false
    private var sentCount = 0
    private var wireShown = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val prefs by lazy { getSharedPreferences("rabbahlog-sample", MODE_PRIVATE) }

    @get:SuppressLint("HardwareIds")
    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "sample-device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "RabbahLog sample — device $deviceId"
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        // Broker settings — prefilled with the server values, editable so the docker-internal
        // hostname ("mosquitto") can be replaced by the server's public IP/domain on the phone.
        hostIn = field("broker host (use your server's public IP)", prefs.getString("host", "mosquitto")!!)
        portIn = field("port", prefs.getInt("port", 1883).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // Password is deliberately NOT hardcoded (this file lives in a git repo) — type it once
        // and it persists in SharedPreferences on the device.
        userIn = field("username", prefs.getString("user", "rabbah")!!)
        passIn = field("password (type once — remembered)", prefs.getString("pass", "")!!)
        prefixIn = field("topic prefix", prefs.getString("prefix", "cm30-mdb/hamdan-rabbah")!!)

        root.addView(row(hostIn to 3f, portIn to 1f))
        root.addView(row(userIn to 1f, passIn to 1f))
        root.addView(prefixIn)

        val connectBtn = Button(this).apply {
            text = "Connect / apply settings"
            setOnClickListener { connect() }
        }
        root.addView(connectBtn)

        statusView = TextView(this).apply {
            text = "MQTT: not started"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(statusView)

        root.addView(row(
            button("Raw log") {
                sendEvent(com.rabbah.mqtt.InfoLogEvent.INFO_RAW,
                    listOf("hello from sample at " + clock.format(Date())))
            } to 1f,
            button("MDB example") {
                sendEvent(MdbLogEvent.MDB_VEND_REQUEST,
                    listOf("13 00 01 F4 00 03", "ACK", "500", "3"))
            } to 1f
        ))
        root.addView(row(
            button("Telemetry JSON") {
                val json = JSONObject()
                    .put("battery", 87)
                    .put("at", clock.format(Date()))
                RabbahMqtt.sendJson("telemetry", json)
                sentCount++
                appendLog("SENT  telemetry: $json")
            } to 1f,
            button("Burst x10") {
                for (i in 1..10) RabbahLog.raw("burst message $i/10")
                sentCount += 10
                appendLog("SENT  burst of 10 raw logs")
            } to 1f
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        appendLog("-- fill in the broker host (server public IP), then Connect --")
        ui.post(ticker)
    }

    /** Connects (or reconnects with edited settings) and persists them for next launch. */
    private fun connect() {
        val host = hostIn.text.toString().trim()
        val port = portIn.text.toString().trim().toIntOrNull() ?: 1883
        val user = userIn.text.toString().trim()
        val pass = passIn.text.toString()
        val prefix = prefixIn.text.toString().trim().trimEnd('/')
        if (host.isEmpty() || prefix.isEmpty()) {
            appendLog("!! broker host and topic prefix are required")
            return
        }
        prefs.edit().putString("host", host).putInt("port", port).putString("user", user)
            .putString("pass", pass).putString("prefix", prefix).apply()

        if (started) MqttLib.stop()
        MqttLib.init(MqttConfig(
            topicPrefix = prefix,
            deviceId = deviceId,
            brokerHost = host,
            brokerPort = port,
            username = user.ifEmpty { null },
            password = if (user.isEmpty() || pass.isEmpty()) null else pass
        ))
        MqttLib.start()
        started = true
        RabbahLog.init("rabbahlog-sample", "1.0")

        if (!subscribed) {
            subscribed = true
            // JSON in: anything published to <prefix>/<deviceId>/inbox shows up here.
            RabbahMqtt.subscribeJson("inbox") { json ->
                ui.post { appendLog("RECV  inbox: $json") }
            }
            // Plain commands (the dashboard's channel) — just display them in this sample.
            MqttLib.addCommandListener { cmd ->
                ui.post { appendLog("RECV  command: $cmd") }
                true
            }
        }
        appendLog("-- connecting to $host:$port — logs go to $prefix/$deviceId/liveLog --")
    }

    /** One send: the queue gets the compact item; the screen shows the decoded sentence
     * (and, once, the exact wire JSON so you can compare it against the server side). */
    private fun sendEvent(event: RabbahLogEvent, params: List<String>) {
        RabbahLog.log(event, params)
        sentCount++
        appendLog("SENT  " + RabbahLog.format(event, params))
        if (!wireShown) {
            wireShown = true
            appendLog("wire  RABBAH_LOG:" + RabbahLog.makeLogJson(event, params))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val state = when {
                !started -> "not started"
                MqttLib.isConnected -> "CONNECTED"
                else -> "connecting…"
            }
            statusView.text =
                "MQTT: $state   queued ${MqttLib.pendingMessages}   sent $sentCount   dropped ${MqttLib.droppedMessages}"
            statusView.setTextColor(if (started && MqttLib.isConnected) COLOR_OK else COLOR_WAIT)
            ui.postDelayed(this, 1000)
        }
    }

    private fun appendLog(line: String) {
        logView.append(clock.format(Date()) + "  " + line + "\n")
        // Keep the on-screen log bounded; the dashboard/broker holds the real history.
        val text = logView.text
        if (text.length > 20_000) logView.text = text.subSequence(text.length - 15_000, text.length)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- tiny programmatic-UI helpers ----------

    private fun field(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg cells: Pair<android.view.View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((view, weight) in cells) {
            addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, weight))
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        val COLOR_OK = Color.rgb(27, 127, 58)
        val COLOR_WAIT = Color.rgb(176, 90, 0)
    }
}
