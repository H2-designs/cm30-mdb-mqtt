package com.rabbah.mdb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central, persisted, remotely-editable store for every MDB response payload this device sends
 * (READER CONFIG DATA, PERIPHERAL ID, BEGIN SESSION, REVALUE LIMIT, ...). The transport reads the
 * current bytes from here fresh on every send instead of hardcoding them, so any payload can be
 * changed live over MQTT without a rebuild, and the change survives app restarts (backed by
 * SharedPreferences).
 *
 * Array LENGTH is fixed per name, matching the defaults below - only byte VALUES are editable.
 * This is deliberate: several arrays have specific indices overwritten at runtime (price bytes,
 * feature level, revalue limit, ...), and letting the length drift would silently break those
 * without the compiler ever catching it. The BEGIN SESSION payloads are the exception (see
 * [variableLength]): no runtime index writes touch them, and real machines disagree about what
 * they want - some feature-level-2 VMCs only accept the short Level-1-style 3-byte form - so
 * their length is freely editable (1..35 bytes).
 *
 * EVERYTHING about configs is handled inside this class: parsing the JSON the dashboard sends,
 * validating names and lengths, persisting, and producing the ack lines and snapshot the
 * dashboard needs back. See [applyJson].
 */
object MdbConfigStore {

    private const val PREFS_NAME = "mdb_config"
    private lateinit var prefs: SharedPreferences

