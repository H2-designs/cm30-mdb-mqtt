package com.ciontek.mdblib

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Public entry point for app developers integrating the CM30 MDB Slave (Cashless Device #1,
 * address 0x10). Owns the whole RESET -> SETUP -> EXPANSION -> READER ENABLE -> session/vend
 * protocol state machine internally — callers only ever touch the four members below:
 *
 *  - [initMDB] — starts the port + background thread and runs the protocol automatically.
 *  - [vendRequestListener] — called the instant a VEND REQUEST arrives; return true/false to
 *    approve or deny it.
 *  - [fetchVendState] — read-once vend outcome: null / [VEND_STATE_IN_PROCESS] /
 *    [VEND_STATE_SUCCESS] / [VEND_STATE_FAILURE].
 *  - [forceReset] — closes and reopens the port, restarting the state machine from scratch.
 *
 * No UI dependency of any kind — safe to call from any Android app, Activity or Service.
 */
object MdbSlave {

    /** A vend was approved and is being dispensed — waiting for the VMC to report the outcome. */
    const val VEND_STATE_IN_PROCESS = 1

    /** The VMC reported VEND SUCCESS (product dispensed). */
    const val VEND_STATE_SUCCESS = 2

    /** The VMC reported VEND FAILURE (product not dispensed, refund initiated). */
    const val VEND_STATE_FAILURE = 3

    /**
     * Implement this and assign it to [vendRequestListener] to decide whether to approve a vend.
     * Called synchronously on the library's internal background thread the moment a VEND REQUEST
     * arrives — return quickly (e.g. a local balance check), since the VMC is waiting on a timely
     * reply. Return true to approve the vend, false to deny it.
     */
    fun interface VendRequestListener {
        fun onVendRequest(priceHigh: Int, priceLow: Int): Boolean
    }

    /**
     * Set this to decide vend approval. If left null when a VEND REQUEST arrives, the request is
     * denied by default (safe default — no accidental free vends).
     */
    var vendRequestListener: VendRequestListener? = null

    /** Scale factor this device declares to the VMC (via READER CONFIG DATA) — used by
     * [combinePrice] to convert raw MDB price bytes into a real currency amount. */
    const val PRICE_SCALE_FACTOR = 1

    /** Decimal places this device declares to the VMC (via READER CONFIG DATA). */
    const val PRICE_DECIMAL_PLACES = 2

    /**
     * Combines the raw [priceHigh]/[priceLow] bytes handed to [VendRequestListener.onVendRequest]
     * into the real currency amount — the number you'd actually send to a payment gateway.
     * Formula: (priceHigh * 256 + priceLow) * [PRICE_SCALE_FACTOR] * 10^(-[PRICE_DECIMAL_PLACES]).
     * Example: priceHigh=0x03, priceLow=0xE8 (raw 1000) -> 10.00.
     */
    fun combinePrice(priceHigh: Int, priceLow: Int): Double {
        val raw = ((priceHigh and 0xFF) shl 8) or (priceLow and 0xFF)
        return raw * PRICE_SCALE_FACTOR * Math.pow(10.0, -PRICE_DECIMAL_PLACES.toDouble())
    }

    private enum class MdbState { INACTIVE_STATE, DISABLED_STATE, ENABLED_STATE, VEND_STATE }

    private const val CMD_RESET = 0x10
    private const val CMD_SETUP = 0x11
    private const val CMD_POLL = 0x12
    private const val CMD_VEND = 0x13
    private const val CMD_READER = 0x14
    private const val CMD_EXPANSION = 0x17

    /** Repeat JUST RESET for this many polls before switching to plain ACK. */
    private const val JUST_RESET_REPEAT_COUNT = 3

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    // --- protocol state machine fields ---
    private var mdbState = MdbState.INACTIVE_STATE
    private var justResetCount = 0
    private var priceHigh = 0
    private var priceLow = 0
    private var sessionBeginPending = false
    private var endSessionPending = false
    private var vendCancelPending = false
    private var vendApprovePending = false
    private var vendDeniedPending = false
    private var vendRequestReceived = false

    private val vendStateLock = Any()
    private var vendState: Int? = null

