package com.spizganed.quickbuds.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.calls.BikeMode
import com.spizganed.quickbuds.calls.MusicCommands
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Voice music's listener, in [BudsService]: while Bike mode is on, Vosk listens offline through the earbuds'
 * microphone for "Hey Jarvis" + a command ([MusicCommands.grammar]), and runs it in the chosen music app. "Hey
 * Jarvis" alone beeps, then a command alone counts for [WINDOW_MS]. While a call rings, "Hey Jarvis, cut the call"
 * rejects it (voice answer, when it asks, has the call instead). During the call it is off: Android silences it.
 *
 * Vosk records continuously itself: no start tone. Was: Android's speech engine, which played a tone at each of its
 * 8-second listens (a "toing" every 10 to 15 s), and returned no words when fed QuickBuds' own recording.
 * By the developer's choice (2026-10-19), knowing the cost: always the earbuds' microphone, the only one that hears
 * on a bike. It puts the earbuds in call mode, so music plays in call quality (muffled): Bluetooth cannot do both at
 * once. Was: the phone's microphone while music played; it cannot hear from a pocket or a bag. Never online.
 */
class VoiceMusicLoop(context: Context) {

    private val c = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = c.getSystemService(AudioManager::class.java)
    private var running = false
    /** A call rings: "Hey Jarvis, cut the call" rejects it (unless voice answer is asking about it). */
    private var ringing = false
    /**
     * In a call: not listening. Android silences other apps' microphones during a call (2026-10-19, the phone's own
     * audio log: "pack:com.spizganed.quickbuds … silenced:true"), so "cut the call" cannot be heard then.
     */
    private var inCall = false
    /** "Hey Jarvis" alone was heard: a command alone counts until this time. */
    private var awaitUntil = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    /** Results that were no command, logged up to REPORT_MAX per run, to tune the phrases. */
    private var reported = 0

    private fun log(msg: String) = PacketLogger.log("VOICE MUSIC: $msg")

