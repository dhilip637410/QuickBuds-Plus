package com.spizganed.quickbuds.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import java.net.URLEncoder

/**
 * The problem report, filled by [ReportActivity] and sent as a GitHub issue ([issueUrl]). The entry ids below are
 * upstream's Google Form fields; here they only key [body] and [preview].
 */
object ProblemReport {

    private const val ENTRY_MODEL = "entry.849813820"
    private const val ENTRY_CATEGORY = "entry.494675210"
    private const val ENTRY_DESCRIPTION = "entry.1786789589"
    private const val ENTRY_LOG = "entry.863055756"

    /** Google answers 413 somewhere between 31 and 41 KB of form body. */
    private const val MAX_BODY = 30_000

    /** The form's Category options, in the form's own words (it rejects any other value). */
    val CATEGORIES = listOf("UI", "Lag", "Connection", "Feature not working", "Battery", "Other")

    /** The log's first line: phone, Android, QuickBuds, buds model and firmware. Exported logs start with it too. */
    fun header(c: Context): String {
        val p = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val model = ModelCatalog.current(c)?.name ?: p.getString(ModelCatalog.KEY_DEVICE_NAME, null) ?: "unknown buds"
        val id = p.getString(Capabilities.KEY_PRODUCT_ID, null) ?: "?"
        val fw = p.getString(BudsConnectionManager.KEY_FIRMWARE, null) ?: "?"
        return "QuickBuds ${UpdateChecker.installed(c)} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | $model ($id) firmware $fw"
    }

    /** The log as sent: header, then the packet log without Bluetooth addresses or this phone's name. */
    fun log(c: Context): String {
        var text = header(c) + "\n" + PacketLogger.getFileContent().lines().joinToString("\n", transform = ::dropDeviceBytes)
        text = MAC.replace(text, "XX:XX:XX:XX:XX:XX")
        for (name in phoneNames(c)) text = text.replace(name, "[this phone]")
        return text
    }

    /**
     * A packet line whose bytes hold device addresses and names (PROTOCOL.md §9 device list and device manager:
     * `0x8112`, `0x0429`, `0x8132`, push `0x0204` sub `06`) keeps only its 9-byte header.
     */
    private fun dropDeviceBytes(line: String): String {
        val at = line.indexOf(": AA ")
        if (at < 0) return line
        val b = line.substring(at + 2).split(' ').mapNotNull { it.toIntOrNull(16) }
        if (b.size < 10) return line
        val cmd = b[4] or (b[5] shl 8)
        if (cmd !in DEVICE_CMDS && !(cmd == 0x0204 && b[9] == 0x06)) return line
        return line.substring(0, at + 2) + b.take(9).joinToString(" ") { "%02X".format(it) } + " [device data removed]"
    }

    private val DEVICE_CMDS = setOf(0x8112, 0x0429, 0x8132)

    private val MAC = Regex("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    private fun canConnect(c: Context) = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
        c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** This phone's own names: the device name and the Bluetooth name (null without the permission). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun phoneNames(c: Context): List<String> = listOfNotNull(
        runCatching { Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { if (canConnect(c)) c.getSystemService(BluetoothManager::class.java)?.adapter?.name else null }.getOrNull()
    ).filter { it.length >= 3 }.distinct()

    /**
     * The form body. A log over [MAX_BODY] loses its oldest lines (the header stays): the newest part shows the
     * problem. [categories] are [CATEGORIES] values.
     */
    fun body(model: String, categories: List<String>, description: String, log: String?): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val fields = listOf(ENTRY_MODEL to model, ENTRY_DESCRIPTION to description) + categories.map { ENTRY_CATEGORY to it }
        val head = fields.joinToString("&") { "${it.first}=${enc(it.second)}" }
        if (log == null) return head
        val lines = log.lines()
        var from = 1
        while (true) {
            val kept = (listOf(lines[0]) + lines.drop(from)).joinToString("\n")
            val body = "$head&$ENTRY_LOG=${enc(kept)}"
            if (body.length <= MAX_BODY || from >= lines.size) return body
            // Drop a tenth of what is left: a few passes, not one per line.
            from += maxOf(1, (lines.size - from) / 10)
        }
    }

    /** [body] as the person reads it before Send: one block per question, the log last. */
    fun preview(body: String): String {
        val names = mapOf(ENTRY_MODEL to "Model", ENTRY_CATEGORY to "Category", ENTRY_DESCRIPTION to "Description", ENTRY_LOG to "Log")
        return body.split('&').joinToString("\n\n") {
            val (k, v) = it.split('=', limit = 2)
            names.getValue(k) + ":\n" + java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    /**
     * QuickBuds Plus: a new GitHub issue on its own repo, filled with [preview] of [body]. The person reviews it and
     * submits it in the browser (a GitHub account is needed); nothing is posted from the app. The upstream Google Form
     * belongs to upstream QuickBuds. A link over [MAX_ISSUE_URL] loses the oldest log lines, as [body] does.
     */
    fun issueUrl(model: String, body: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val base = "${UpdateChecker.PAGE}/issues/new?title=${enc("Problem report: $model")}&body="
        val budget = MAX_ISSUE_URL - base.length
        val text = preview(body)
        if (enc(text).length <= budget) return base + enc(text)
        // Too long: the questions and the log's header line stay, the oldest log lines go.
        val at = text.indexOf("\n\nLog:\n")
        if (at < 0) return base + enc(text.take(budget / 3))
        val head = text.substring(0, at) + "\n\nLog:\n"
        val lines = text.substring(at + 7).lines()
        var from = 1
        while (from < lines.size) {
            val kept = head + (listOf(lines[0], "[older lines removed]") + lines.drop(from)).joinToString("\n")
            if (enc(kept).length <= budget) return base + enc(kept)
            from += maxOf(1, (lines.size - from) / 10)
        }
        return base + enc((head + lines[0]).take(budget / 3))
    }

    /** GitHub refuses new-issue links much over 8 KB. */
    private const val MAX_ISSUE_URL = 7_500
}