    /**
     * Opens the MDB slave port and starts the background thread that runs the whole protocol
     * (RESET/SETUP/EXPANSION/READER ENABLE/session/vend) automatically. Safe to call again while
     * already running — it's a no-op in that case. Returns false if the port failed to open.
     */
    fun initMDB(): Boolean {
        if (running.getAndSet(true)) return true
        if (!MdbSlaveWrapper.open()) {
            running.set(false)
            return false
        }
        resetProtocolState()

        worker = thread(name = "MdbSlaveLib") {
            while (running.get()) {
                val cmd = MdbSlaveWrapper.receiveCommand()
                if (cmd != null && cmd.isNotEmpty()) {
                    processCommand(cmd)
                }
                Thread.sleep(1)
            }
        }
        return true
    }

    /**
     * Forces a full reset: closes the port and immediately reopens it, restarting the protocol
     * state machine from scratch — the automated equivalent of pressing close then open.
     */
    fun forceReset(): Boolean {
        running.set(false)
        worker = null
        MdbSlaveWrapper.close()
        return initMDB()
    }

    /**
     * Reads and clears the current vend outcome in one step. Returns null if nothing has changed
     * since the last call — [VEND_STATE_IN_PROCESS]/[VEND_STATE_SUCCESS]/[VEND_STATE_FAILURE]
     * otherwise. Once read, the value resets to null until the next vend event occurs.
     */
    fun fetchVendState(): Int? = synchronized(vendStateLock) {
        val current = vendState
        vendState = null
        current
    }

    private fun setVendState(state: Int) = synchronized(vendStateLock) {
        vendState = state
    }

    private fun resetProtocolState() {
        mdbState = MdbState.INACTIVE_STATE
        justResetCount = 0
        priceHigh = 0
        priceLow = 0
        sessionBeginPending = false
        endSessionPending = false
        vendCancelPending = false
        vendApprovePending = false
        vendDeniedPending = false
        vendRequestReceived = false
        synchronized(vendStateLock) { vendState = null }
    }

    private fun ByteArray.byteAt(index: Int): Int =
        if (index < size) this[index].toInt() and 0xFF else -1

