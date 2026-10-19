package com.spizganed.quickbuds.calls

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.TileService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.music.VoiceMusic
import com.spizganed.quickbuds.ui.BikeModeTile
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Bike mode (a quick settings tile, and Settings › Calls › Bike mode): turns on what the user chose for riding.
 * Auto-answer (with its own delay, [DEFAULT_DELAY_S] by default) and voice answer: on while Bike mode is on, and
 * off puts back the setting from before, unless it was changed by hand meanwhile. Voice music listens only while
 * Bike mode is on anyway: its own switch is the choice. Calls are still answered only while earbuds are connected
 * for calls, and the "Which calls" contact filter still applies.
 */
object BikeMode {

    const val KEY_ON = "bikeMode"
    /** The choices: what Bike mode turns on. */
    const val KEY_AUTO_ANSWER = "bikeAutoAnswer"
    const val KEY_VOICE_ANSWER = "bikeVoiceAnswer"
    const val KEY_DELAY = "bikeDelay"
    private const val KEY_PREV_ENABLED = "bikeModePrevAutoAnswer"
    private const val KEY_PREV_DELAY = "bikeModePrevDelay"
    private const val KEY_PREV_VOICE = "bikeModePrevVoiceAnswer"
    const val DEFAULT_DELAY_S = 3

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun isOn(c: Context) = prefs(c).getBoolean(KEY_ON, false)

    /** Auto-answer with Bike mode: on unless chosen off (it was all Bike mode did before the choices). */
    fun autoAnswer(c: Context) = prefs(c).getBoolean(KEY_AUTO_ANSWER, true)

    fun voiceAnswer(c: Context) = prefs(c).getBoolean(KEY_VOICE_ANSWER, false)

    fun delaySeconds(c: Context) = prefs(c).getInt(KEY_DELAY, DEFAULT_DELAY_S).takeIf { it in AutoAnswer.DELAYS } ?: DEFAULT_DELAY_S

    /** The permissions the chosen call features still need: Bike mode cannot turn on without them. */
    fun missingPermissions(c: Context): List<String> =
        ((if (autoAnswer(c)) AutoAnswer.PERMISSIONS.toList() else emptyList()) +
            (if (voiceAnswer(c)) VoiceAnswer.PERMISSIONS.toList() else emptyList()))
            .distinct().filter { c.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    /** Returns false when it cannot turn on: a chosen feature's permissions are missing. */
    fun set(c: Context, on: Boolean): Boolean {
        val p = prefs(c)
        if (on) {
            if (missingPermissions(c).isNotEmpty()) return false
            val e = p.edit()
            // Remember the settings only when Bike mode was really off, not on a repeated "on".
            if (!isOn(c)) e.putBoolean(KEY_PREV_ENABLED, p.getBoolean(AutoAnswer.KEY_ENABLED, false))
                .putInt(KEY_PREV_DELAY, AutoAnswer.delaySeconds(c))
                .putBoolean(KEY_PREV_VOICE, VoiceAnswer.switchedOn(c))
            if (autoAnswer(c)) e.putBoolean(AutoAnswer.KEY_ENABLED, true).putInt(AutoAnswer.KEY_DELAY, delaySeconds(c))
            if (voiceAnswer(c)) e.putBoolean(VoiceAnswer.KEY_ENABLED, true)
            e.putBoolean(KEY_ON, true).apply()
        } else {
            if (!isOn(c)) return true
            val e = p.edit().putBoolean(KEY_ON, false)
            // Put back what was there; a setting changed by hand meanwhile stays as it is.
            if (autoAnswer(c) && p.getBoolean(AutoAnswer.KEY_ENABLED, false) && AutoAnswer.delaySeconds(c) == delaySeconds(c))
                e.putBoolean(AutoAnswer.KEY_ENABLED, p.getBoolean(KEY_PREV_ENABLED, false))
                    .putInt(AutoAnswer.KEY_DELAY, p.getInt(KEY_PREV_DELAY, AutoAnswer.DEFAULT_DELAY))
            if (voiceAnswer(c) && VoiceAnswer.switchedOn(c)) e.putBoolean(VoiceAnswer.KEY_ENABLED, p.getBoolean(KEY_PREV_VOICE, false))
            e.apply()
        }
        PacketLogger.log("CALL: bike mode ${if (on) "on (${describe(c)})" else "off"}")
        refreshTile(c)
        return true
    }

    /**
     * Changes a choice. While Bike mode is on, it goes off with the old choices and on again with the new ones, so
     * what it put back and what it turns on always match. False: the new choice needs permissions (still off then).
     */
    fun choose(c: Context, change: (SharedPreferences.Editor) -> Unit): Boolean {
        val wasOn = isOn(c)
        if (wasOn) set(c, false)
        prefs(c).edit().also(change).apply()
        return !wasOn || set(c, true)
    }

    /** For the log: what this Bike mode turns on. */
    private fun describe(c: Context) = listOfNotNull(
        if (autoAnswer(c)) "auto-answer in ${delaySeconds(c)} s" else null,
        if (voiceAnswer(c)) "voice answer" else null,
        if (VoiceMusic.switchedOn(c)) "voice music" else null
    ).joinToString().ifEmpty { "nothing chosen" }

    /** The tile shows the new state even when the panel is closed. */
    fun refreshTile(c: Context) = runCatching {
        TileService.requestListeningState(c, ComponentName(c, BikeModeTile::class.java))
    }
}
