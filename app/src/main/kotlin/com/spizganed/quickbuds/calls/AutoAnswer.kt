package com.spizganed.quickbuds.calls

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Auto-answer: a ringing call is answered after the chosen delay, when earbuds are connected for calls, so it is
 * heard in them. Nothing happens when the call stops ringing first (the user answered or rejected it), when no
 * earbuds are connected for calls, or without the two phone permissions. Pure rules here; [AutoAnswerMonitor] runs them.
 */
object AutoAnswer {
    const val KEY_ENABLED = "autoAnswer"
    const val KEY_DELAY = "autoAnswerDelay"
    val DELAYS = listOf(3, 5, 10, 15)
    const val DEFAULT_DELAY = 5

    val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false) && permitted(c) && BudsService.backgroundAllowed(c)

    fun delaySeconds(c: Context) = prefs(c).getInt(KEY_DELAY, DEFAULT_DELAY).takeIf { it in DELAYS } ?: DEFAULT_DELAY

    fun permitted(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** At the end of the delay: answer only if the call still rings and earbuds are connected for calls. */
    fun shouldAnswer(stillRinging: Boolean, earbudsConnected: Boolean): Boolean = stillRinging && earbudsConnected

    // --- Which calls: all, only chosen contacts, or all except chosen contacts ---

    enum class Filter { ALL, ONLY, EXCEPT }

    /** A chosen contact: the name to show and the number to match. */
    data class Contact(val name: String, val number: String)

    const val KEY_FILTER = "autoAnswerFilter"
    private const val KEY_CONTACTS = "autoAnswerContacts"

    /** Android gives the caller's number only with this permission (Android 9+). */
    const val CALL_LOG = Manifest.permission.READ_CALL_LOG

    fun filter(c: Context): Filter =
        runCatching { Filter.valueOf(prefs(c).getString(KEY_FILTER, Filter.ALL.name)!!) }.getOrDefault(Filter.ALL)

    fun setFilter(c: Context, f: Filter) = prefs(c).edit().putString(KEY_FILTER, f.name).apply()

    fun callLogPermitted(c: Context) = c.checkSelfPermission(CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun contacts(c: Context): List<Contact> = runCatching {
        val arr = org.json.JSONArray(prefs(c).getString(KEY_CONTACTS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Contact(o.getString("name"), o.getString("number")) } }
    }.getOrDefault(emptyList())

    fun setContacts(c: Context, list: List<Contact>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("name", it.name).put("number", it.number)) }
        prefs(c).edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }

    /** The digits only; "+91 98765-43210" and "098765 43210" both end in 9876543210. */
    private fun digits(n: String) = n.filter { it.isDigit() }

    /** Same number: the last 10 digits, so a country code or a trunk 0 does not matter; shorter numbers must equal. */
    fun sameNumber(a: String, b: String): Boolean {
        val x = digits(a); val y = digits(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return if (x.length >= 10 && y.length >= 10) x.takeLast(10) == y.takeLast(10) else x == y
    }

    /**
     * Whether the caller passes the filter. A hidden or unknown number ([number] null) is not a chosen contact:
     * ONLY skips it, EXCEPT answers it.
     */
    fun callerAllowed(filter: Filter, number: String?, contacts: List<Contact>): Boolean {
        val chosen = number != null && contacts.any { sameNumber(it.number, number) }
        return when (filter) {
            Filter.ALL -> true
            Filter.ONLY -> chosen
            Filter.EXCEPT -> !chosen
        }
    }
}

/**
 * In [BudsService] while it runs: the phone state broadcast says when a call rings; after the delay, if the call still
 * rings and earbuds are connected for calls, it is answered.
 * "Connected for calls" is the headset profile (or LE Audio), not media audio or QuickBuds' own link: a call goes to
 * earbuds only over that profile. Without it the call would open on the phone's earpiece, with nobody holding it.
 */
