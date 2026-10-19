package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.calls.AutoAnswer
import com.spizganed.quickbuds.calls.BikeMode
import com.spizganed.quickbuds.calls.VoiceAnswer
import com.spizganed.quickbuds.music.VoiceMusic

/**
 * Bike mode: on/off (as the quick settings tile), and what it turns on: auto-answer (and after how long), voice
 * answer, and voice music. A choice that needs permissions asks for them first. Settings › Calls.
 */
class BikeModeActivity : Activity() {

    private lateinit var bikeSwitch: Switch
    private lateinit var bikeSub: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var delaySub: TextView
    private lateinit var voiceSwitch: Switch
    private lateinit var musicSwitch: Switch
    private lateinit var musicSub: TextView
    /** Permission requests refused for good: Android shows no dialog any more, so the app's settings open. */
    private val asked = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.bike_tile_label))

        bikeSwitch = SettingRowFactory.buildSwitch(this, BikeMode.isOn(this))
        val bikeRow = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.bike_tile_label, 0, bikeSwitch) { bikeSwitch.performClick() }
        bikeSub = SettingRowFactory.subtitle(this, bikeRow)
        bikeSwitch.setOnCheckedChangeListener(bikeListener)
        root.addView(SettingRowFactory.card(this).apply { SettingRowFactory.addRow(this, bikeRow) })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.bike_when_on))
        autoSwitch = SettingRowFactory.buildSwitch(this, BikeMode.autoAnswer(this))
        val autoRow = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.auto_answer_title, 0, autoSwitch) { autoSwitch.performClick() }
        autoSwitch.setOnCheckedChangeListener(autoListener)
        val delayRow = SettingRowFactory.build(this, 0, R.string.auto_answer_delay_title, 0, SettingRowFactory.buildChevron(this)) {
            BottomSheetDialog(this).title(getString(R.string.auto_answer_delay_title)).items(AutoAnswer.DELAYS.map { s ->
                BottomSheetDialog.Item(getString(R.string.auto_answer_seconds, s), s == BikeMode.delaySeconds(this)) {
                    BikeMode.choose(this) { it.putInt(BikeMode.KEY_DELAY, s) }
                    paint()
                }
            }).show()
        }
        delaySub = SettingRowFactory.subtitle(this, delayRow)
        voiceSwitch = SettingRowFactory.buildSwitch(this, BikeMode.voiceAnswer(this))
        val voiceRow = SettingRowFactory.build(this, R.drawable.ic_earbud, R.string.va_title, 0, voiceSwitch) { voiceSwitch.performClick() }
        voiceSwitch.setOnCheckedChangeListener(voiceListener)
        // Voice music: the switch chooses it; the row opens its own screen (music app, speech model, access).
        musicSwitch = SettingRowFactory.buildSwitch(this, VoiceMusic.switchedOn(this))
        val musicRow = SettingRowFactory.build(this, R.drawable.ic_bike, R.string.vm_title, 0, musicSwitch) {
            startActivity(Intent(this, VoiceMusicActivity::class.java))
        }
        musicSub = SettingRowFactory.subtitle(this, musicRow)
        musicSwitch.setOnCheckedChangeListener(musicListener)
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, autoRow)
            SettingRowFactory.addRow(this, delayRow)
            SettingRowFactory.addRow(this, voiceRow)
            SettingRowFactory.addRow(this, musicRow)
        })
        root.addView(TextView(this).apply {
            setText(R.string.bike_note); textSize = 13f
            setTextColor(ThemeRes.color(this@BikeModeActivity, R.attr.appColorTextSecondary))
            val p = ThemeRes.dp(this@BikeModeActivity, 4f)
            setPadding(p, ThemeRes.dp(this@BikeModeActivity, 10f), p, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@BikeModeActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun missing(perms: Array<String>) = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Asks for [perms]; refused for good before, opens the app's settings instead. True: all granted already. */
    private fun ask(perms: List<String>, request: Int): Boolean {
        if (perms.isEmpty()) return true
        if (request in asked && perms.none { shouldShowRequestPermissionRationale(it) }) {
            ConfirmDialog.show(this, getString(R.string.va_perm_title), getString(R.string.va_perm_body),
                getString(R.string.perm_action), cancelRes = R.string.dialog_close) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            return false
        }
        asked += request
        requestPermissions(perms.toTypedArray(), request)
        return false
    }

    /** This screen is open: the background service may take the microphone now (voice answer, voice music). */
    private fun takeMicrophone() = startService(Intent(this, BudsService::class.java)
        .setAction(BudsService.ACTION_VOICE_PROBE).putExtra(BudsService.EXTRA_FIND_ON, true))

    private val bikeListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(BikeMode.missingPermissions(this), REQUEST_BIKE)) return@OnCheckedChangeListener paint()
        BikeMode.set(this, on)
        if (on) takeMicrophone()
        paint()
    }

    private val autoListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(AutoAnswer.PERMISSIONS), REQUEST_AUTO)) return@OnCheckedChangeListener paint()
        BikeMode.choose(this) { it.putBoolean(BikeMode.KEY_AUTO_ANSWER, on) }
        paint()
    }

    private val voiceListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(VoiceAnswer.PERMISSIONS), REQUEST_VOICE)) return@OnCheckedChangeListener paint()
        BikeMode.choose(this) { it.putBoolean(BikeMode.KEY_VOICE_ANSWER, on) }
        if (on) takeMicrophone()
        paint()
    }

    private val musicListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on && !ask(missing(MUSIC_PERMISSIONS), REQUEST_MUSIC)) return@OnCheckedChangeListener paint()
        VoiceMusic.setEnabled(this, on)
        if (on) takeMicrophone()
        // Not set up yet (no music app, model or access): its screen shows what is missing.
        if (on && VoiceMusic.app(this) == null) startActivity(Intent(this, VoiceMusicActivity::class.java))
        paint()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) return paint()
        // Granted: do what the switch asked for.
        when (requestCode) {
            REQUEST_BIKE -> { BikeMode.set(this, true); takeMicrophone() }
            REQUEST_AUTO -> BikeMode.choose(this) { it.putBoolean(BikeMode.KEY_AUTO_ANSWER, true) }
            REQUEST_VOICE -> { BikeMode.choose(this) { it.putBoolean(BikeMode.KEY_VOICE_ANSWER, true) }; takeMicrophone() }
            REQUEST_MUSIC -> { VoiceMusic.setEnabled(this, true); takeMicrophone() }
        }
        paint()
    }

    /** The switches as saved (a refused permission leaves them off), and the subtitles. */
    private fun paint() {
        quiet(bikeSwitch, BikeMode.isOn(this), bikeListener)
        quiet(autoSwitch, BikeMode.autoAnswer(this), autoListener)
        quiet(voiceSwitch, BikeMode.voiceAnswer(this), voiceListener)
        quiet(musicSwitch, VoiceMusic.switchedOn(this), musicListener)
        bikeSub.text = summary(this)
        delaySub.text = getString(R.string.auto_answer_seconds, BikeMode.delaySeconds(this))
        musicSub.text = VoiceMusicActivity.summary(this)
    }

    private fun quiet(sw: Switch, on: Boolean, l: CompoundButton.OnCheckedChangeListener) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = on
        sw.setOnCheckedChangeListener(l)
    }

    companion object {
        private const val REQUEST_BIKE = 91
        private const val REQUEST_AUTO = 92
        private const val REQUEST_VOICE = 93
        private const val REQUEST_MUSIC = 94
        /** Voice music: the microphone, the call state, and answer-calls for "Hey Jarvis, cut the call". */
        private val MUSIC_PERMISSIONS = arrayOf(android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS)

        /** One line: on or off, then what it turns on. */
        fun summary(c: Context): String {
            val chosen = listOfNotNull(
                if (BikeMode.autoAnswer(c)) c.getString(R.string.auto_answer_title) else null,
                if (BikeMode.voiceAnswer(c)) c.getString(R.string.va_title) else null,
                if (VoiceMusic.switchedOn(c)) c.getString(R.string.vm_title) else null
            )
            return (listOf(c.getString(if (BikeMode.isOn(c)) R.string.bike_tile_on else R.string.bike_tile_off)) + chosen).joinToString(" · ")
        }
    }
}