    /** Everything it needs, in the order a settings screen would show the first missing one. */
    fun wanted(): Boolean = VoiceMusic.switchedOn(c) && BikeMode.isOn(c) && VoiceMusic.app(c) != null &&
        VoiceMusic.accessAllowed(c) && VoskModel.ready(c) && BudsService.microphoneReady && !inCall &&
        !com.spizganed.quickbuds.calls.VoiceAnswer.asking &&
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val phone = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                // Ringing: keep listening for "Hey Jarvis, cut the call", unless voice answer asks about this call
                // (it decides within a moment of the number arriving: look again after that).
                TelephonyManager.EXTRA_STATE_RINGING -> if (!ringing) {
                    ringing = true
                    main.postDelayed({ if (com.spizganed.quickbuds.calls.VoiceAnswer.asking) stop("voice answer asks") }, 800)
                }
                // In the call: Android silences other apps' microphones, so stop until it ends.
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (!inCall) {
                    ringing = false
                    inCall = true
                    stop("in a call: Android gives the microphone to the call only")
                }
                // The call ended: back to normal listening, with the earbud microphone set again.
                TelephonyManager.EXTRA_STATE_IDLE -> if (ringing || inCall) {
                    ringing = false
                    inCall = false
                    stop("call ended")
                    main.postDelayed({ sync() }, 2_000)
                }
            }
        }
    }
    private var phoneRegistered = false

    /** Starts or stops to match the settings, Bike mode, the model, the microphone and calls. */
    fun sync() {
        if (!phoneRegistered && VoiceMusic.switchedOn(c)) {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.registerReceiver(phone, f, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") c.registerReceiver(phone, f)
            phoneRegistered = true
        }
        if (wanted() && !running) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) start() }
        else if (!wanted() && running) stop("off")
        restoreSystemSounds()
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun start() {
        val dev = am?.availableCommunicationDevices?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return log("not listening: no earbuds connected for calls")
        if (!runCatching { am.setCommunicationDevice(dev) }.getOrDefault(false)) return log("not listening: earbud microphone refused")
        running = true
        reported = 0
        log("loading the speech model")
        // Loading takes a second or two: off the main thread, then listen on it.
        Thread {
            val m = model ?: VoskModel.load(c)
            main.post {
                if (!running) return@post
                if (m == null) return@post stop("speech model missing or broken")
                model = m
                listen(m)
            }
        }.apply { isDaemon = true; name = "vosk-load" }.start()
    }

    private fun listen(m: Model) {
        val phrases = MusicCommands.grammar()
        VoskModel.missingWords(m, phrases).takeIf { it.isNotEmpty() }?.let { log("words the model does not know: $it") }
        val rec = runCatching { Recognizer(m, RATE, JSONArray(phrases).toString()) }
            .onFailure { log("recognizer not created: ${it.message}") }.getOrNull() ?: return stop("no recognizer")
        recognizer = rec
        val svc = runCatching { SpeechService(rec, RATE) }
            .onFailure { log("microphone not opened: ${it.message}") }.getOrNull() ?: return stop("no microphone")
        service = svc
        svc.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}
            override fun onResult(hypothesis: String?) = heard(hypothesis)
            override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
            override fun onError(exception: Exception?) {
                log("listening error: ${exception?.message}, starting again")
                val r = running
                stop("error")
                if (r) main.postDelayed({ sync() }, 3_000)
            }
            override fun onTimeout() {}
        })
        log("listening for \"Hey Jarvis\" (${VoiceMusic.label(c, VoiceMusic.app(c)!!)})")
    }

    private fun heard(json: String?) {
        val text = runCatching { JSONObject(json ?: return).optString("text") }.getOrDefault("")
        if (text.isBlank() || text == "[unk]") return
        val awaiting = System.currentTimeMillis() < awaitUntil
        when (val h = MusicCommands.interpret(text, awaiting)) {
            MusicCommands.Heard.Wake -> {
                awaitUntil = System.currentTimeMillis() + WINDOW_MS
                beep()
                log("heard \"hey jarvis\": waiting ${WINDOW_MS / 1000} s for the command")
            }
            is MusicCommands.Heard.Do -> {
                awaitUntil = 0L
                log("heard \"$text\" -> ${h.command}")
                // "Cut the call" rejects a ringing call; with none ringing there is nothing to cut.
                if (h.command == MusicCommands.Command.EndCall && !ringing) log("no ringing call to cut")
                else VoiceMusic.run(c, h.command)
            }
            MusicCommands.Heard.Nothing -> if (++reported <= REPORT_MAX) log("heard \"$text\": no command")
        }
    }

    /** A short beep in the earbuds: "Hey Jarvis" was heard, say the command. */
    private fun beep() {
        val tone = runCatching { android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }.getOrNull() ?: return
        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        main.postDelayed({ runCatching { tone.release() } }, 400)
    }

    fun stop(why: String) {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { service?.stop(); service?.shutdown() }
        service = null
        runCatching { recognizer?.close() }
        recognizer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
        awaitUntil = 0L
        log("stopped listening ($why)")
    }

    fun destroy() {
        stop("service ends")
        runCatching { model?.close() }
        model = null
        if (phoneRegistered) runCatching { c.unregisterReceiver(phone) }
        phoneRegistered = false
    }

    /**
     * The test build of 2026-10-19 muted system sounds against the old engine's tone. If it left them muted (the app
     * was replaced while listening), they come back once.
     */
    private fun restoreSystemSounds() {
        val p = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_TONE_MUTED, false)) return
        runCatching { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) }
        p.edit().remove(KEY_TONE_MUTED).apply()
        log("system sounds back on")
    }

    private companion object {
        const val RATE = 16_000f
        const val REPORT_MAX = 20
        /** After "Hey Jarvis" alone: this long to say the command. */
        const val WINDOW_MS = 6_000L
        const val KEY_TONE_MUTED = "voiceMusicToneMuted"
    }
}