class AutoAnswerMonitor(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val answer = Runnable { tryAnswer() }

    /** The ringing call's number, when Android gives it (call log permission): one of the two broadcasts has it. */
    private var incoming: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Voice answer probe (Dev tools): measures this one call, answers nothing.
                    if (VoiceProbe.armed(context) && !VoiceAnswer.enabled(context)) {
                        VoiceProbe.setArmed(context, false)
                        handler.removeCallbacks(answer)
                        VoiceProbe.run(context, "ringing")
                        onProbeDone()
                        return
                    }
                    @Suppress("DEPRECATION")
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }?.let { incoming = it }
                    // Voice answer, once the number is known (the second of the two broadcasts): it asks the
                    // user, so the timed auto-answer waits. No clear reply: auto-answer answers then.
                    val number = incoming
                    if (number != null && !voiceAsked && VoiceAnswer.enabled(context)) {
                        voiceAsked = true
                        callHeadset { headset ->
                            if (VoiceAnswer.onRinging(context, number, headset) { tryAnswer() }) {
                                voiceTookCall = true
                                handler.removeCallbacks(answer)
                            }
                        }
                    }
                    if (!AutoAnswer.enabled(context) || voiceTookCall) return
                    // The broadcast comes twice (with and without the number): one timer.
                    handler.removeCallbacks(answer)
                    handler.postDelayed(answer, AutoAnswer.delaySeconds(context) * 1000L)
                    PacketLogger.log("CALL: ringing, auto-answer in ${AutoAnswer.delaySeconds(context)} s if the earbuds are connected")
                }
                // Answered or rejected by the user, or ended: nothing to do.
                TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                    handler.removeCallbacks(answer)
                    VoiceAnswer.onStoppedRinging()
                    incoming = null
                    voiceAsked = false
                    voiceTookCall = false
                }
            }
        }
    }

    /** The voice probe ran: the service drops the microphone type again. */
    var onProbeDone: () -> Unit = {}

    /** This call: voice answer looked at it once; it asked the user (the timed auto-answer then waits). */
    private var voiceAsked = false
    private var voiceTookCall = false

    /** Registered while the feature is on and allowed, or the voice probe is armed; on start and on setting changes. */
    fun update() {
        val want = AutoAnswer.enabled(context) || VoiceProbe.armed(context) || VoiceAnswer.enabled(context)
        if (want && !registered) {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // A system broadcast: exported, so the system's sender reaches it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
            registered = true
        } else if (!want && registered) {
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(answer)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    @SuppressLint("MissingPermission")
    private fun tryAnswer() {
        if (!AutoAnswer.enabled(context)) return
        val ringing = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)
        if (!ringing) return
        val filter = AutoAnswer.filter(context)
        if (!AutoAnswer.callerAllowed(filter, incoming, AutoAnswer.contacts(context))) {
            // No number in the log: it is personal data.
            PacketLogger.log("CALL: not answered: the caller does not pass the contact filter ($filter, " +
                "${if (incoming == null) "number not known" else "number known"})")
            return
        }
        callHeadset { name ->
            if (!AutoAnswer.shouldAnswer(ringing, name != null)) {
                PacketLogger.log("CALL: not answered: no earbuds connected for calls")
                return@callHeadset
            }
            runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
                PacketLogger.log("CALL: auto-answered after ${AutoAnswer.delaySeconds(context)} s on $name")
            }.onFailure { PacketLogger.error("auto-answer", it) }
        }
    }

    /**
     * The name of a device connected for calls (headset profile, or LE Audio where the phone has it), or null.
     * On the main thread; null when Bluetooth is off, cannot be asked, or does not answer in time.
     */
    @SuppressLint("MissingPermission")
    private fun callHeadset(result: (String?) -> Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return result(null)
        // A profile the phone has switched off accepts the request and never answers: LE Audio only where it is on.
        val leAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { adapter.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED }.getOrDefault(false)
        val profiles = if (leAudio) listOf(BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO) else listOf(BluetoothProfile.HEADSET)
        var left = profiles.size
        var found: String? = null
        var delivered = false
        fun deliver() {
            if (delivered) return
            delivered = true
            result(found)
        }
        handler.postDelayed({ deliver() }, HEADSET_TIMEOUT_MS)
        for (p in profiles) {
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let { d ->
                            if (found == null) found = runCatching { d.name }.getOrNull() ?: d.address
                        }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        handler.post { if (--left == 0) deliver() }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            }.getOrDefault(false)
            if (!asked && --left == 0) handler.post { deliver() }
        }
    }

    private companion object {
        const val HEADSET_TIMEOUT_MS = 2_000L
    }
}
