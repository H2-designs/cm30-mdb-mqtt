package com.rabbah.rs232sample

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
import com.rabbah.mdb.Rs232Lib
import kotlin.concurrent.thread

/**
 * RS232 test bench for hardware-lib's Rs232Lib - the whole rule workflow on buttons, results in
 * a live log. No MQTT, no MDB engine: just the RS232 API against the CM30 serial port.
 *
 *   1. LOAD RULES          - paste/edit the rule JSON and load it (persisted)
 *   2. baud + OPEN PORT    - start listening; every machine frame is matched + auto-replied
 *   3. SIMULATE RX         - test a rule WITHOUT the machine (feeds a fake frame through)
 *   4. SEND HEX            - manual TX, e.g. a vend approval frame
 *
 * Every [rs232] line the library reports lands in the log below the buttons - the same messages
 * the dashboard would see. Vend-request matches show ">> VEND REQUEST price=N".
 */
class MainActivity : Activity() {

    companion object {
        // Reader -> machine payment results, CRC precomputed (XOR) - verified against the doc.
        const val TX_PAYMENT_SUCCESS = "A1 02 00 07 53 55 43 43 45 53 53 E7"
        const val TX_PAYMENT_FAILED = "A1 02 00 06 46 41 49 4C 45 44 A6"
    }

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var payStatus: TextView
    private val log = StringBuilder()
    @Volatile private var pendingPrice: Int = -1

    private fun logLine(line: String) {
        runOnUiThread {
            log.insert(0, line + "\n")
            if (log.length > 20_000) log.setLength(20_000)
            logView.text = log.toString()
        }
    }