    private fun processCommand(cmd: ByteArray) {
        val b0 = cmd.byteAt(0)

        // Cashless Device #1's MDB address range is 0x10-0x17. Everything outside that is the
        // VMC probing other peripheral types (Bill Validator, Coin Changer, Cashless #2, etc.) —
        // not addressed to us.
        if (b0 !in 0x10..0x17) return

        val b1 = cmd.byteAt(1)
        val b2 = cmd.byteAt(2)
        val b3 = cmd.byteAt(3)

        when (mdbState) {
            MdbState.INACTIVE_STATE -> {
                if (b0 == CMD_RESET) {
                    MdbSlaveWrapper.ack()
                } else if (b0 == CMD_POLL && justResetCount < JUST_RESET_REPEAT_COUNT) {
                    MdbSlaveWrapper.jstReset()
                    justResetCount++
                } else if (b0 == CMD_POLL) {
                    MdbSlaveWrapper.ack()
                } else if (b0 == CMD_SETUP && b1 == 0x00) {
                    MdbSlaveWrapper.readerConfigData(0x02, 0x0E)
                } else if (b0 == CMD_SETUP && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                } else if (b0 == CMD_EXPANSION && b1 == 0x00) {
                    MdbSlaveWrapper.readerConfigInfo()
                    mdbState = MdbState.DISABLED_STATE
                }
            }

            MdbState.DISABLED_STATE -> {
                if (b0 == CMD_EXPANSION && b1 == 0x00) {
                    // VMC retried EXPANSION REQUEST ID — resend without changing state again.
                    MdbSlaveWrapper.readerConfigInfo()
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    mdbState = MdbState.ENABLED_STATE
                    // No real card-tap detection here — session begins automatically.
                    sessionBeginPending = true
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    MdbSlaveWrapper.can()
                } else if (b0 == CMD_POLL) {
                    MdbSlaveWrapper.ack()
                }
            }

            MdbState.ENABLED_STATE -> {
                if (b0 == CMD_POLL) {
                    if (sessionBeginPending) {
                        MdbSlaveWrapper.sessionBegin()
                        sessionBeginPending = false
                        mdbState = MdbState.VEND_STATE
                    } else {
                        MdbSlaveWrapper.ack()
                    }
                } else if (b0 == CMD_RESET) {
                    MdbSlaveWrapper.ack()
                    mdbState = MdbState.INACTIVE_STATE
                } else if (b0 == CMD_READER && b1 == 0x00) {
                    // VMC disabled the reader between sessions (common on real hardware after
                    // END SESSION) — drop back to DISABLED_STATE so a subsequent READER ENABLE
                    // is handled the same way as the very first one. Previously unhandled here,
                    // which silently dropped the ACK and could make the VMC give up on us,
                    // never sending the next cycle's VEND REQUEST.
                    MdbSlaveWrapper.ack()
                    sessionBeginPending = false
                    mdbState = MdbState.DISABLED_STATE
                } else if (b0 == CMD_READER && b1 == 0x01) {
                    // Already enabled (or a retried ENABLE) — just re-arm/ack, no state change.
                    MdbSlaveWrapper.ack()
                    sessionBeginPending = true
                } else if (b0 == CMD_READER && b1 == 0x02) {
                    MdbSlaveWrapper.can()
                }
            }

            MdbState.VEND_STATE -> {
                if (b0 == CMD_VEND && b1 == 0x00) {
                    MdbSlaveWrapper.ack()
                    priceHigh = b2
                    priceLow = b3
                    vendRequestReceived = true
                    // Ask the app's listener right now — synchronously — whether to approve.
                    // Denied by default if no listener is set.
                    val approved = vendRequestListener?.onVendRequest(priceHigh, priceLow) ?: false
                    if (approved) {
                        vendApprovePending = true
                    } else {
                        vendDeniedPending = true
                    }
                } else if (b0 == CMD_VEND && b1 == 0x02) {
                    // VMC reports the vend succeeded (product dispensed).
                    MdbSlaveWrapper.ack()
                    setVendState(VEND_STATE_SUCCESS)
                } else if (b0 == CMD_VEND && b1 == 0x03) {
                    // VEND FAILURE — ACK immediately (refund initiated); the idle-POLL ACK below
                    // implicitly signals "refund done" per spec.
                    MdbSlaveWrapper.ack()
                    setVendState(VEND_STATE_FAILURE)
                } else if (b0 == CMD_RESET) {
                    MdbSlaveWrapper.ack()
                    mdbState = MdbState.INACTIVE_STATE
                } else if (b0 == CMD_VEND && b1 == 0x04) {
                    MdbSlaveWrapper.ack()
                    endSessionPending = true
                } else if (b0 == CMD_VEND && b1 == 0x01) {
                    MdbSlaveWrapper.ack()
                    vendCancelPending = true
                } else if (b0 == CMD_POLL) {
                    if (endSessionPending) {
                        mdbState = MdbState.ENABLED_STATE
                        MdbSlaveWrapper.endSession()
                        endSessionPending = false
                        // Re-arm for the next cycle — same reasoning as the initial auto-arm.
                        sessionBeginPending = true
                    } else if (vendCancelPending) {
                        // The VMC's own VEND CANCEL — answered with SESSION CANCEL REQUEST, not
                        // VEND DENIED, since this is a cancellation, not a rejection of a request
                        // we're still holding.
                        MdbSlaveWrapper.sessionCancel()
                        vendCancelPending = false
                        vendRequestReceived = false
                        priceHigh = 0
                        priceLow = 0
                    } else if (vendDeniedPending) {
                        MdbSlaveWrapper.vendDenied()
                        vendDeniedPending = false
                        vendRequestReceived = false
                        priceHigh = 0
                        priceLow = 0
                    } else if (vendApprovePending && vendRequestReceived) {
                        MdbSlaveWrapper.vendApproved(priceHigh, priceLow)
                        vendApprovePending = false
                        vendRequestReceived = false
                        priceHigh = 0
                        priceLow = 0
                        setVendState(VEND_STATE_IN_PROCESS)
                    } else {
                        MdbSlaveWrapper.ack()
                    }
                }
            }
        }
    }
}
