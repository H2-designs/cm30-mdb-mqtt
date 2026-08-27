package com.ciontek.mdbsample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ciontek.mdblib.MdbSlave

/**
 * Minimal sample app on top of the `mdblib` wrapper — the Kotlin twin of RNSampleApp's App.tsx.
 * Same concept: start MDB, auto-decide vend requests in the listener (approve anything up to
 * 20.00), poll the read-once vend state for the outcome, and offer a force-reset button.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    private val pollVendState = object : Runnable {
        override fun run() {
            when (MdbSlave.fetchVendState()) {
                MdbSlave.VEND_STATE_IN_PROCESS -> statusView.text = "Dispensing…"
                MdbSlave.VEND_STATE_SUCCESS -> statusView.text = "Success"
                MdbSlave.VEND_STATE_FAILURE -> statusView.text = "Failed"
                null -> {} // nothing new
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "MDB Slave Sample (Kotlin)"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "starting…"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val resetButton = Button(this).apply {
            text = "Force reset"
            setOnClickListener {
                statusView.text = if (MdbSlave.forceReset()) "MDB running (reset)" else "MDB port failed to open"
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(statusView)
            addView(resetButton)
        })

        MdbSlave.vendRequestListener = MdbSlave.VendRequestListener { priceHigh, priceLow ->
            MdbSlave.combinePrice(priceHigh, priceLow) <= 20.0
        }

        statusView.text = if (MdbSlave.initMDB()) "MDB running" else "MDB port failed to open"

        handler.postDelayed(pollVendState, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
