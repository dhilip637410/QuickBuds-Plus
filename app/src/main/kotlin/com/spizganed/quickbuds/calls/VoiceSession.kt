package com.spizganed.quickbuds.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import com.spizganed.quickbuds.bluetooth.PacketLogger
import java.util.Locale

/**
 * One spoken question and one listen, through the earbuds: speak [run]'s prompt (media audio), open the earbuds'
 * microphone (the call link), listen up to [LISTEN_MS] with the phone's on-device recognizer, and hand back YES,
 * NO or UNCLEAR ([VoicePhrases]). Never online. Measured first with [VoiceProbe] on the developer's phone
 * (2026-10-18): earbud microphone during a ringing call, app in the background, ringer on.
 *
 * Main thread only. [cancel] stops everything at once without a result (the call stopped ringing).
 */
class VoiceSession(context: Context) {

    private val c = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = c.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var micOpen = false
    private var done = false
    private var onResult: ((VoicePhrases.Answer, String) -> Unit)? = null

    private fun log(msg: String) = PacketLogger.log("VOICE ANSWER: $msg")

    /** A Bluetooth output to speak through. Never the phone's speaker: without one, nothing is said. */
    fun bluetoothOutput(): Boolean = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    fun run(prompt: String, promptLocale: Locale, result: (VoicePhrases.Answer, String) -> Unit) {
        onResult = result
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
            return finish(VoicePhrases.Answer.UNCLEAR, "no on-device recognizer")
        }
        if (!bluetoothOutput()) return finish(VoicePhrases.Answer.UNCLEAR, "no Bluetooth output: silent")
        tts = TextToSpeech(c) { status ->
            val t = tts
            if (done || t == null) return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { log("speech engine failed ($status)"); return@TextToSpeech listen() }
            val lang = t.setLanguage(promptLocale)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) t.language = Locale("en", "IN")
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { listen() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { listen() } }
            })
            t.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "voice-answer")
        }
        // A prompt that never ends must not hold the listen back.
        main.postDelayed({ listen() }, SPEAK_TIMEOUT_MS)
    }

    private var listening = false

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (done || listening) return
        listening = true
        // The earbuds' microphone is on the call link (SCO).
        micOpen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am?.availableCommunicationDevices?.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            dev != null && runCatching { am!!.setCommunicationDevice(dev) }.getOrDefault(false)
        } else @Suppress("DEPRECATION") runCatching { am?.startBluetoothSco(); true }.getOrDefault(false)
        if (!micOpen) return finish(VoicePhrases.Answer.UNCLEAR, "earbud microphone not available")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return finish(VoicePhrases.Answer.UNCLEAR, "Android too old")
        // A moment for the call link to come up, then the recognizer.
        main.postDelayed({ OnDeviceEnglish.resolve(c) { lang -> if (lang == null) finish(VoicePhrases.Answer.UNCLEAR, "no English on-device") else recognize(lang) } }, 800)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognize(lang: String) {
        if (done) return
        val rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull()
            ?: return finish(VoicePhrases.Answer.UNCLEAR, "recognizer not created")
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = finish(VoicePhrases.Answer.UNCLEAR, "recognizer error $error")
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                finish(VoicePhrases.classifyAll(said), "heard ${said.take(3)}")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5))
        }.onFailure { finish(VoicePhrases.Answer.UNCLEAR, "start failed") }
        // The time box: nothing said within it is UNCLEAR.
        main.postDelayed({ runCatching { recognizer?.stopListening() } }, LISTEN_MS)
        main.postDelayed({ finish(VoicePhrases.Answer.UNCLEAR, "time box over") }, LISTEN_MS + 3_000L)
    }

    /** Releases the microphone and the engines first, then reports: an answered call gets the audio route back. */
    private fun finish(answer: VoicePhrases.Answer, detail: String) {
        if (done) return
        release()
        log("$detail -> $answer")
        onResult?.invoke(answer, detail)
        onResult = null
    }

    /** Stop now, no result: the call was answered by hand, rejected or ended. */
    fun cancel() {
        if (done) return
        release()
        onResult = null
        log("cancelled: the call stopped ringing")
    }

    private fun release() {
        done = true
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        if (micOpen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
            else @Suppress("DEPRECATION") runCatching { am?.stopBluetoothSco() }
            micOpen = false
        }
    }

    companion object {
        const val LISTEN_MS = 6_000L
        private const val SPEAK_TIMEOUT_MS = 8_000L
    }
}

/**
 * The English the phone's on-device recognizer has installed (en-IN first, else any English), asked once per
 * process and remembered. Voice answer's; voice music uses Vosk ([com.spizganed.quickbuds.music.VoskModel]). Main thread.
 */
object OnDeviceEnglish {
    private var cached: String? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun resolve(context: Context, then: (String?) -> Unit) {
        cached?.let { return then(it) }
        val c = context.applicationContext
        val check = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }.getOrNull() ?: return then(null)
        check.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), c.mainExecutor,
            object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(s: android.speech.RecognitionSupport) {
                    check.destroy()
                    val l = s.installedOnDeviceLanguages
                    cached = l.firstOrNull { it.equals("en-IN", true) } ?: l.firstOrNull { it.startsWith("en") }
                    // Back to back, the on-device service drops the second recognizer: a pause.
                    Handler(Looper.getMainLooper()).postDelayed({ then(cached) }, 600)
                }
                override fun onError(error: Int) { check.destroy(); then(null) }
            })
    }
}
