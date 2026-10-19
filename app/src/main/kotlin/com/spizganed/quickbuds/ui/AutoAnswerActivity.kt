package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.calls.AutoAnswer
import com.spizganed.quickbuds.calls.VoiceAnswer
import com.spizganed.quickbuds.calls.VoicePhrases

/**
 * Calls, for any earbuds (the phone answers; the call goes to the earbuds connected for calls): auto-answer (on/off,
 * the delay), voice answer (on/off, Try it now, the words), and one contact filter for both: all calls, only chosen
 * contacts, or all except chosen contacts. Both on: voice answer asks first; no clear reply, auto-answer answers.
 * Reached from Settings, Earbud settings and the Bike mode tile.
 */
class AutoAnswerActivity : Activity() {

    private lateinit var answerSwitch: Switch
    private lateinit var answerSub: TextView
    private lateinit var delaySub: TextView
    private lateinit var filterSub: TextView
    private lateinit var contactsCard: LinearLayout
    private lateinit var contactsLabel: TextView
    private lateinit var voiceSwitch: Switch
    private lateinit var voiceSub: TextView
    private lateinit var tryResult: TextView
    private var permAsked = false
    private var voicePermAsked = false

    private fun prefs() = getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        fun dp(v: Float) = ThemeRes.dp(this, v)
        fun note(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(ThemeRes.color(this@AutoAnswerActivity, R.attr.appColorTextSecondary))
            setPadding(dp(4f), dp(10f), dp(4f), 0)
        }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.auto_answer_section))

        answerSwitch = SettingRowFactory.buildSwitch(this, isOn())
        val row = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.auto_answer_title, R.string.auto_answer_off, answerSwitch) {
            answerSwitch.performClick()
        }
        answerSub = SettingRowFactory.subtitle(this, row)
        answerSwitch.setOnCheckedChangeListener(answerListener)

        val delayRow = SettingRowFactory.build(this, 0, R.string.auto_answer_delay_title, 0, SettingRowFactory.buildChevron(this)) {
            BottomSheetDialog(this).title(getString(R.string.auto_answer_delay_title)).items(AutoAnswer.DELAYS.map { s ->
                BottomSheetDialog.Item(getString(R.string.auto_answer_seconds, s), s == AutoAnswer.delaySeconds(this)) {
                    prefs().edit().putInt(AutoAnswer.KEY_DELAY, s).apply()
                    paint()
                }
            }).show()
        }
        delaySub = SettingRowFactory.subtitle(this, delayRow)

        voiceSwitch = SettingRowFactory.buildSwitch(this, voiceOn())
        val voiceRow = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.va_title, 0, voiceSwitch) { voiceSwitch.performClick() }
        voiceSub = SettingRowFactory.subtitle(this, voiceRow)
        voiceSwitch.setOnCheckedChangeListener(voiceListener)
        // Try it now: the question in the earbuds and one listen; nothing happens to any call.
        val tryRow = SettingRowFactory.build(this, 0, R.string.va_try_title, R.string.va_try_sub, null) { tryNow() }
        tryResult = SettingRowFactory.subtitle(this, tryRow)

        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, row)
            SettingRowFactory.addRow(this, delayRow)
            SettingRowFactory.addRow(this, voiceRow)
            SettingRowFactory.addRow(this, tryRow)
        })

        // One contact filter for auto-answer, Bike mode and voice answer.
        val filterRow = SettingRowFactory.build(this, 0, R.string.aa_filter_title, 0, SettingRowFactory.buildChevron(this)) {
            BottomSheetDialog(this).title(getString(R.string.aa_filter_title)).items(AutoAnswer.Filter.values().map { f ->
                BottomSheetDialog.Item(getString(filterLabel(f)), f == AutoAnswer.filter(this)) {
                    AutoAnswer.setFilter(this, f)
                    if (f != AutoAnswer.Filter.ALL && !AutoAnswer.callLogPermitted(this)) askCallLog()
                    paint()
                }
            }).show()
        }
        filterSub = SettingRowFactory.subtitle(this, filterRow)
        root.addView(SettingRowFactory.sectionLabel(this, R.string.aa_filter_title))
        root.addView(SettingRowFactory.card(this).apply { SettingRowFactory.addRow(this, filterRow) })
        root.addView(note(getString(R.string.aa_filter_note)).apply { setPadding(dp(4f), dp(6f), dp(4f), 0) })

        contactsLabel = SettingRowFactory.sectionLabel(this, R.string.aa_contacts_title)
        root.addView(contactsLabel)
        contactsCard = SettingRowFactory.card(this)
        root.addView(contactsCard)

        // Voice answer: the exact words it understands.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.va_say_title))
        root.addView(SettingRowFactory.card(this).apply {
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
            addView(TextView(this@AutoAnswerActivity).apply {
                textSize = 14f
                setTextColor(ThemeRes.color(this@AutoAnswerActivity, R.attr.appColorTextPrimary))
                text = getString(R.string.va_say_yes, VoicePhrases.YES.joinToString(", ")) + "\n\n" +
                    getString(R.string.va_say_no, VoicePhrases.NO.joinToString(", "))
            })
        })
        root.addView(note(getString(R.string.va_say_tip)))
        root.addView(note(getString(R.string.va_note)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@AutoAnswerActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings: the permissions may have changed.
        quietSwitch(isOn())
        quietVoiceSwitch(voiceOn())
        paint()
    }

    private fun isOn() = prefs().getBoolean(AutoAnswer.KEY_ENABLED, false) && AutoAnswer.permitted(this)

    private fun voiceOn() = VoiceAnswer.switchedOn(this) && VoiceAnswer.permitted(this)

    private fun filterLabel(f: AutoAnswer.Filter) = when (f) {
        AutoAnswer.Filter.ALL -> R.string.aa_filter_all
        AutoAnswer.Filter.ONLY -> R.string.aa_filter_only
        AutoAnswer.Filter.EXCEPT -> R.string.aa_filter_except
    }

    private val answerListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !AutoAnswer.permitted(this)) {
            quietSwitch(false)
            askPhonePermissions()
            return@OnCheckedChangeListener
        }
        prefs().edit().putBoolean(AutoAnswer.KEY_ENABLED, on).apply()
        paint()
    }

    private val voiceListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !VoiceAnswer.permitted(this)) {
            quietVoiceSwitch(false)
            askVoicePermissions()
            return@OnCheckedChangeListener
        }
        VoiceAnswer.setEnabled(this, on)
        if (on) takeMicrophone()
        paint()
    }

    /** This screen is open: the background service may take the microphone now. */
    private fun takeMicrophone() = startService(Intent(this, BudsService::class.java)
        .setAction(BudsService.ACTION_VOICE_PROBE).putExtra(BudsService.EXTRA_FIND_ON, true))

    private fun quietSwitch(on: Boolean) {
        answerSwitch.setOnCheckedChangeListener(null)
        answerSwitch.isChecked = on
        answerSwitch.setOnCheckedChangeListener(answerListener)
    }

    private fun quietVoiceSwitch(on: Boolean) {
        voiceSwitch.setOnCheckedChangeListener(null)
        voiceSwitch.isChecked = on
        voiceSwitch.setOnCheckedChangeListener(voiceListener)
    }

    /** Subtitles, and the contact list while a contact filter is chosen. */
    private fun paint() {
        val on = isOn()
        val delay = AutoAnswer.delaySeconds(this)
        val filter = AutoAnswer.filter(this)
        answerSub.text = when {
            on && !BudsService.backgroundAllowed(this) -> getString(R.string.settings_loss_needs_background)
            on -> getString(R.string.auto_answer_on, delay)
            else -> getString(R.string.auto_answer_off)
        }
        delaySub.text = getString(R.string.auto_answer_seconds, delay)
        voiceSub.text = getString(when {
            !VoiceAnswer.permitted(this) && VoiceAnswer.switchedOn(this) -> R.string.va_sub_perms
            !voiceOn() -> R.string.va_sub_off
            !BudsService.backgroundAllowed(this) -> R.string.settings_loss_needs_background
            !BudsService.microphoneReady -> R.string.va_sub_mic
            else -> R.string.va_sub_on
        })
        filterSub.text = if (filter != AutoAnswer.Filter.ALL && !AutoAnswer.callLogPermitted(this))
            getString(filterLabel(filter)) + "\n" + getString(R.string.aa_calllog_needed)
        else getString(filterLabel(filter))

        val show = filter != AutoAnswer.Filter.ALL
        contactsLabel.visibility = if (show) View.VISIBLE else View.GONE
        contactsCard.visibility = contactsLabel.visibility
        contactsCard.removeAllViews()
        if (!show) return
        val contacts = AutoAnswer.contacts(this)
        if (contacts.isEmpty()) SettingRowFactory.addRow(contactsCard, TextView(this).apply {
            setText(R.string.aa_contacts_empty)
            setTextColor(ThemeRes.color(this@AutoAnswerActivity, R.attr.appColorTextSecondary))
            textSize = 14f; gravity = Gravity.CENTER
            val p = ThemeRes.dp(this@AutoAnswerActivity, 16f)
            setPadding(p, p, p, p)
        })
        for (c in contacts) {
            val r = SettingRowFactory.build(this, 0, 0, 0, null) {
                ConfirmDialog.show(this, c.name, c.number, getString(R.string.aa_contact_remove)) {
                    AutoAnswer.setContacts(this, AutoAnswer.contacts(this).filterNot { it == c })
                    paint()
                }
            }
            r.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = c.name
            SettingRowFactory.subtitle(this, r).text = c.number
            SettingRowFactory.addRow(contactsCard, r)
        }
        SettingRowFactory.addRow(contactsCard, SettingRowFactory.build(this, R.drawable.ic_plus, R.string.aa_contacts_add, 0, null) {
            // The picker hands over one contact's number; no contacts permission is needed for that.
            runCatching { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQUEST_PICK) }
                .onFailure { PacketLogger.error("contact picker", it) }
        })
    }

    private fun tryNow() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
        tryResult.setText(R.string.va_try_running)
        VoiceAnswer.tryNow(this) { answer, detail ->
            if (isFinishing || isDestroyed) return@tryNow
            val what = getString(when (answer) {
                VoicePhrases.Answer.YES -> R.string.va_answer_yes
                VoicePhrases.Answer.NO -> R.string.va_answer_no
                VoicePhrases.Answer.UNCLEAR -> R.string.va_answer_unclear
            })
            tryResult.text = getString(R.string.va_try_result, detail, what)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Activity result API is not used in this app (no AndroidX Activity).")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                val name = c.getString(0) ?: return@use
                val number = c.getString(1) ?: return@use
                val list = AutoAnswer.contacts(this)
                if (list.none { AutoAnswer.sameNumber(it.number, number) }) AutoAnswer.setContacts(this, list + AutoAnswer.Contact(name, number))
            }
        }.onFailure { PacketLogger.error("contact read", it) }
        paint()
    }

    // --- Permissions ---

    private fun askPhonePermissions() {
        val missing = AutoAnswer.PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // Refused for good before: Android shows no dialog any more, so explain and open the app's settings.
        if (permAsked && missing.none { shouldShowRequestPermissionRationale(it) }) return showPermissionHelp(
            R.string.auto_answer_perm_title, R.string.auto_answer_perm_body)
        permAsked = true
        requestPermissions(missing.toTypedArray(), REQUEST_PHONE)
    }

    private fun askVoicePermissions() {
        val missing = VoiceAnswer.PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (voicePermAsked && missing.none { shouldShowRequestPermissionRationale(it) }) return showPermissionHelp(
            R.string.va_perm_title, R.string.va_perm_body)
        voicePermAsked = true
        requestPermissions(missing.toTypedArray(), REQUEST_VOICE)
    }

    private fun askCallLog() = ConfirmDialog.show(this, getString(R.string.aa_calllog_title), getString(R.string.aa_calllog_body),
        getString(R.string.dloc_continue)) {
        requestPermissions(arrayOf(AutoAnswer.CALL_LOG), REQUEST_CALL_LOG)
    }

    private fun showPermissionHelp(title: Int, body: Int) = ConfirmDialog.show(this, getString(title), getString(body),
        getString(R.string.perm_action), cancelRes = R.string.dialog_close) {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PHONE -> if (AutoAnswer.permitted(this)) {
                prefs().edit().putBoolean(AutoAnswer.KEY_ENABLED, true).apply()
                quietSwitch(true)
            } else showPermissionHelp(R.string.auto_answer_perm_title, R.string.auto_answer_perm_body)
            REQUEST_VOICE -> if (VoiceAnswer.permitted(this)) {
                VoiceAnswer.setEnabled(this, true)
                quietVoiceSwitch(true)
                takeMicrophone()
            } else showPermissionHelp(R.string.va_perm_title, R.string.va_perm_body)
            REQUEST_CALL_LOG -> if (!AutoAnswer.callLogPermitted(this) && !shouldShowRequestPermissionRationale(AutoAnswer.CALL_LOG))
                showPermissionHelp(R.string.aa_calllog_title, R.string.aa_calllog_body)
        }
        paint()
    }

    companion object {
        private const val REQUEST_PHONE = 61
        private const val REQUEST_CALL_LOG = 62
        private const val REQUEST_PICK = 63
        private const val REQUEST_VOICE = 64
        /** Try it now asks for the microphone alone: granting it does not switch voice answer on. */
        private const val REQUEST_MIC = 65

        /** One line for the rows that open this screen: auto-answer (off, or the delay and which calls), and voice answer. */
        fun summary(c: Context): String {
            val on = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(AutoAnswer.KEY_ENABLED, false) &&
                AutoAnswer.permitted(c)
            val voice = if (VoiceAnswer.switchedOn(c) && VoiceAnswer.permitted(c)) " · " + c.getString(R.string.va_title) else ""
            if (!on) return c.getString(R.string.auto_answer_off) + voice
            val which = c.getString(when (AutoAnswer.filter(c)) {
                AutoAnswer.Filter.ALL -> R.string.aa_filter_all
                AutoAnswer.Filter.ONLY -> R.string.aa_filter_only
                AutoAnswer.Filter.EXCEPT -> R.string.aa_filter_except
            })
            return c.getString(R.string.auto_answer_on, AutoAnswer.delaySeconds(c)) + " · " + which + voice
        }
    }
}
