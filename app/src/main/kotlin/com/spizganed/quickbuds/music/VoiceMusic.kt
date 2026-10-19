package com.spizganed.quickbuds.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.calls.MusicCommands
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Notification access, which Android requires to control other apps' music (their media sessions). It reads no
 * notification: the service only has to exist and be allowed.
 */
class MusicAccessService : NotificationListenerService()

/**
 * Voice music: while Bike mode is on, spoken commands control the chosen music app ([VoiceMusicLoop] listens).
 * This object holds the settings, the list of music apps, and the commands themselves.
 */
object VoiceMusic {

    const val KEY_ENABLED = "voiceMusic"
    const val KEY_APP = "voiceMusicApp"

    data class App(val pkg: String, val label: String)

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun switchedOn(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    fun app(c: Context): String? = prefs(c).getString(KEY_APP, null)
    fun setApp(c: Context, pkg: String) = prefs(c).edit().putString(KEY_APP, pkg).apply()

    fun accessAllowed(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /**
     * Every installed app that plays media for others to control: a media browser service (Spotify, YouTube Music,
     * JioSaavn, ...), voice search for music ("play X"), or media buttons. Sorted by name. Needs the matching
     * <queries> in the manifest (Android 11+ package visibility).
     */
    fun apps(c: Context): List<App> {
        val pm = c.packageManager
        val pkgs = LinkedHashSet<String>()
        runCatching { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0) }.getOrNull()
            ?.forEach { pkgs += it.serviceInfo.packageName }
        runCatching { pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0) }.getOrNull()
            ?.forEach { pkgs += it.activityInfo.packageName }
        runCatching { pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0) }.getOrNull()
            ?.forEach { pkgs += it.activityInfo.packageName }
        pkgs.remove(c.packageName)
        return pkgs.mapNotNull { p ->
            runCatching {
                val info = pm.getApplicationInfo(p, 0)
                // Launchable apps only: system media plumbing has no screen of its own.
                if (pm.getLaunchIntentForPackage(p) == null) null else App(p, pm.getApplicationLabel(info).toString())
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The chosen app's media session, when it has one now (playing or paused). Needs notification access. */
    private fun controller(c: Context, pkg: String): MediaController? = runCatching {
        c.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(c, MusicAccessService::class.java))
            ?.firstOrNull { it.packageName == pkg }
    }.onFailure { PacketLogger.log("VOICE MUSIC: no access to media sessions (${it.javaClass.simpleName})") }.getOrNull()

    /** A media key straight to the app: works for most apps even without an open session. */
    private fun mediaKey(c: Context, pkg: String, code: Int) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val now = SystemClock.uptimeMillis()
            c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, action, code, 0)))
        }
    }

    /** Runs [cmd] in the chosen app: through its media session when it has one, else as a media key. */
    fun run(c: Context, cmd: MusicCommands.Command): Boolean {
        if (cmd == MusicCommands.Command.EndCall) return endCall(c)
        val pkg = app(c) ?: return false.also { PacketLogger.log("VOICE MUSIC: no app chosen") }
        val ctl = controller(c, pkg)
        val t = ctl?.transportControls
        when (cmd) {
            MusicCommands.Command.Play -> t?.play() ?: mediaKey(c, pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
            MusicCommands.Command.Pause -> t?.pause() ?: mediaKey(c, pkg, KeyEvent.KEYCODE_MEDIA_PAUSE)
            MusicCommands.Command.Next -> t?.skipToNext() ?: mediaKey(c, pkg, KeyEvent.KEYCODE_MEDIA_NEXT)
            MusicCommands.Command.Previous -> t?.skipToPrevious() ?: mediaKey(c, pkg, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MusicCommands.Command.EndCall -> {}
        }
        PacketLogger.log("VOICE MUSIC: $cmd sent to ${label(c, pkg)}${if (ctl == null) " (media key: no open session)" else ""}")
        return true
    }

    /** "Hey Jarvis, cut the call": ends the ringing or ongoing call (Android 9+, the answer-calls permission). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun endCall(c: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false.also { PacketLogger.log("VOICE MUSIC: ending a call needs Android 9") }
        if (c.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false.also { PacketLogger.log("VOICE MUSIC: cannot end the call: the answer-calls permission is missing") }
        }
        val ended = runCatching {
            @Suppress("DEPRECATION") c.getSystemService(android.telecom.TelecomManager::class.java)?.endCall() == true
        }.onFailure { PacketLogger.error("voice music end call", it) }.getOrDefault(false)
        PacketLogger.log("VOICE MUSIC: end call ${if (ended) "done" else "refused by Android"}")
        return ended
    }
}
