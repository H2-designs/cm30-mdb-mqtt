package com.rabbah.mdb

import android.hardware.digital.DigitalIO
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulse output over the CM30's digital IO (vendor class android.hardware.digital.DigitalIO).
 *
 * POLARITY - set once from the backend/dashboard value:
 *   initPulse(true)  = HIGH-pulse mode: the line idles LOW, each pulse drives it HIGH.
 *   initPulse(false) = LOW-pulse mode:  the line idles HIGH, each pulse drives it LOW.
 * initPulse() drives the idle level onto the pin IMMEDIATELY, so the machine sees the correct
 * resting state from that moment on.
 *
 * TIMING - the one thing that must never wobble: a stretched pulse width makes the machine
 * reject the pulse. The engine drives EVERY edge itself via digital_out_set_value on one
 * dedicated "PulseWorker" thread at THREAD_PRIORITY_URGENT_AUDIO that does nothing else:
 * active level for width ms, idle for (period - width) ms, exactly count times. Waits are
 * deadline-based sleep-then-spin (sleep the bulk, spin the last ~2 ms), so widths never
 * stretch and period error never accumulates. The vendor's own digital_out_pulse is NOT used
 * for trains - tested on real hardware it produced the wrong pulse count and ignored the
 * direction (undocumented parameter semantics); it remains available as [vendorPulse] for
 * experiments only.
 *
 *   PulseLib.initPulse(backendSaysHigh)          // true = high pulses, false = low pulses
 *   PulseLib.sendPulse(50, 100, 3)               // 3 pulses: 50 ms active, 100 ms period
 *
 * Dashboard/backend equivalents (via HardwareLib.handleCommand, so they work over MQTT with
 * no extra wiring): "initPulse:true" / "initPulse:false" and "sendPulse:50,100,3".
 *
 * sendPulse() only QUEUES (returns immediately, safe from any thread); trains run one at a
 * time in FIFO order. Progress/results are reported as "[pulse] ..." log lines through the
 * normal HardwareLib log pipeline - AFTER the train finishes, never during it.
 *
 * A device without the CM30 runtime degrades to reported "[pulse] ... failed" lines and
 * false returns - never a crash (same policy as HardwareLib.start()).
 */
object PulseLib {

    private val digitalIO: DigitalIO? by lazy {
        try {
            DigitalIO()
        } catch (t: Throwable) {
            HardwareLib.reportLine("[pulse] DigitalIO unavailable (${t.javaClass.simpleName}) - no CM30 runtime")
            null
        }
    }

    /** True after a successful [initPulse] - [sendPulse] refuses to run before polarity is set,
     * because pulsing against the wrong idle level is exactly the bug this API exists to avoid. */
    @Volatile
    var isInitialized: Boolean = false
        private set

    /** The configured polarity: true = HIGH-pulse mode (idle LOW), false = LOW-pulse mode
     * (idle HIGH). Meaningless before [isInitialized]. */
    @Volatile
    var isHighPulse: Boolean = true
        private set