    private fun refreshStatus() = runOnUiThread {
        val port = if (Rs232Lib.isOpen) "OPEN" else "closed"
        val ruleCount = try { org.json.JSONArray(Rs232Lib.rulesJson()).length() } catch (_: Throwable) { 0 }
        val crc = if (Rs232Lib.crcCheckEnabled) "ON" else "off"
        status.text = "hardware-lib ${HardwareLib.VERSION}\nport: $port | rules: $ruleCount/${Rs232Lib.MAX_RULES} | frame gap: ${Rs232Lib.frameGapMs}ms | crc check: $crc"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Rs232Lib.init(this)

        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        root.addView(status)

        // --- payment approval panel: a VEND REQUEST parks here until APPROVE/DECLINE ---
        payStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            text = "no payment pending"
            setPadding(0, pad, 0, 0)
        }
        root.addView(payStatus)

        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun LinearLayout.button(label: String, onClick: () -> Unit) {
            addView(Button(this@MainActivity).apply {
                text = label
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        // APPROVE sends the SUCCESS frame, DECLINE the FAILED frame - both with correct XOR CRC.
        fun answerPayment(approve: Boolean) {
            val price = pendingPrice
            if (price < 0) { logLine("-- no payment pending --"); return }
            val frame = if (approve) TX_PAYMENT_SUCCESS else TX_PAYMENT_FAILED
            thread {
                val ok = Rs232Lib.sendHex(frame)
                logLine("<< ${if (approve) "APPROVED" else "DECLINED"} price=$price sent=$ok")
                runOnUiThread {
                    pendingPrice = -1
                    payStatus.text = "last: ${if (approve) "APPROVED" else "DECLINED"} price=$price"
                }
            }
        }
        root.addView(row().apply {
            button("APPROVE") { answerPayment(true) }
            button("DECLINE") { answerPayment(false) }
        })

        // --- rule table ---
        val rulesField = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            val saved = Rs232Lib.rulesJson()
            setText(
                if (saved != "[]") saved
                else """[
 {"name":"CONNECT","rx":"A0 04 00 03 43 4F 4E E5","tx":"A1 05 00 02 4F 4B A2"},
 {"name":"HEARTBEAT","rx":"A0 06 00 02 48 42 ??","tx":"A1 06 00 02 4F 4B A1"},
 {"name":"PAYMENT_REQUEST","rx":"A0 01 *","tx":"","amountStart":4,"amountEnd":-1},
 {"name":"PAYMENT_TYPED","rx":"A0 06 *","tx":"","amountStart":5,"amountEnd":-1},
 {"name":"PRODUCTION_OK","rx":"A0 03 00 07 53 55 43 43 45 53 53 E7","tx":""},
 {"name":"PRODUCTION_FAIL","rx":"A0 03 00 06 46 41 49 4C 45 44 A6","tx":""},
 {"name":"CANCEL","rx":"A0 08 00 06 43 41 4E 43 45 4C A8","tx":""}
]"""
            )
        }
        root.addView(rulesField)
        root.addView(row().apply {
            button("LOAD RULES") {
                val err = Rs232Lib.setRulesJson(rulesField.text.toString())
                logLine(if (err == null) "<< rules loaded OK" else "<< rules REJECTED: $err")
                refreshStatus()
            }
            button("GET RULES") { rulesField.setText(Rs232Lib.rulesJson()); logLine("<< current rules shown above") }
            button("CLEAR") { Rs232Lib.clearRules(); refreshStatus() }
        })

        // --- port ---
        val baudField = EditText(this).apply {
            hint = "baud"
            setText("9600")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(row().apply {
            addView(baudField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            button("OPEN PORT") {
                val baud = baudField.text.toString().toIntOrNull() ?: 9600
                thread {
                    val ok = Rs232Lib.open(baud = baud)
                    logLine("<< open returned $ok")
                    refreshStatus()
                }
            }
            button("CLOSE") { thread { Rs232Lib.close(); refreshStatus() } }
        })

        // --- simulate rx (rule test without the machine) ---
        val simField = EditText(this).apply {
            hint = "simulate machine frame"
            setText("A0 01 00 06 31 35 30 30 30 30 A3")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(simField)
        root.addView(row().apply {
            button("SIMULATE RX") { thread { Rs232Lib.simulateFrame(simField.text.toString()) } }
            button("CRC ON") { Rs232Lib.setCrcCheck(true); refreshStatus() }
            button("CRC OFF") { Rs232Lib.setCrcCheck(false); refreshStatus() }
        })

        // --- manual tx ---
        val txField = EditText(this).apply {
            hint = "raw hex to send"
            setText("A1 02 00 07 53 55 43 43 45 53 53 E7")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(txField)
        root.addView(row().apply {
            button("SEND HEX") {
                thread {
                    val ok = Rs232Lib.sendHex(txField.text.toString())
                    logLine("<< sendHex returned $ok")
                }
            }
        })

        // --- framed tx: header + ASCII payload, length + XOR CRC computed by the library ---
        val headerField: EditText
        val asciiField: EditText
        val frameRow = row()
        headerField = EditText(this).apply {
            hint = "header"
            setText("A1 02")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        asciiField = EditText(this).apply {
            hint = "ascii payload"
            setText("SUCCESS")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        frameRow.addView(headerField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        frameRow.addView(asciiField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(frameRow)
        root.addView(row().apply {
            button("BUILD + SEND FRAME") {
                thread {
                    val ok = Rs232Lib.sendXorAscii(headerField.text.toString(), asciiField.text.toString())
                    logLine("<< sendXorAscii returned $ok")
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

        // Every [rs232] line the library reports shows up here - same text the dashboard gets.
        HardwareLib.addLogListener { line, _ -> logLine(line) }
        Rs232Lib.vendRequestListener = { price, frameHex ->
            logLine(">> VEND REQUEST price=$price frame=$frameHex")
            runOnUiThread {
                pendingPrice = price
                payStatus.text = "PAYMENT PENDING: $price  ->  APPROVE or DECLINE"
            }
        }

        logLine("-- rs232 test bench | hardware-lib ${HardwareLib.VERSION} --")
        logLine("1) LOAD RULES  2) OPEN PORT (or SIMULATE RX without machine)")
        refreshStatus()
    }
}
