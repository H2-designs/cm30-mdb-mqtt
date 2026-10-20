package com.rabbah.pulsesample

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rabbah.mdb.HardwareLib
import com.rabbah.mdb.PulseLib
import kotlin.concurrent.thread

/**
 * Pulse test bench for hardware-lib's PulseLib - every pulse function on buttons, results in a
 * live log. No MQTT, no MDB engine: just the pulse API against the real CM30 digital IO.
 *
 *   1. INIT HIGH / INIT LOW      - polarity (drives the idle level immediately)
 *   2. width / period / count    - the train parameters (ms, ms, pulses)
 *   3. SEND PULSE                - runs the train, shows the true/false result
 *   4. ready feedback            - SET (expected[,channel]) / OFF / CHECK READY
 *
 * Every [pulse] line the library reports lands in the log below the buttons, so you see the
 * same messages the dashboard would.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val log = StringBuilder()

    private fun logLine(line: String) {
        runOnUiThread {
            log.insert(0, line + "\n")
            if (log.length > 20_000) log.setLength(20_000)
            logView.text = log.toString()
        }
    }

    private fun refreshStatus() = runOnUiThread {
        val mode = if (!PulseLib.isInitialized) "NOT SET"
            else if (PulseLib.isHighPulse) "HIGH (idle LOW)" else "LOW (idle HIGH)"
        val ready = PulseLib.readyFeedbackValue?.let { "expect digital_in == $it" } ?: "disabled"
        status.text = "hardware-lib ${HardwareLib.VERSION}\npulse mode: $mode\nready feedback: $ready | trains pending: ${PulseLib.pendingTrains}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HardwareLib.init(this)   // 7.26.0: ready-feedback gate (and all settings) persist across restarts

        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        root.addView(status)

        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun LinearLayout.button(label: String, onClick: () -> Unit) {
            addView(Button(this@MainActivity).apply {
                text = label
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        fun LinearLayout.numField(hintText: String, initial: String): EditText {
            val f = EditText(this@MainActivity).apply {
                hint = hintText
                setText(initial)
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            addView(f, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return f
        }

        // --- polarity ---
        root.addView(row().apply {
            button("INIT HIGH PULSE") { thread { PulseLib.initPulse(true); refreshStatus() } }
            button("INIT LOW PULSE") { thread { PulseLib.initPulse(false); refreshStatus() } }
        })

        // --- pulse train ---
        val widthField: EditText
        val periodField: EditText
        val countField: EditText
        val trainRow = row()
        widthField = trainRow.numField("width ms", "50")
        periodField = trainRow.numField("period ms", "100")
        countField = trainRow.numField("count", "3")
        root.addView(trainRow)
        root.addView(row().apply {
            button("SEND PULSE") {
                val w = widthField.text.toString().toIntOrNull() ?: 0
                val p = periodField.text.toString().toIntOrNull() ?: 0
                val c = countField.text.toString().toIntOrNull() ?: 0
                logLine(">> sendPulse(width=$w, period=$p, count=$c) ...")
                thread {
                    val ok = PulseLib.sendPulse(w, p, c)   // blocks until the train finishes
                    logLine("<< sendPulse returned $ok")
                    refreshStatus()
                }
            }
        })

        // --- ready feedback ---
        val expectedField: EditText
        val channelField: EditText
        val rfRow = row()
        expectedField = rfRow.numField("expected", "0")
        channelField = rfRow.numField("channel", "0")
        root.addView(rfRow)
        root.addView(row().apply {
            button("SET READY FB") {
                val e = expectedField.text.toString().toIntOrNull()
                val ch = channelField.text.toString().toIntOrNull() ?: 0
                thread { PulseLib.setReadyFeedback(e, ch); refreshStatus() }
            }
            button("READY FB OFF") { thread { PulseLib.setReadyFeedback(null); refreshStatus() } }
            button("CHECK READY") {
                thread {
                    val ready = PulseLib.isMachineReady()
                    logLine("<< isMachineReady returned $ready")
                }
            }
        })

        // --- vendor raw passthrough (parameter-order experiments) ---
        root.addView(row().apply {
            button("VENDOR PULSE (raw)") {
                val w = widthField.text.toString().toIntOrNull() ?: 50
                val p = periodField.text.toString().toIntOrNull() ?: 100
                val c = countField.text.toString().toIntOrNull() ?: 1
                val dir = if (PulseLib.isHighPulse) 0 else 1
                logLine(">> vendorPulse(dir=$dir, $w, $p, $c) ...")
                thread {
                    val ok = PulseLib.vendorPulse(dir, w, p, c)
                    logLine("<< vendorPulse returned $ok")
                }
            }
        })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) })

        // Every [pulse] line the library reports shows up here - same text the dashboard gets.
        HardwareLib.addLogListener { line, _ -> logLine(line) }

        logLine("-- pulse test bench | hardware-lib ${HardwareLib.VERSION} --")
        logLine("1) INIT polarity  2) SEND PULSE  3) optional ready feedback")
        refreshStatus()
    }
}
