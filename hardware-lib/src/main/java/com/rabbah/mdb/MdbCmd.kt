package com.rabbah.mdb

/**
 * The CMD code schema: every exchange the engine can handle has ONE stable integer [code],
 * pairing what the VMC sent ([received]) with what this device answers ([reply]).
 *
 * This is the library's public vocabulary. [HardwareLib.exchangeListener] delivers one
 * [MdbExchangeEvent] per bus exchange carrying the matched entry, so a host app can react
 * numerically ("code 4 happened") without ever parsing MDB bytes itself:
 *
 *   111 = RESET            -> ACK
 *   112 = POLL             -> JUST RESET
 *   113 = SETUP CONFIG     -> READER CONFIG DATA   (reply hex editable, see [HardwareLib.setReplyHex])
 *   114 = EXPANSION REQ ID -> PERIPHERAL ID        (reply hex editable; the VMC's own ID payload is
 *                                                 captured, see [HardwareLib.lastReceivedHex])
 *   ... and so on for every exchange (full table below).
 *
 * APPEND-ONLY: codes are part of the public contract. Add new entries with fresh numbers,
 * never renumber or reuse.
 *
 * [logEventName] names the matching entry of the Rabbah MDB codebook (mqtt-lib's MdbLogEvent),
 * so a transport bridge can forward exchanges to the dashboard with a plain valueOf() and the
 * wire format stays byte-identical to when the MQTT code lived inside this library.
 */
