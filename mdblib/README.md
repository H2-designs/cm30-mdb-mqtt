# MdbSlave Library (`mdblib`)

Drop-in Android library for the CM30 MDB Slave (Cashless Device #1, address `0x10`). It runs the
entire MDB protocol state machine internally — `RESET → SETUP → EXPANSION → READER ENABLE →
BEGIN SESSION → VEND REQUEST → VEND APPROVED/DENIED → VEND SUCCESS/FAILURE → SESSION COMPLETE →
END SESSION` — so your app never has to touch raw MDB bytes, checksums, or protocol timing.

You only ever call **4 things**. Everything else is private to the library.

---

## 1. Add the dependency

**Option A — module dependency** (this repo):
```gradle
// settings.gradle
include ':mdblib'

// app/build.gradle
dependencies {
    implementation project(':mdblib')
}
```

**Option B — the standalone AAR** (`mdblib-release.aar`), if you're integrating into a different
project:
```gradle
repositories {
    flatDir { dirs 'libs' }   // put mdblib-release.aar in your app's libs/ folder
}
dependencies {
    implementation(name: 'mdblib-release', ext: 'aar')
}
```

`minSdkVersion 30`+. Requires the real CM30 hardware — the library's native transport only works
on an actual CM30 device (it depends on `android.hardware.mdbSlave.MdbSlave`, provided by the
vendor's `CM30-HardwareLibrary` `.aar`, bundled inside `mdblib`).

---

## 2. The API — `com.ciontek.mdblib.MdbSlave`

Everything is a single object (`MdbSlave`), no instantiation needed.

### `initMDB()` — start it

```kotlin
val started: Boolean = MdbSlave.initMDB()
```
Opens the MDB slave port and starts a background thread that runs the whole protocol
automatically. Call this once, typically in your Activity/Service's `onCreate()` or wherever your
app is ready to start accepting cards.

- Returns `false` if the port failed to open (e.g. hardware not present/ready). You can retry by
  calling it again.
- Calling it again while already running is a safe no-op (returns `true`).
- You do **not** need to call anything else to keep the protocol going — RESET, POLL, SETUP,
  EXPANSION, READER ENABLE, and repeated sessions all happen automatically inside the library.

### `vendRequestListener` — approve or deny a vend

```kotlin
MdbSlave.vendRequestListener = MdbSlave.VendRequestListener { priceHigh, priceLow ->
    val price = MdbSlave.combinePrice(priceHigh, priceLow)   // real currency amount, e.g. 10.00
    // Decide right here — check a local balance, call your payment gateway, etc.
    val approve = checkCustomerCanAfford(price)
    approve   // true = approve the vend, false = deny it
}
```

- Called **synchronously**, on the library's internal background thread, the instant a
  `VEND REQUEST` arrives from the VMC. Return your decision as fast as you can — the VMC is
  waiting on a timely reply, so don't do slow I/O (network calls, etc.) directly inside this
  callback; if you need to check something remote, decide with cached/local data here and reflect
  the real outcome later if needed.
- If you never set this (`vendRequestListener == null`), every vend request is **denied by
  default** — the library never approves a vend for you without an explicit decision.
- `priceHigh`/`priceLow` are the raw MDB price bytes (0–255 each) — this is the price your
  payment gateway needs. Use `MdbSlave.combinePrice(priceHigh, priceLow)` to convert them into a
  real currency amount (e.g. `10.00`) instead of doing the byte math yourself:
  ```kotlin
  val amount: Double = MdbSlave.combinePrice(priceHigh, priceLow)
  ```
  This applies the scale factor/decimal places this device declares to the VMC
  (`MdbSlave.PRICE_SCALE_FACTOR`, `MdbSlave.PRICE_DECIMAL_PLACES` — currently `1` and `2`).

### `fetchVendState()` — check what happened

```kotlin
when (MdbSlave.fetchVendState()) {
    MdbSlave.VEND_STATE_IN_PROCESS -> {
        // 1 — your listener approved a vend; the VMC is now dispensing the product.
    }
    MdbSlave.VEND_STATE_SUCCESS -> {
        // 2 — the VMC confirmed the product was dispensed. Finalize the transaction.
    }
    MdbSlave.VEND_STATE_FAILURE -> {
        // 3 — the VMC reported the product did NOT dispense. Refund initiated automatically
        // at the MDB protocol level; update your own records accordingly.
    }
    null -> {
        // Nothing new since the last time you called this.
    }
}
```

- **Read-once**: calling this clears the value back to `null` immediately, so poll it (e.g. from a
  timer, or right after you know a vend was approved) rather than expecting it to "stay" set.
- Values only ever change in this order per vend cycle: `null → IN_PROCESS → SUCCESS` or
  `null → IN_PROCESS → FAILURE`. It won't jump straight to `SUCCESS`/`FAILURE` without
  `IN_PROCESS` first.

### `forceReset()` — hard reset

```kotlin
val restarted: Boolean = MdbSlave.forceReset()
```
Closes the MDB port and immediately reopens it, restarting the whole state machine from scratch —
the automated equivalent of pressing "close" then "open". Use this if the reader gets into a
state you want to forcibly clear (e.g. after a prolonged error, or as a manual "reset" button in
your own UI). Returns the same as `initMDB()`.

---

## Full example

```kotlin
class MyVendingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MdbSlave.vendRequestListener = MdbSlave.VendRequestListener { priceHigh, priceLow ->
            walletBalance >= MdbSlave.combinePrice(priceHigh, priceLow)
        }

        if (!MdbSlave.initMDB()) {
            showError("Could not open MDB port")
        }

        // Poll for vend outcomes, e.g. every 500ms, or triggered by your own event loop.
        startVendStatePolling()
    }

    private fun startVendStatePolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                when (MdbSlave.fetchVendState()) {
                    MdbSlave.VEND_STATE_IN_PROCESS -> showDispensing()
                    MdbSlave.VEND_STATE_SUCCESS -> { deductBalance(); showSuccess() }
                    MdbSlave.VEND_STATE_FAILURE -> showFailure()
                    null -> {} // nothing new
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    fun onResetButtonPressed() {
        MdbSlave.forceReset()
    }
}
```

---

## What happens automatically (you don't need to call anything for these)

- RESET, SETUP, EXPANSION REQUEST ID, READER ENABLE are all ACK'd/replied to automatically.
- `BEGIN SESSION` auto-fires as soon as the reader is enabled, and again after every completed
  session — there's no real card-tap detection built in yet, so the library treats "reader
  enabled" as "ready for a session" continuously.
- `VEND CANCEL` (customer/VMC cancels before you respond) and `SESSION COMPLETE` → `END SESSION`
  are handled internally; you don't need to react to them beyond what `fetchVendState()` tells you.

## Known limitations

- No real card-tap event exists yet — session begin is time/state-driven, not tied to an actual
  customer presenting a card. If real card-tap detection is added later, it should replace the
  auto-arm logic inside the library, not require any change to your app's use of this API.
- `VEND_STATE_FAILURE` reflects the MDB-level ACK handshake for the refund request, not a
  confirmed real refund — there's no full refund-success/refund-failed distinction
  (`MALFUNCTION 0xC0-0xCF`) implemented yet.
