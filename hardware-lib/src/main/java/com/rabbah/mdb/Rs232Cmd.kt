package com.rabbah.mdb

/**
 * The RS232 event schema as stable integer codes - the RS232 twin of [MdbCmd], so an app can
 * process RS232 events with numeric codes exactly like MDB exchanges (e.code 110-136).
 *
 * Codes 137-143 continue the MdbCmd numbering and are IDENTICAL to the wire log codes the
 * mqtt-lib codebook serves (schema "RS232") - one code space end to end. The enum entry NAMES
 * are exactly the eventName strings [HardwareLib.addRs232EventListener] delivers, so mapping a
 * listener callback to its code is one lookup:
 *
 *   HardwareLib.addRs232EventListener { name, params ->
 *       val cmd = Rs232Cmd.fromEventName(name) ?: return@addRs232EventListener
 *       myLogger.emit(messageCode = cmd.code.toString(), params = params,
 *                     localMessage = cmd.format(params))
 *   }
 *
 * APPEND-ONLY: add new events at the bottom with the next code (144+). Never insert, never
 * renumber - and keep this table in lockstep with mqtt-lib's Rs232LogEvent (same codes, same
 * templates), which is what getCodebook serves to dashboards and the backend.
 */
enum class Rs232Cmd(
    val code: Int,
    val template: String,
    val paramCount: Int
) {
    /** p = [ruleName, rxHex, txHex or "-", price or "-1"]. A payment is this code with
     *  p[3] != "-1" (price in minor units). */
    RS232_RX_MATCHED(137, "RS232 {0} rx={1} tx={2} price={3}", 4),

    /** p = [rxHex] - frame matched no rule, nothing was replied (ships with severity "e"). */
    RS232_RX_UNMATCHED(138, "RS232 UNMATCHED rx={0}", 1),

    /** p = [rxHex, expectedCrcHex] - bad XOR CRC, frame dropped (ships with severity "e"). */
    RS232_CRC_DISCARDED(139, "RS232 CRC FAIL rx={0} expected={1} - discarded", 2),

    /** p = [txHex] - manual sendHex / sendXor* writes (rule auto-replies ride RX_MATCHED). */
    RS232_TX(140, "RS232 TX {0}", 1),

    /** p = [portParams e.g. "baud=9600 8N1", ruleCount]. */
    RS232_PORT_OPEN(141, "RS232 port open {0}, {1} rule(s) active", 2),

    RS232_PORT_CLOSED(142, "RS232 port closed", 0),

    /** p = [ruleCount]. */
    RS232_RULES_LOADED(143, "RS232 {0} rule(s) loaded", 1);

    /** The exact string [HardwareLib.addRs232EventListener] delivers for this event. */
    val eventName: String get() = name

    /** Renders the human sentence the same way the dashboard does: {n} slots from [params]. */
    fun format(params: List<String>): String {
        var out = template
        params.forEachIndexed { i, p -> out = out.replace("{$i}", p) }
        return out
    }

    companion object {
        /** Listener-callback name -> schema entry; null for names this build doesn't know
         *  (a newer library on the other side) - skip or log raw in that case. */
        @JvmStatic
        fun fromEventName(eventName: String): Rs232Cmd? =
            values().firstOrNull { it.name == eventName }

        /** Wire code -> schema entry (137-143); null for unknown codes. */
        @JvmStatic
        fun fromCode(code: Int): Rs232Cmd? = values().firstOrNull { it.code == code }
    }
}
