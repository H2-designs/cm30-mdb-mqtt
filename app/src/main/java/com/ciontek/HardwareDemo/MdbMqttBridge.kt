package com.ciontek.HardwareDemo

import com.rabbah.mdb.HardwareLib

/**
 * The app-side bridge between hardware-lib and mqtt-lib - since hardware-lib 8.2.1 a thin wrapper.
 *
 * HardwareLib.attachRabbahMqtt() does the whole job by itself: remote commands in (via MqttLib's
 * listener chain), every MDB exchange out as ONE coded RabbahLog item, RS232 exchanges out as coded
 * items, plain report lines and the control snapshots (SETTINGS_JSON / CONFIG_JSON / VMC_STATUS)
 * out as before. It is idempotent: calling attach() ten times registers nothing twice, and the
 * dashboard's HW toggle (attachHardware / detachHardware) drives the same pair.
 *
 * Do NOT add your own exchange / log forwarders next to this - every extra publisher is one more
 * copy of every line on the backend (RabbahLog drops exact duplicates within 30 ms, but do not rely
 * on it). If you need a custom transport, use HardwareLib.attachLogSink INSTEAD of attachRabbahMqtt,
 * never both.
 */
object MdbMqttBridge {
    /** Wire hardware-lib to mqtt-lib. Call once (idempotent). */
    fun attach(): Boolean = HardwareLib.attachRabbahMqtt()

    /** The true disconnect - removes every listener attach() registered. */
    fun detach(): Boolean = HardwareLib.detachRabbahMqtt()
}