enum class MdbCmd(
    val code: Int,
    val received: String,
    val reply: String,
    val logEventName: String,
    val template: String,
    /** True when the log's {0} slot carries the RAW FRAME HEX instead of the command name -
     * the frames whose payload bytes are the information (SETUP, EXPANSION, REVALUE, unknown). */
    val rxShowsHex: Boolean = false
) {
    /** Fallback for an exchange no other entry matches (should not happen in practice). */
    GENERIC(110, "any", "any", "MDB_EXCHANGE", "EXCHANGE rx={0} tx={1}", rxShowsHex = true),

    /** 10h from any state: bus reset. Re-arms the JUST RESET announcement. */
    RESET_ACK(111, "RESET (10)", "ACK", "MDB_RESET", "RESET rx={0} tx={1}"),

    /** 12h after a reset: we announce JUST RESET (payload editable as config JUST_RESET). */
    POLL_JUST_RESET(112, "POLL (12)", "JUST RESET", "MDB_POLL_JUST_RESET", "POLL (JUST RESET) rx={0} tx={1}"),

    /** 11h 00h: VMC config arrives, we answer READER CONFIG DATA (config READER_CONFIG_DATA). */
    SETUP_READER_CONFIG(113, "SETUP CONFIG (11 00)", "READER CONFIG DATA", "MDB_SETUP_CONFIG", "SETUP CONFIG rx={0} tx={1} level={2}", rxShowsHex = true),

    /** 17h 00h: VMC requests our ID, we answer PERIPHERAL ID (config READER_CONFIG_INFO /
     *  READER_CONFIG_INFO_L3). The VMC's own request payload is captured per-code. */
    EXPANSION_PERIPHERAL_ID(114, "EXPANSION REQUEST ID (17 00)", "PERIPHERAL ID", "MDB_EXPANSION_REQUEST_ID", "EXPANSION REQUEST ID rx={0} tx={1}", rxShowsHex = true),

    /** 11h 01h: max/min prices from the VMC - captured, answered with ACK. */
    SETUP_PRICES_ACK(115, "SETUP MAX/MIN PRICES (11 01)", "ACK", "MDB_SETUP_PRICES", "SETUP MAX/MIN PRICES rx={0} tx={1}", rxShowsHex = true),

    /** 17h 04h (Level 3): optional feature bits from the VMC - captured, answered with ACK. */
    EXPANSION_OPTIONS_ACK(116, "EXPANSION ENABLE OPTIONS (17 04)", "ACK", "MDB_EXPANSION_ENABLE_OPTIONS", "EXPANSION ENABLE OPTIONS rx={0} tx={1}", rxShowsHex = true),

    /** 14h 01h: reader enabled - selling can start. */
    READER_ENABLE_ACK(117, "READER ENABLE (14 01)", "ACK", "MDB_READER_ENABLE", "READER ENABLE rx={0} tx={1}"),

    /** 14h 00h: reader disabled. */
    READER_DISABLE_ACK(118, "READER DISABLE (14 00)", "ACK", "MDB_READER_DISABLE", "READER DISABLE rx={0} tx={1}"),

    /** 14h 02h: reader cancel - answered CANCELLED (config CAN). */
    READER_CANCEL_CANCELLED(119, "READER CANCEL (14 02)", "CANCELLED", "MDB_READER_CANCEL", "READER CANCEL rx={0} tx={1}"),

    /** 12h while a session is armed: we answer BEGIN SESSION (config SESSION_BEGIN /
     *  SESSION_BEGIN_L2, 1-35 bytes). */
    POLL_BEGIN_SESSION(120, "POLL (12)", "BEGIN SESSION", "MDB_POLL_SESSION_BEGIN", "POLL (SESSION BEGIN) rx={0} tx={1}"),

    /** 13h 00h: customer picked an item - price/item captured, ACKed, decision comes later. */
    VEND_REQUEST_ACK(121, "VEND REQUEST (13 00)", "ACK", "MDB_VEND_REQUEST", "VEND REQUEST rx={0} tx={1} price={2} item={3}"),

    /** 12h after approveVend(): we answer VEND APPROVED (config VEND_APPROVED). */
    POLL_VEND_APPROVED(122, "POLL (12)", "VEND APPROVED", "MDB_POLL_VEND_APPROVED", "POLL (VEND APPROVED) rx={0} tx={1}"),

    /** 12h after a deny: we answer VEND DENIED (config VEND_DENIED). */
    POLL_VEND_DENIED(123, "POLL (12)", "VEND DENIED", "MDB_POLL_VEND_DENIED", "POLL (VEND DENIED) rx={0} tx={1}"),

    /** 13h 02h: product dispensed - settle the payment. */
    VEND_SUCCESS_ACK(124, "VEND SUCCESS (13 02)", "ACK", "MDB_VEND_SUCCESS", "VEND SUCCESS rx={0} tx={1} item={2}"),

    /** 13h 03h: product did NOT dispense - refund the payment. */
    VEND_FAILURE_ACK(125, "VEND FAILURE (13 03)", "ACK", "MDB_VEND_FAILURE", "VEND FAILURE rx={0} tx={1}"),

    /** 13h 04h: VMC says the session is over - END SESSION goes out on the next POLL. */
    SESSION_COMPLETE_ACK(126, "VEND SESSION COMPLETE (13 04)", "ACK", "MDB_SESSION_COMPLETE", "SESSION COMPLETE rx={0} tx={1}"),

    /** 12h after SESSION COMPLETE: we answer END SESSION (config END_SESSION). */
    POLL_END_SESSION(127, "POLL (12)", "END SESSION", "MDB_POLL_END_SESSION", "POLL (END SESSION) rx={0} tx={1}"),

    /** 13h 01h: the VMC cancels the pending vend - ACKed, the cancel reply follows on a POLL. */
    VEND_CANCEL_ACK(128, "VEND CANCEL (13 01)", "ACK", "MDB_VEND_CANCEL", "VEND CANCEL rx={0} tx={1}"),

    /** 12h after a cancel: we answer SESSION CANCEL REQUEST (config SESSION_CANCEL). */
    POLL_SESSION_CANCEL(129, "POLL (12)", "SESSION CANCEL REQUEST", "MDB_POLL_SESSION_CANCEL", "POLL (SESSION CANCEL REQUEST) rx={0} tx={1}"),

    /** 15h 00h (Level 2+): VMC wants to credit funds - always denied (config REVALUE_DENIED). */
    REVALUE_REQUEST_DENIED(130, "REVALUE REQUEST (15 00)", "REVALUE DENIED", "MDB_REVALUE_REQUEST", "REVALUE REQUEST rx={0} tx={1}", rxShowsHex = true),

    /** 15h 01h (Level 2+): we answer the revalue limit (config REVALUE_LIMIT). */
    REVALUE_LIMIT_REPLY(131, "REVALUE LIMIT REQUEST (15 01)", "REVALUE LIMIT AMOUNT", "MDB_REVALUE_LIMIT_REQUEST", "REVALUE LIMIT REQUEST rx={0} tx={1}", rxShowsHex = true),

    /** 13h 05h: cash sale report from the VMC - logged, ACKed. */
    CASH_SALE_ACK(132, "CASH SALE (13 05)", "ACK", "MDB_CASH_SALE", "CASH SALE rx={0} tx={1} price={2} item={3}"),

    /** 12h with nothing pending: the idle heartbeat, suppressed from logs by default. */
    POLL_ACK(133, "POLL (12)", "ACK (idle)", "MDB_POLL", "POLL rx={0} tx={1}"),

    /** 17h with an unrecognized subcommand. */
    EXPANSION_OTHER(134, "EXPANSION (17 xx)", "(varies)", "MDB_EXPANSION_OTHER", "EXPANSION rx={0} tx={1}", rxShowsHex = true),

    /** Traffic for the coin changer / bill validator / anything outside 10h-17h - never answered. */
    OTHER_PERIPHERAL(135, "non-cashless address", "(ignored)", "MDB_OTHER_PERIPHERAL", "OTHER PERIPHERAL rx={0} tx={1}", rxShowsHex = true),

    /** Addressed to us but not recognized in the current state - never answered. */
    UNHANDLED(136, "unrecognized in current state", "(none)", "MDB_UNHANDLED", "UNHANDLED rx={0} tx={1}", rxShowsHex = true);

    companion object {
        /** The entry for [code], or null for a number this build does not know. */
        @JvmStatic
        fun fromCode(code: Int): MdbCmd? = values().firstOrNull { it.code == code }
    }
}