    private val defaults: LinkedHashMap<String, ByteArray> = linkedMapOf(
        "JUST_RESET" to byteArrayOf(0x00),
        "CAN" to byteArrayOf(0x08),
        "READER_CONFIG_DATA" to byteArrayOf(0x01, 0x02, 0x19, 0x78, 0x01, 0x02, 0xE8.toByte(), 0x0B),
        "READER_CONFIG_INFO" to byteArrayOf(
            0x09, 0x43, 0x41, 0x53, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x31, 0x00, 0x01
        ),
        // Same PERIPHERAL ID as above, plus the 4 extra "optional feature bits" bytes (Z31-Z34)
        // that only exist at Level 3 - all zero, meaning no optional feature (FTL, 32-bit
        // currency, remote vend, basket, coupons, Always Idle, ...) is claimed. Declaring zero
        // support here is what tells the VMC not to expect any of them. Bit 5 of the LAST byte
        // (Z34) is "Always Idle" - the engine reads it live from here, no separate setting.
        "READER_CONFIG_INFO_L3" to byteArrayOf(
            0x09, 0x43, 0x41, 0x53, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x31,
            0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
            0x30, 0x30, 0x30, 0x31, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
        ),
        "VEND_APPROVED" to byteArrayOf(0x05, 0x00, 0x00),
        "VEND_DENIED" to byteArrayOf(0x06),
        "END_SESSION" to byteArrayOf(0x07),
        "SESSION_CANCEL" to byteArrayOf(0x04),
        "SESSION_BEGIN" to byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte()),
        // Level 2/3 Standard BEGIN SESSION per spec: code + funds hi/lo + 4-byte Payment Media ID
        // + Payment Type + 2-byte Payment Data = 10 data bytes (CHK is computed by the transport,
        // not stored here). Kept separate from SESSION_BEGIN so switching the MDB level back and
        // forth never loses either payload's values.
        "SESSION_BEGIN_L2" to byteArrayOf(
            0x03, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00
        ),
        "REVALUE_LIMIT" to byteArrayOf(0x0F, 0xFF.toByte(), 0xFF.toByte()),
        // Answer to a REVALUE REQUEST (15H 00H). This device never credits funds onto payment
        // media, so the default is REVALUE DENIED (0x0E).
        "REVALUE_DENIED" to byteArrayOf(0x0E)
    )

    /** Names whose LENGTH is editable too, not just the byte values. Only payloads that no
     * runtime code indexes into may be listed here. MDB's max block is 36 bytes on the wire,
     * so 35 data bytes + the computed CHK is the ceiling. */
    private val variableLength = setOf("SESSION_BEGIN", "SESSION_BEGIN_L2")
    private const val MAX_VARIABLE_BYTES = 35

    private val current = linkedMapOf<String, ByteArray>()
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for ((name, default) in defaults) {
            val saved = prefs.getString(name, null)
            val parsed = saved?.let { parseHex(it) }
            val lengthOk = parsed != null &&
                if (name in variableLength) parsed.size in 1..MAX_VARIABLE_BYTES
                else parsed.size == default.size
            current[name] = if (parsed != null && lengthOk) parsed else default.copyOf()
        }
    }

    fun names(): List<String> = defaults.keys.toList()

    fun expectedLength(name: String): Int = defaults.getValue(name).size

    /** Returns a fresh copy - callers are free to mutate specific indices (price, level, limit,
     * ...) without corrupting the stored config. */
    fun get(name: String): ByteArray = (current[name] ?: defaults.getValue(name)).copyOf()

    /**
     * Parses [hex] (tolerant of spaces, commas, "0x" prefixes - anything non-hex is stripped)
     * and applies it to [name] only if it decodes to exactly the expected number of bytes - or,
     * for the [variableLength] payloads (BEGIN SESSION), to ANY length from 1 to 35 bytes, so a
     * machine that wants the short Level-1-style form can be given exactly that.
     * Persists the change across restarts. Returns null on success, or a human-readable error.
     */
    fun set(name: String, hex: String): String? {
        val expected = defaults[name] ?: return "Unknown config: $name"
        val bytes = parseHex(hex) ?: return "Invalid hex string: $hex"
        if (name in variableLength) {
            if (bytes.size !in 1..MAX_VARIABLE_BYTES) {
                return "Wrong length for $name: got ${bytes.size} byte(s), allowed 1-$MAX_VARIABLE_BYTES"
            }
        } else if (bytes.size != expected.size) {
            return "Wrong length for $name: got ${bytes.size} byte(s), need ${expected.size}"
        }
        current[name] = bytes
        prefs.edit().putString(name, toHex(bytes)).apply()
        return null
    }

    fun resetToDefault(name: String) {
        val default = defaults[name] ?: return
        current[name] = default.copyOf()
        prefs.edit().remove(name).apply()
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private fun parseHex(input: String): ByteArray? {
        val clean = input.replace(Regex("0x", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Every current config as a JSON object of name -> hex string, for sending a full snapshot
     * to the dashboard (the "CONFIG_JSON:" tagged message). */
    fun snapshotJson(): String {
        val obj = JSONObject()
        for (name in defaults.keys) {
            obj.put(name, toHex(current[name] ?: defaults.getValue(name)))
        }
        return obj.toString()
    }

    /** Result of [applyJson]: whether the JSON was a config message at all, and the per-name ack
     * lines to report back (in the exact wording the dashboard's Config panel matches on). */
    class ApplyResult(val handled: Boolean, val ackLines: List<String>)

    /**
     * The single entry point for configs arriving over MQTT as JSON. Accepts:
     *
     *   { "setConfig": { "NAME": "hex bytes", ... } }   - bulk or single update
     *   { "resetConfig": ["NAME", ...] }                - reset names to library defaults
     *   { "getConfig": true }                           - no changes; caller sends the snapshot
     *
     * Any combination of those keys in one object works. Everything is validated per name - one
     * bad value never blocks the rest - and every outcome produces an ack line. Returns
     * handled=false (with no side effects) if the string is not JSON or has none of these keys,
     * so the caller can pass unrecognized messages further along the command chain.
     */
    fun applyJson(json: String): ApplyResult {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return ApplyResult(handled = false, ackLines = emptyList())
        }
        if (!obj.has("setConfig") && !obj.has("resetConfig") && !obj.has("getConfig")) {
            return ApplyResult(handled = false, ackLines = emptyList())
        }

        val acks = mutableListOf<String>()

        val setObj = obj.optJSONObject("setConfig")
        if (setObj != null) {
            for (name in setObj.keys()) {
                val hex = setObj.optString(name)
                val error = set(name, hex)
                acks += if (error != null) {
                    "[remote] setConfig $name failed: $error"
                } else {
                    "[remote] $name updated -> ${toHex(get(name))}"
                }
            }
        }

        val resetArr: JSONArray? = obj.optJSONArray("resetConfig")
        if (resetArr != null) {
            for (i in 0 until resetArr.length()) {
                val name = resetArr.optString(i)
                if (name !in names()) {
                    acks += "[remote] resetConfig: unknown config $name"
                } else {
                    resetToDefault(name)
                    acks += "[remote] $name reset to default -> ${toHex(get(name))}"
                }
            }
        }

        return ApplyResult(handled = true, ackLines = acks)
    }
}
