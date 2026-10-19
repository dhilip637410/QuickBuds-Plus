package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes
import java.util.Locale

/**
 * A measurement, not the feature: can a ringing call be answered by voice over the earbuds on this phone?
 * One run speaks a prompt, opens the earbuds' microphone (the call link, SCO), listens for 6 s and logs every
 * step ("VOICE:" lines). It never answers, declines, or changes the ringer or volume.
 *
 * What it answers: does the prompt play; does the recognizer hear anything (the loudest level, "max rms", tells
 * real sound from silence) while QuickBuds is in the background; does the earbud microphone open while a call
 * rings; is Tamil available on the device.
 */
object VoiceProbe {

    const val KEY_ARMED = "voiceProbeArmed"
    private const val LISTEN_MS = 6_000L
    private const val SPEAK_TIMEOUT_MS = 6_000L

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun armed(c: Context) = prefs(c).getBoolean(KEY_ARMED, false)

    fun setArmed(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ARMED, on).apply()

    /**
     * Asks the phone's on-device recognizer for a language model ([lang], e.g. ta-IN or en-IN), on-device only. Logs what the recognizer
     * offers (installed, downloadable, pending) and, on Android 14+, the download's progress and outcome.
     */
    fun downloadModel(context: Context, lang: String) {
        val c = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
            return log("$lang download: no on-device recognizer on this phone")
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        val rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c) }
            .onFailure { log("$lang download: recognizer not created: ${it.message}") }.getOrNull() ?: return
        rec.checkRecognitionSupport(intent, c.mainExecutor, object : android.speech.RecognitionSupportCallback {
            override fun onSupportResult(s: android.speech.RecognitionSupport) {
                log("$lang download: installed [${s.installedOnDeviceLanguages.joinToString()}], " +
                    "downloadable [${s.supportedOnDeviceLanguages.joinToString()}], pending [${s.pendingOnDeviceLanguages.joinToString()}]")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    rec.triggerModelDownload(intent, c.mainExecutor, object : android.speech.ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) = log("$lang download: $completedPercent%")
                        override fun onSuccess() { log("$lang download: done"); rec.destroy() }
                        override fun onScheduled() { log("$lang download: scheduled by Android (later, e.g. on Wi-Fi)"); rec.destroy() }
                        override fun onError(error: Int) { log("$lang download: refused (${errorName(error)})"); rec.destroy() }
                    })
                } else {
                    rec.triggerModelDownload(intent)
                    log("$lang download: requested (Android 13 gives no progress)")
                    rec.destroy()
                }
            }
            override fun onError(error: Int) { log("$lang download: support check error ${errorName(error)}"); rec.destroy() }
        })
    }

    private var running = false

    /** The English the on-device recognizer has installed, checked once per process. */
    private var installedEnglish: String? = null

    private fun log(msg: String) = PacketLogger.log("VOICE: $msg")

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio (microphone)"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing said"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        10 -> "too many requests"
        11 -> "server disconnected"
        12 -> "language not supported"
        13 -> "language not installed"
        14 -> "cannot check language support"
        else -> "code $code"
    }

    /** One run on the main thread. [reason]: "try now" (app on screen) or "ringing" (a real call). */
    @SuppressLint("MissingPermission")
    fun run(context: Context, reason: String) {
        val c = context.applicationContext
        if (running) return log("already running")
        running = true
        val main = Handler(Looper.getMainLooper())
        val am = c.getSystemService(AudioManager::class.java)
        log("start ($reason), Android ${Build.VERSION.RELEASE}, microphone permission " +
            (if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "granted" else "MISSING"))

        // Which Bluetooth outputs exist now: media (A2DP) for the prompt, call (SCO) for the microphone.
        val outs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val a2dp = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val sco = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        log("bluetooth outputs: media ${if (a2dp) "yes" else "no"}, call ${if (sco) "yes" else "no"}; audio mode ${am?.mode}")

        // Recognition on this phone: on-device or not, and which of English and Tamil.
        log("recognizer available: ${SpeechRecognizer.isRecognitionAvailable(c)}" +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ", on-device: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(c)}" else "")

        var tts: TextToSpeech? = null
        var listened = false
        fun cleanup() {
            runCatching { tts?.shutdown() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am?.clearCommunicationDevice() }
            else @Suppress("DEPRECATION") runCatching { am?.stopBluetoothSco() }
            log("end")
            running = false
        }

        fun listen() {
            if (listened) return
            listened = true
            // The earbuds' microphone is on the call link (SCO): ask for it.
            val micOpened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = am?.availableCommunicationDevices?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (dev == null) { log("earbud microphone: no call device offered"); false }
                else runCatching { am!!.setCommunicationDevice(dev) }.onFailure { log("earbud microphone: refused (${it.message})") }.getOrDefault(false)
            } else @Suppress("DEPRECATION") runCatching { am?.startBluetoothSco(); true }.getOrDefault(false)
            log("earbud microphone requested: ${if (micOpened) "accepted" else "not accepted"}, audio mode ${am?.mode}")

            // On-device only, in an English the phone has installed. Never online: nothing leaves the phone.
            @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
            fun recognize(onDevice: Boolean, lang: String, retried: Boolean) {
                val rec = runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
                }.onFailure { log("recognizer not created: ${it.message}") }.getOrNull() ?: return cleanup()
                var maxRms = -100f
                var finished = false
                fun finish(what: String, retry: Boolean = false) {
                    if (finished) return
                    finished = true
                    log("$what; loudest level ${"%.1f".format(maxRms)} dB (about -2 or lower is silence)")
                    runCatching { rec.destroy() }
                    cleanup()
                }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = log("listening (${if (onDevice) "on-device" else "online"}, $lang)")
                    override fun onBeginningOfSpeech() {
                        val mics = runCatching {
                            am?.activeRecordingConfigurations.orEmpty().mapNotNull { it.audioDevice?.type }.distinct()
                        }.getOrDefault(emptyList())
                        val where = when {
                            mics.isEmpty() -> "not reported"
                            mics.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET } -> "earbuds"
                            mics.any { it == AudioDeviceInfo.TYPE_BUILTIN_MIC } -> "phone"
                            else -> "type $mics"
                        }
                        log("speech started, recording from: $where")
                    }
                    override fun onRmsChanged(rmsdB: Float) { if (rmsdB > maxRms) maxRms = rmsdB }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) = finish("recognizer error: ${errorName(error)}")
                    override fun onResults(results: Bundle?) {
                        val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        finish("heard ${said.take(3)} -> ${VoicePhrases.classifyAll(said)}")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                runCatching {
                    rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5))
                }.onFailure { finish("start failed: ${it.message}") }
                // The time box: what is not said within it is UNCLEAR.
                main.postDelayed({ runCatching { rec.stopListening() } }, LISTEN_MS)
                main.postDelayed({ finish("time box over") }, LISTEN_MS + 3_000L)
            }

            main.postDelayed({
                val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)
                if (!onDevice || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    log("no on-device recognizer on this phone: not listening (online is off by choice)")
                    return@postDelayed cleanup()
                }
                installedEnglish?.let { lang -> return@postDelayed recognize(true, lang, false) }
                // The exact English the device has installed (en-IN if there, else the first English).
                runCatching {
                    val check = SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
                    check.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), c.mainExecutor,
                        object : android.speech.RecognitionSupportCallback {
                            override fun onSupportResult(s: android.speech.RecognitionSupport) {
                                check.destroy()
                                val installed = s.installedOnDeviceLanguages
                                log("on-device installed: ${installed.joinToString().ifEmpty { "none" }}; Tamil can be downloaded: " +
                                    s.supportedOnDeviceLanguages.any { it.startsWith("ta") })
                                val lang = installed.firstOrNull { it.equals("en-IN", true) } ?: installed.firstOrNull { it.startsWith("en") }
                                installedEnglish = lang
                                // A moment between two on-device recognizers: back to back, the service drops the
                                // second ("server disconnected").
                                main.postDelayed({ if (lang != null) recognize(true, lang, false) else { log("no English installed on-device: not listening (online is off by choice)"); cleanup() } }, 600)
                            }
                            override fun onError(error: Int) { check.destroy(); log("language check error ${errorName(error)}"); cleanup() }
                        })
                }.onFailure { log("language check failed: ${it.message}"); cleanup() }
            }, 800)   // a moment for the call link to come up
        }

        // The prompt, over Bluetooth media only (the probe skips it without a Bluetooth output: never the speaker).
        if (!a2dp && !sco) {
            log("no Bluetooth output: no prompt spoken")
            return listen()
        }
        tts = TextToSpeech(c) { status ->
            if (status != TextToSpeech.SUCCESS) { log("speech engine failed ($status)"); return@TextToSpeech listen() }
            val t = tts ?: return@TextToSpeech listen()
            log("speech engine ready; Tamil voice ${when (t.isLanguageAvailable(Locale("ta", "IN"))) {
                TextToSpeech.LANG_MISSING_DATA -> "needs download"; TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"; else -> "available" }}")
            t.language = Locale("en", "IN")
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { log("prompt playing") } }
                override fun onDone(utteranceId: String?) { main.post { log("prompt done"); listen() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { log("prompt error"); listen() } }
            })
            t.speak("QuickBuds test. Say yes or no.", TextToSpeech.QUEUE_FLUSH, null, "probe")
        }
        main.postDelayed({ if (!listened) { log("prompt did not finish in time"); listen() } }, SPEAK_TIMEOUT_MS)
    }
}