/**
 * One complete bus exchange, delivered to [HardwareLib.exchangeListener] on the offload
 * thread (never the bus thread). Everything a transport or UI needs is on it:
 *
 *  - [cmd]/[code]: the CMD schema entry (see [MdbCmd]) - react numerically.
 *  - [rxHex]: the exact frame the VMC sent, as hex ("11 00 03 10 ...").
 *  - [txName]: the engine's name for what it replied ("ACK", "READER CONFIG DATA", ...).
 *  - [params]: positional values, p[0]=received command NAME ("POLL", "RESET", ... - raw hex
 *    only for unknown frames) p[1]=txName plus extras (price, item, level) -
 *    the exact "p" array of the Rabbah MDB codebook, ready for RabbahLog.log().
 *  - [message]: the human sentence ([MdbCmd.template] filled with [params]) - ready for a screen.
 *  - [sessionId]: correlation id, non-null from BEGIN SESSION through END SESSION.
 *  - [publishRemote]: whether remote log publishing was enabled when this fired - a transport
 *    bridge should forward the exchange only when true (local listeners always fire).
 *  - [showOnScreen]: false for high-volume suppressible traffic (idle POLLs, unhandled).
 */
data class MdbExchangeEvent(
    val cmd: MdbCmd,
    val rxHex: String,
    val txName: String,
    val params: List<String>,
    val message: String,
    val sessionId: String?,
    val publishRemote: Boolean,
    val showOnScreen: Boolean,
    val timestampMs: Long
) {
    val code: Int get() = cmd.code
    val logEventName: String get() = cmd.logEventName
}
