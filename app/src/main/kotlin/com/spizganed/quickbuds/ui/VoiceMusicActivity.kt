package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.calls.BikeMode
import com.spizganed.quickbuds.music.VoiceMusic

/**
 * Voice music settings: on/off, the music app (every installed app that plays media), notification access (what
 * Android requires to control another app's music), and the commands. It listens only while Bike mode is on.
 */
class VoiceMusicActivity : Activity() {

    private lateinit var sw: Switch
    private lateinit var status: TextView
    private lateinit var appSub: TextView
    private lateinit var accessSub: TextView
    private lateinit var modelSub: TextView
    /** The download's last percent, or -1 when none runs; -2 after a failure. */
    private var modelPct = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        fun dp(v: Float) = ThemeRes.dp(this, v)
        fun note(text: String) = TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(ThemeRes.color(this@VoiceMusicActivity, R.attr.appColorTextSecondary))
            setPadding(dp(4f), dp(10f), dp(4f), 0)
        }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.vm_title))

        sw = SettingRowFactory.buildSwitch(this, isOn())
        val row = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.vm_title, 0, sw) { sw.performClick() }
        status = SettingRowFactory.subtitle(this, row)
        sw.setOnCheckedChangeListener(listener)
        val appRow = SettingRowFactory.build(this, 0, R.string.vm_app_title, 0, SettingRowFactory.buildChevron(this)) { pickApp() }
        appSub = SettingRowFactory.subtitle(this, appRow)
        val accessRow = SettingRowFactory.build(this, 0, R.string.vm_access_title, 0, SettingRowFactory.buildChevron(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        accessSub = SettingRowFactory.subtitle(this, accessRow)
        val modelRow = SettingRowFactory.build(this, 0, R.string.vm_model_title, 0, null) { downloadModel() }
        modelSub = SettingRowFactory.subtitle(this, modelRow)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, row)
            SettingRowFactory.addRow(this, appRow)
            SettingRowFactory.addRow(this, accessRow)
            SettingRowFactory.addRow(this, modelRow)
        })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.va_say_title))
        root.addView(note(getString(R.string.vm_wake)).apply { setPadding(dp(4f), 0, dp(4f), dp(8f)) })
        root.addView(SettingRowFactory.card(this).apply {
            setPadding(dp(16f), dp(12f), dp(16f), dp(14f))
            addView(TextView(this@VoiceMusicActivity).apply {
                textSize = 14f
                setTextColor(ThemeRes.color(this@VoiceMusicActivity, R.attr.appColorTextPrimary))
                setText(R.string.vm_commands)
            })
        })
        root.addView(note(getString(R.string.vm_note)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@VoiceMusicActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        quietSwitch(isOn())
        paint()
    }

    private fun isOn() = VoiceMusic.switchedOn(this) &&
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val listener = CompoundButton.OnCheckedChangeListener { _, on ->
        // The microphone, the call state, and answer-calls for "Hey Jarvis, cut the call".
        val missing = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ANSWER_PHONE_CALLS).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (on && missing.isNotEmpty()) {
            quietSwitch(false)
            requestPermissions(missing.toTypedArray(), REQUEST_MIC)
            return@OnCheckedChangeListener
        }
        VoiceMusic.setEnabled(this, on)
        if (on) takeMicrophone()
        if (on && VoiceMusic.app(this) == null) pickApp()
        paint()
    }

    /** This screen is open: the background service may take the microphone now, for use with Bike mode. */
    private fun takeMicrophone() = startService(Intent(this, BudsService::class.java)
        .setAction(BudsService.ACTION_VOICE_PROBE).putExtra(BudsService.EXTRA_FIND_ON, true))

    private fun quietSwitch(on: Boolean) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = on
        sw.setOnCheckedChangeListener(listener)
    }

    private fun pickApp() {
        val apps = VoiceMusic.apps(this)
        if (apps.isEmpty()) return ConfirmDialog.show(this, getString(R.string.vm_app_title), getString(R.string.vm_app_empty),
            getString(R.string.dialog_close), cancelRes = null)
        val chosen = VoiceMusic.app(this)
        BottomSheetDialog(this).title(getString(R.string.vm_app_title)).items(apps.map { a ->
            BottomSheetDialog.Item(a.label, a.pkg == chosen) { VoiceMusic.setApp(this, a.pkg); paint() }
        }).show()
    }

    /** The offline speech model: once, about 37 MB, from the Vosk project. */
    private fun downloadModel() {
        if (com.spizganed.quickbuds.music.VoskModel.ready(this) || com.spizganed.quickbuds.music.VoskModel.downloading) return
        modelPct = 0
        paint()
        com.spizganed.quickbuds.music.VoskModel.download(this, { pct ->
            runOnUiThread { modelPct = pct; if (!isFinishing) paint() }
        }) { ok ->
            runOnUiThread {
                modelPct = if (ok) -1 else -2
                // Ready: the background service may start listening now (Bike mode on).
                if (ok) takeMicrophone()
                if (!isFinishing) paint()
            }
        }
    }

    private fun paint() {
        status.text = summary(this)
        appSub.text = VoiceMusic.app(this)?.let { VoiceMusic.label(this, it) } ?: getString(R.string.vm_app_none)
        accessSub.setText(if (VoiceMusic.accessAllowed(this)) R.string.vm_access_on else R.string.vm_access_off)
        modelSub.text = when {
            com.spizganed.quickbuds.music.VoskModel.ready(this) -> getString(R.string.vm_model_ready)
            modelPct >= 0 || com.spizganed.quickbuds.music.VoskModel.downloading -> getString(R.string.vm_model_downloading, maxOf(modelPct, 0))
            modelPct == -2 -> getString(R.string.vm_model_failed)
            else -> getString(R.string.vm_model_missing)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoiceMusic.setEnabled(this, true)
            quietSwitch(true)
            takeMicrophone()
            if (VoiceMusic.app(this) == null) pickApp()
        }
        paint()
    }

    companion object {
        private const val REQUEST_MIC = 81

        /** One line: off, or the first thing it still needs, or listening. */
        fun summary(c: Context): String = c.getString(when {
            !VoiceMusic.switchedOn(c) -> R.string.va_sub_off
            VoiceMusic.app(c) == null -> R.string.vm_sub_app
            !VoiceMusic.accessAllowed(c) -> R.string.vm_sub_access
            !com.spizganed.quickbuds.music.VoskModel.ready(c) -> R.string.vm_sub_model
            !BudsService.microphoneReady -> R.string.va_sub_mic
            !BikeMode.isOn(c) -> R.string.vm_sub_bike
            else -> R.string.vm_sub_on
        })
    }
}
