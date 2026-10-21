package com.spizganed.quickbuds.bluetooth

import kotlin.math.abs

/**
 * Left, right and case battery from the Google Fast Pair advertisement (service data UUID 0xFE2C), for earbuds of
 * any brand that support Fast Pair. The format is Google's published Fast Pair specification ("Advertising when
 * not discoverable", "Battery notification"); nothing here is guessed:
 *
 *     00                      version and flags
 *     LT <L bytes>            account key filter (T 0 show UI, 2 hide UI)
 *     LT <L bytes>            salt (T 1)
 *     3T <3 bytes>            battery (T 3 show UI, 4 hide UI): left, right, case
 *
 * Each battery byte: bit 7 charging, bits 0-6 the level 0-100; 0x7F means unknown.
 *
 * The advertisement does not say WHOSE earbuds sent it: that is hidden in the account key filter, which only
 * Google Play services can test. So [Picker] trusts a broadcast only when its checks leave one that fits.
 */
object FastPairBattery {

    /**
     * Whether to listen at all: one other device connected ([trackedKinds]), earbuds or of unknown kind (a neckband,
     * headphones or a speaker has no left, right or case), the card on. Supported buds connected next to it are no
     * reason to stop: [Picker] sets their own broadcast aside by their levels. Was: no listening at all then.
     */
    fun allowed(trackedKinds: List<DeviceCatalog.Kind?>, cardOn: Boolean): Boolean {
        // Not singleOrNull(): an unknown kind is null too.
        if (trackedKinds.size != 1) return false
        val only = trackedKinds[0]
        return (only == null || only == DeviceCatalog.Kind.TWS) && cardOn
    }

    /** Levels 0-100, -1 when the earbuds do not report that part. */
    data class Levels(
        val left: Int, val right: Int, val case: Int,
        val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean
    ) {
        val any: Boolean get() = left >= 0 || right >= 0 || case >= 0
    }

    /** The battery part of a 0xFE2C service data block, or null when it has none (or is malformed). */
    fun parse(data: ByteArray?): Levels? {
        if (data == null || data.size < 2) return null
        // Version 0 only. A 3-byte block is the discoverable form (a model id), which carries no battery.
        if ((data[0].toInt() and 0xF0) != 0) return null
        var i = 1
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            val length = header shr 4
            val type = header and 0x0F
            if (i + 1 + length > data.size) return null
            if ((type == 3 || type == 4) && length == 3) return levels(data.copyOfRange(i + 1, i + 4))
            i += 1 + length
        }
        return null
    }

    /** Three battery bytes (left, right, case), as in the advertisement and the Message Stream ([FastPairStream]). */
    fun levels(b: ByteArray): Levels? {
        if (b.size != 3) return null
        fun level(b: Byte) = (b.toInt() and 0x7F).let { if (it > 100) -1 else it }
        fun charging(b: Byte) = (b.toInt() and 0x80) != 0 && (b.toInt() and 0x7F) <= 100
        return Levels(level(b[0]), level(b[1]), level(b[2]), charging(b[0]), charging(b[1]), charging(b[2])).takeIf { it.any }
    }

    /**
     * The safeguards. A broadcast is used only when:
     *  - it is strong ([minRssi] dBm or more): the connected earbuds are close to the phone;
     *  - it reports a bud, and either the other bud or the case: a lone bud level could be a neckband's;
     *  - it is not the supported buds' own broadcast (QuickBuds reads their exact levels: [own]);
     *  - the connected earbuds have reported their own standard level, and a reported bud is within [tolerance]
     *    of it. Without that level there is nothing to check the broadcast against: it is not used;
     *  - one broadcast is left after these checks, or one is [clearMarginDb] stronger than every other.
     * Otherwise [pick] returns null and the card keeps the single standard level. [decide] also says why.
     *
     * Was: both buds required (a pair with one bud in the case, "L=-1 R=70 C=14", showed nothing), and any second
     * Fast Pair broadcast nearby, the supported buds' too, stopped it. Before that: the cross-check only when a
     * standard level was known, and one side was enough; a neckband was given another device's "R=70".
     */
    class Picker(
        private val windowMs: Long = 30_000,
        private val minRssi: Int = -75,
        private val tolerance: Int = 15,
        private val clearMarginDb: Int = 10
    ) {
        private class Heard(val rssi: Int, val levels: Levels, val atMs: Long)

        private val heard = HashMap<String, Heard>()

        /** One advertisement: [address] is the advertiser's (rotating) BLE address. */
        fun onAdvert(address: String, rssi: Int, levels: Levels, nowMs: Long) {
            heard[address] = Heard(rssi, levels, nowMs)
        }

        /** The levels to show (or null) and why, for the log. */
        data class Verdict(val levels: Levels?, val reason: String)

        fun pick(nowMs: Long, standardLevel: Int?, own: Levels? = null): Levels? = decide(nowMs, standardLevel, own).levels

        fun decide(nowMs: Long, standardLevel: Int?, own: Levels? = null): Verdict {
            heard.values.removeAll { nowMs - it.atMs > windowMs }
            if (heard.isEmpty()) return Verdict(null, "no broadcast heard")
            if (standardLevel == null || standardLevel <= 0) return Verdict(null, "no standard level to check against")
            val ok = heard.values.filter { h ->
                val l = h.levels
                val buds = listOf(l.left, l.right).filter { it >= 0 }
                h.rssi >= minRssi && buds.isNotEmpty() && (buds.size == 2 || l.case >= 0) &&
                    !isOwn(l, own) && buds.any { abs(it - standardLevel) <= tolerance }
            }.sortedByDescending { it.rssi }
            val heardText = heard.values.joinToString(" ") { "[${it.rssi} dBm L=${it.levels.left} R=${it.levels.right} C=${it.levels.case}]" }
            return when {
                ok.isEmpty() -> Verdict(null, "none fits (standard $standardLevel): $heardText")
                ok.size == 1 || ok[0].rssi - ok[1].rssi >= clearMarginDb -> Verdict(ok[0].levels, "used ${ok[0].rssi} dBm of: $heardText")
                else -> Verdict(null, "${ok.size} fit, none clearly closest: $heardText")
            }
        }

        /** The supported buds' own broadcast: both buds within 1 of what they report to QuickBuds. */
        private fun isOwn(l: Levels, own: Levels?): Boolean {
            if (own == null || own.left < 0 || own.right < 0) return false
            return l.left >= 0 && l.right >= 0 && abs(l.left - own.left) <= 1 && abs(l.right - own.right) <= 1
        }

        fun clear() = heard.clear()
    }
}