    private class PulseJob(val widthMs: Int, val periodMs: Int, val count: Int) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var ok = false
    }

    private val jobs = LinkedBlockingQueue<PulseJob>(100)
    private val workerStarted = AtomicBoolean(false)

    /** Pulse trains queued but not yet finished. 0 = the line is idle. */
    val pendingTrains: Int
        get() = jobs.size + (if (trainRunning) 1 else 0)

    @Volatile private var trainRunning = false

    /**
     * Sets the pulse polarity from the backend/dashboard value and drives the idle level:
     * highPulse=true -> idle LOW (0), pulses go HIGH; highPulse=false -> idle HIGH (1),
     * pulses go LOW. NOTE the inversion (true -> 0): the backend boolean names the PULSE
     * level, digital_out_set_value takes the IDLE level - they are opposites by definition.
     * Safe to call again whenever the backend changes the setting. Returns true when the
     * hardware accepted the write.
     */
    @JvmStatic
    fun initPulse(highPulse: Boolean): Boolean {
        val io = digitalIO ?: return false
        isHighPulse = highPulse
        isInitialized = try {
            io.digital_out_set_value(if (highPulse) 0 else 1) >= 0
        } catch (t: Throwable) {
            false
        }
        if (isInitialized) {
            // 7.27.0: remembered, so the next boot re-drives the same idle level (see loadPersistedPulseMode).
            MdbSettings.pulseMode = if (highPulse) "high" else "low"
            HardwareLib.publishSettingsSnapshot()
        }
        HardwareLib.reportLine(
            if (isInitialized) "[pulse] mode=${if (highPulse) "HIGH (idle LOW)" else "LOW (idle HIGH)"}"
            else "[pulse] initPulse failed - digital_out_set_value rejected"
        )
        return isInitialized
    }

    /**
     * Re-applies the persisted polarity (7.27.0): if a previous initPulse succeeded on this device, the
     * same idle level is driven again and sendPulse is allowed straight away. "unset" does nothing.
     * Called from HardwareLib.init(); harmless to call again.
     */
    @JvmStatic
    fun loadPersistedPulseMode(): Boolean = when (MdbSettings.pulseMode) {
        "high" -> initPulse(true)
        "low" -> initPulse(false)
        else -> false
    }

    /**
     * Sends one pulse train and returns whether it ACTUALLY went out: [count] pulses, each
     * [pulseWidthMs] at the active level followed by (pulsePeriodMs - pulseWidthMs) back at
     * idle. The active level comes from [initPulse] (HIGH-pulse mode pulses high, LOW-pulse
     * mode pulses low).
     *
     * The train itself always runs on the dedicated top-priority PulseWorker thread - the
     * calling thread only WAITS for the result, so the caller's priority can never distort the
     * pulse timing. The call therefore blocks for about pulsePeriodMs x count (plus any train
     * already in the queue) - call it from a coroutine/background thread, never the UI thread.
     *
     * Returns true only when EVERY pulse was physically written; false when rejected (not
     * initialized, width < 1, period < width, period == width with count > 1, count < 1,
     * queue full) or when the hardware reported an error mid-train. Every rejection/failure
     * is also reported as a "[pulse] ..." log line.
     */
    @JvmStatic
    fun sendPulse(pulseWidthMs: Int, pulsePeriodMs: Int, count: Int): Boolean {
        if (!isInitialized) {
            HardwareLib.reportLine("[pulse] sendPulse rejected - call initPulse(true|false) first")
            return false
        }
        if (pulseWidthMs < 1 || count < 1 || pulsePeriodMs < pulseWidthMs ||
            (count > 1 && pulsePeriodMs == pulseWidthMs)
        ) {
            HardwareLib.reportLine(
                "[pulse] sendPulse rejected - invalid width=$pulseWidthMs period=$pulsePeriodMs count=$count"
            )
            return false
        }
        // Ready-feedback gate: when the backend armed an expected digital-in value, the machine
        // must report it RIGHT NOW or no pulse goes out (see setReadyFeedback/isMachineReady).
        if (!isMachineReady()) {
            HardwareLib.reportLine("[pulse] sendPulse rejected - machine not ready (ready feedback mismatch)")
            return false
        }
        ensureWorker()
        val job = PulseJob(pulseWidthMs, pulsePeriodMs, count)
        if (!jobs.offer(job)) {
            HardwareLib.reportLine("[pulse] sendPulse rejected - queue full")
            return false
        }
        job.done.await()
        return job.ok
    }

    // ------------------------------------ ready feedback ------------------------------------

    /** The digital-in value that means "machine ready to accept pulses", or null when the
     * machine does not support ready feedback (every pulse is then allowed). Set from the
     * backend model via [setReadyFeedback]. */
    @Volatile
    var readyFeedbackValue: Int? = null
        private set

    @Volatile private var readyFeedbackChannel = 0

    /** 8.1.0: the full gate - digital-in channel -> value that means READY. Empty = gate off. The CM30
     *  exposes three inputs (0..2); every entry must match before a pulse train goes out. */
    @Volatile
    var readyFeedbackChannels: Map<Int, Int> = emptyMap()
        private set

    /** "0=1,2=0" <-> map. Returns null when the text is not channel=value pairs. */
    @JvmStatic
    fun parseReadyChannels(text: String): Map<Int, Int>? {
        val out = LinkedHashMap<Int, Int>()
        for (part in text.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val kv = part.split("=").map { it.trim() }
            val ch = kv.getOrNull(0)?.toIntOrNull() ?: return null
            val v = kv.getOrNull(1)?.toIntOrNull() ?: return null
            if (ch !in 0..7 || v !in 0..1) return null
            out[ch] = v
        }
        return out
    }

    private fun channelsText(map: Map<Int, Int>): String = map.entries.joinToString(",") { it.key.toString() + "=" + it.value }

    init {
        // Whatever is persisted (or was set before init) is the starting point - MdbSettings is safe
        // to read before HardwareLib.init(); loadPersistedReadyFeedback() re-reads once prefs exist.
        loadPersistedReadyFeedback()
    }

    /** The digital input read by the gate ([setReadyFeedback]). */
    val readyFeedbackInputChannel: Int
        get() = readyFeedbackChannel

    /** Re-applies the persisted ready-feedback gate (7.26.0). Called from HardwareLib.init() once
     * SharedPreferences are available; harmless to call any time. */
    @JvmStatic
    fun loadPersistedReadyFeedback() {
        readyFeedbackChannel = MdbSettings.readyFeedbackChannel
        readyFeedbackValue = if (MdbSettings.readyFeedbackEnabled) MdbSettings.readyFeedbackValue else null
        val multi = parseReadyChannels(MdbSettings.readyFeedbackChannels) ?: emptyMap()
        readyFeedbackChannels = when {
            !MdbSettings.readyFeedbackEnabled -> emptyMap()
            multi.isNotEmpty() -> multi
            else -> mapOf(readyFeedbackChannel to (readyFeedbackValue ?: 1))
        }
    }

    /**
     * 8.1.0: arms the gate over SEVERAL digital inputs at once - [channels] maps each input (0..2 on the
     * CM30) to the value that means READY. Every entry must match before any pulse train goes out.
     * An empty map disables the gate. Persisted; the single-channel setReadyFeedback() is now the
     * one-entry special case of this.
     */
    @JvmStatic
    fun setReadyFeedbackChannels(channels: Map<Int, Int>): Boolean {
        readyFeedbackChannels = LinkedHashMap(channels)
        val first = channels.entries.firstOrNull()
        readyFeedbackValue = first?.value
        readyFeedbackChannel = first?.key ?: 0
        MdbSettings.readyFeedbackEnabled = channels.isNotEmpty()
        if (first != null) { MdbSettings.readyFeedbackValue = first.value; MdbSettings.readyFeedbackChannel = first.key }
        MdbSettings.readyFeedbackChannels = channelsText(channels)
        HardwareLib.publishSettingsSnapshot()
        HardwareLib.reportLine(
            if (channels.isEmpty()) "[pulse] ready feedback disabled - machine always treated as ready"
            else "[pulse] ready feedback armed - " + channels.entries.joinToString(", ") { "digital_in(" + it.key + ") must equal " + it.value } + " before pulsing"
        )
        return true
    }

    /** Re-arms the gate with the last persisted value/channel (the dashboard "Ready FB" toggle ON). */
    @JvmStatic
    fun enableReadyFeedback(): Boolean {
        val multi = parseReadyChannels(MdbSettings.readyFeedbackChannels) ?: emptyMap()
        return if (multi.isNotEmpty()) setReadyFeedbackChannels(multi)
        else setReadyFeedback(MdbSettings.readyFeedbackValue, MdbSettings.readyFeedbackChannel)
    }

    /**
     * Arms the ready-feedback gate from the backend model: pass the expected digital-in value
     * (the backend's readyFeedbackValue) to require a match before every pulse train, or null
     * when the machine does not support ready feedback / the backend sent no value - pulses
     * are then always allowed, exactly like the app-side check this replaces. [inputChannel]
     * selects which digital input is read (default 0).
     */
    /**
     * The backend-model form: pass the dashboard flag and value EXACTLY as received -
     *
     *     PulseLib.setReadyFeedback(model.supportReadyFeedback == true, model.readyFeedbackValue)
     *
     * supported=false, or supported=true with a null value, disables the gate (pulses always
     * allowed - the machine has no ready feedback / the backend sent nothing). supported=true
     * with a value arms it: digital_in(channel) must equal the value before every train.
     */
    @JvmStatic
    @JvmOverloads
    fun setReadyFeedback(supported: Boolean, expectedValue: Int?, inputChannel: Int = 0): Boolean =
        setReadyFeedback(if (supported) expectedValue else null, inputChannel)

    @JvmStatic
    @JvmOverloads
    fun setReadyFeedback(expectedValue: Int?, inputChannel: Int = 0): Boolean {
        // 7.26.0 single-channel form, since 8.1.0 the one-entry case of setReadyFeedbackChannels().
        // Disabling keeps the last value/channel persisted so "on" without a value can restore it.
        if (expectedValue == null) {
            MdbSettings.readyFeedbackChannel = inputChannel
            return setReadyFeedbackChannels(emptyMap())
        }
        return setReadyFeedbackChannels(mapOf(inputChannel to expectedValue))
    }

    /**
     * True when the machine is ready to accept pulses:
     *  - ready feedback not configured (null) -> true, logged as unsupported;
     *  - otherwise reads digital_in_get_value(channel) and compares with the expected value -
     *    true only on a match. The expected/actual/ready values are reported as a log line.
     * [sendPulse] calls this itself before every train, so a not-ready machine is never pulsed;
     * it is public so an app can also check ahead of time (e.g. before charging a customer).
     */
    @JvmStatic
    fun isMachineReady(): Boolean {
        val gate = readyFeedbackChannels
        if (gate.isEmpty()) {
            HardwareLib.reportLine("[pulse] ready feedback unsupported/not set - treated as ready")
            return true
        }
        val io = digitalIO ?: return false
        var allReady = true
        val details = StringBuilder()
        for ((channel, expected) in gate) {
            val actual = try {
                io.digital_in_get_value(channel)
            } catch (t: Throwable) {
                HardwareLib.reportLine("[pulse] ready feedback read failed on digital_in(" + channel + "): " + t.javaClass.simpleName + " " + t.message)
                return false
            }
            if (actual != expected) allReady = false
            if (details.isNotEmpty()) details.append(", ")
            details.append("in").append(channel).append(" expected=").append(expected).append(" actual=").append(actual)
        }
        HardwareLib.reportLine("[pulse] ready feedback " + details + " ready=" + allReady)
        return allReady
    }

    /**
     * RAW passthrough to the vendor's own native pulser, digital_out_pulse(p1, p2, p3, p4) -
     * for experiments only, until the vendor documents the parameter order (the bytecode ships
     * no parameter names; known constants: PULSE_DIR_POSITIVE=0, PULSE_DIR_NEGATIVE=1).
     * Returns true when the vendor call returned >= 0.
     */
    @JvmStatic
    fun vendorPulse(p1: Int, p2: Int, p3: Int, p4: Int): Boolean {
        val io = digitalIO ?: return false
        val ret = try {
            io.digital_out_pulse(p1, p2, p3, p4)
        } catch (t: Throwable) {
            HardwareLib.reportLine("[pulse] vendorPulse failed: ${t.javaClass.simpleName} ${t.message}")
            return false
        }
        HardwareLib.reportLine("[pulse] vendorPulse($p1, $p2, $p3, $p4) returned $ret")
        return ret >= 0
    }

    // ------------------------------------ the worker ------------------------------------

    private fun ensureWorker() {
        if (workerStarted.getAndSet(true)) return
        val t = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
                // Priority is an optimization, never a requirement.
            }
            while (true) {
                val job = jobs.take()
                trainRunning = true
                runTrain(job)
                trainRunning = false
            }
        }, "PulseWorker")
        t.isDaemon = true
        t.start()
    }

    /** Runs one full train on the worker thread by driving digital_out_set_value directly -
     * EXACT pulse count, EXACT polarity, deadline-based timing. The vendor's own
     * digital_out_pulse was tried and produced the wrong count and ignored the direction on
     * real hardware (its parameter semantics are undocumented), so the engine owns every edge
     * itself: active level for width ms, idle level for (period - width) ms, count times.
     * Everything inside the loop is pin writes and waits ONLY; the result line is reported
     * after the last edge, never during. The result is handed back through the job's latch,
     * so [sendPulse] can return true/false. */
    private fun runTrain(job: PulseJob) {
        val io = digitalIO
        if (io == null) {
            job.done.countDown()
            return
        }
        val active = if (isHighPulse) 1 else 0
        val idle = if (isHighPulse) 0 else 1
        val gapMs = job.periodMs - job.widthMs
        var sent = 0
        var lastRet = 0
        try {
            var nextEdgeNanos = System.nanoTime()
            for (i in 0 until job.count) {
                lastRet = io.digital_out_set_value(active)
                if (lastRet < 0) break
                nextEdgeNanos += job.widthMs * 1_000_000L
                preciseWaitUntil(nextEdgeNanos)
                lastRet = io.digital_out_set_value(idle)
                if (lastRet < 0) break
                sent++
                if (i < job.count - 1) {
                    nextEdgeNanos += gapMs * 1_000_000L
                    preciseWaitUntil(nextEdgeNanos)
                }
            }
        } catch (t: Throwable) {
            // Best effort: never leave the line stuck at the active level.
            try { io.digital_out_set_value(idle) } catch (_: Throwable) {}
            job.ok = false
            job.done.countDown()
            HardwareLib.reportLine("[pulse] train aborted after $sent/${job.count}: ${t.javaClass.simpleName}")
            return
        }
        job.ok = sent == job.count
        job.done.countDown()
        HardwareLib.reportLine(
            if (job.ok) {
                "[pulse] sent $sent pulse(s) width=${job.widthMs}ms period=${job.periodMs}ms " +
                    "mode=${if (isHighPulse) "HIGH (active=1)" else "LOW (active=0)"}"
            } else {
                "[pulse] FAILED after $sent/${job.count} pulse(s) - digital_out_set_value returned $lastRet"
            }
        )
    }

    /** Deadline-based wait: sleeps the bulk, spins the last ~2 ms - a plain Android sleep
     * oversleeps 10-20 ms, exactly the width error the machine rejects. Deadlines accumulate
     * from the train's start, so period error never adds up across a long train. */
    private fun preciseWaitUntil(deadlineNanos: Long) {
        while (true) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > 2_000_000L) {
                Thread.sleep(remaining / 1_000_000L - 1)
            } else {
                Thread.yield()
            }
        }
    }
}
