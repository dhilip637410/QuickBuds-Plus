package com.spizganed.quickbuds.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.notify.BatteryCard
import com.spizganed.quickbuds.notify.LowBatteryAlert
import com.spizganed.quickbuds.proximity.CaseReminderMonitor
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.proximity.ProximityMonitor
import com.spizganed.quickbuds.proximity.ProximityState
import com.spizganed.quickbuds.proximity.Sensitivity
import com.spizganed.quickbuds.usage.Usage
import com.spizganed.quickbuds.usage.UsageRetention

/**
 * Settings, a full screen reached from the header cog. Same layout pattern
 * as Earbud settings: title, then sectioned cards. Toggles save on change; no confirmation.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_title))

        fun section(labelRes: Int, vararg rows: android.view.View) {
            root.addView(SettingRowFactory.sectionLabel(this, labelRes))
            val card = SettingRowFactory.card(this)
            rows.forEach { SettingRowFactory.addRow(card, it) }
            root.addView(card)
        }

        fun link(icon: Int, title: Int, sub: Int, onClick: () -> Unit) =
            SettingRowFactory.build(this, icon, title, sub, SettingRowFactory.buildChevron(this), onClick = onClick)

        fun toggle(icon: Int, title: Int, sub: Int, key: String, default: Boolean): LinearLayout {
            val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, default))
            sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
        }

        val themeRow = link(R.drawable.ic_palette, R.string.theme_title, 0) {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        themeSubtitle = SettingRowFactory.subtitle(this, themeRow)
        val layoutRow = link(R.drawable.ic_layout, R.string.settings_layout_title, R.string.settings_layout_sub) {
            startActivity(Intent(this, HomeLayoutActivity::class.java))
        }
        section(R.string.settings_appearance, themeRow, layoutRow)

        val general = mutableListOf<android.view.View>(
            toggle(R.drawable.ic_haptics, R.string.settings_haptics_title, R.string.settings_haptics_sub, KEY_HAPTICS, true),
            backgroundRow(prefs),
            toggle(R.drawable.ic_case, R.string.settings_clear_battery_title, R.string.settings_clear_battery_sub,
                BudsService.PREF_CLEAR_BATTERY, false),
            toggle(R.drawable.ic_dev_tools, R.string.settings_devtools_title, R.string.settings_devtools_sub, KEY_DEV_TOOLS_BUTTON, true)
        )
        general.add(0, link(R.drawable.ic_language, R.string.settings_language_title, R.string.settings_language_sub) {
            startActivity(Intent(this, LanguageActivity::class.java))
        })
        section(R.string.settings_general, *general.toTypedArray())

        // --- Usage statistics and loss protection: every supported model, see usage/ and proximity/ ---
        val usageSwitch = SettingRowFactory.buildSwitch(this, Usage.enabled(this))
        usageSwitch.setOnCheckedChangeListener { _, on -> Usage.setEnabled(this, on) }
        val retentionRow = link(R.drawable.ic_delete, R.string.settings_retention_title, 0) { retentionSheet() }
        retentionSubtitle = SettingRowFactory.subtitle(this, retentionRow)
        val sensitivityRow = link(R.drawable.ic_warning, R.string.settings_loss_sensitivity, 0) { sensitivitySheet() }
        sensitivitySubtitle = SettingRowFactory.subtitle(this, sensitivityRow)
        section(
            R.string.settings_usage_section,
            link(R.drawable.ic_usage, R.string.usage_title, R.string.settings_stats_sub) {
                startActivity(Intent(this, UsageActivity::class.java))
            },
            SettingRowFactory.build(this, R.drawable.ic_usage, R.string.settings_usage_title, R.string.settings_usage_sub, usageSwitch) {
                usageSwitch.performClick()
            },
            retentionRow,
            lossRow(prefs),
            link(R.drawable.ic_place, R.string.dloc_title, R.string.dloc_sub) {
                startActivity(Intent(this, DisconnectPlacesActivity::class.java))
            },
            caseRow(prefs),
            batteryRow(prefs),
            lowBatteryRow(prefs),
            sensitivityRow
        )

        // Calls: auto-answer and voice answer, for any earbuds; here because Earbud settings is hidden while other
        // earbuds are selected.
        val callsRow = link(R.drawable.ic_earbud, R.string.auto_answer_title, 0) {
            startActivity(Intent(this, AutoAnswerActivity::class.java))
        }
        callsSubtitle = SettingRowFactory.subtitle(this, callsRow)
        // Bike mode: what it turns on (auto-answer, voice answer, voice music), and on/off as the tile.
        val bikeRow = link(R.drawable.ic_bike, R.string.bike_tile_label, 0) {
            startActivity(Intent(this, BikeModeActivity::class.java))
        }
        bikeSubtitle = SettingRowFactory.subtitle(this, bikeRow)
        section(R.string.auto_answer_section, callsRow, bikeRow)

        val version = UpdateChecker.installed(this)
        val updateRow = link(R.drawable.ic_update, R.string.row_update_title, 0) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        SettingRowFactory.subtitle(this, updateRow).text = getString(R.string.settings_update_sub, version)
        section(
            R.string.settings_app,
            updateRow,
            link(R.drawable.ic_dev_tools, R.string.problem_title, R.string.problem_sub) {
                startActivity(Intent(this, ReportActivity::class.java))
            },
            link(R.drawable.ic_info, R.string.settings_about_title, R.string.settings_about_sub) {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@SettingsActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    private var themeSubtitle: android.widget.TextView? = null
    private var callsSubtitle: android.widget.TextView? = null
    private var bikeSubtitle: android.widget.TextView? = null

    /**
     * Background service switch. Turning it off asks first (the widget and the automatic reconnect
     * need the service); Cancel puts the switch back without saving.
     */
    private fun backgroundRow(prefs: android.content.SharedPreferences): LinearLayout {
        val key = BudsService.PREF_BACKGROUND
        val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, true))
        var reverting = false
        sw.setOnCheckedChangeListener { _, on ->
            if (reverting) return@setOnCheckedChangeListener
            if (on) {
                // The service is still bound by the open main screen, so nothing needs restarting.
                prefs.edit().putBoolean(key, true).apply()
                return@setOnCheckedChangeListener
            }
            reverting = true
            sw.isChecked = true
            reverting = false
            ConfirmDialog.show(
                this, getString(R.string.settings_background_warn_title),
                getString(R.string.settings_background_warn_body),
                getString(R.string.settings_background_warn_action)
            ) {
                prefs.edit().putBoolean(key, false).apply()
                reverting = true
                sw.isChecked = false
                reverting = false
            }
        }
        return SettingRowFactory.build(
            this, R.drawable.ic_power, R.string.settings_background_title, R.string.settings_background_sub, sw
        ) { sw.performClick() }
    }

    private var retentionSubtitle: android.widget.TextView? = null
    private var sensitivitySubtitle: android.widget.TextView? = null
    private var lossSubtitle: android.widget.TextView? = null
    /** The notifying switch waiting for the permission answer, and its key. */
    private var pending: Pair<String, android.widget.Switch>? = null

    /** A notification row: its setting, the channel it posts on, and the subtitle that says when Android blocks it. */
    private class NotifyRow(val key: String, val default: Boolean, val channel: String, val sub: android.widget.TextView, val subRes: Int)
    private val notifyRows = ArrayList<NotifyRow>()

    private fun channelOf(key: String) = when (key) {
        BatteryCard.KEY_ENABLED -> BatteryCard.CHANNEL
        LowBatteryAlert.KEY_ENABLED -> LowBatteryAlert.CHANNEL
        else -> LossNotifier.CHANNEL
    }

    /**
     * On but blocked (permission denied, the app or the channel off in Android): the subtitle says so. Was: the
     * switch showed on and nothing ever appeared.
     */
    private fun renderNotifyRows() {
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        for (r in notifyRows) {
            val blocked = prefs.getBoolean(r.key, r.default) && !LossNotifier.canNotify(this, r.channel)
            r.sub.setText(if (blocked) R.string.loss_perm_title else r.subRes)
        }
    }

    private fun watchNotify(row: LinearLayout, key: String, default: Boolean, subRes: Int): LinearLayout {
        notifyRows += NotifyRow(key, default, channelOf(key), SettingRowFactory.subtitle(this, row), subRes)
        return row
    }

    /**
     * A switch for a feature that notifies. Turning it on asks for the notification permission first
     * (Android 13+): a warning nobody sees is no warning. Denied: the switch goes back off and a sheet says why.
     */
    private fun notifyingSwitch(prefs: android.content.SharedPreferences, key: String, default: Boolean): android.widget.Switch {
        val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, default))
        sw.setOnCheckedChangeListener { _, on ->
            if (!on) {
                prefs.edit().putBoolean(key, false).apply()
                renderLossStatus()
                renderNotifyRows()
                return@setOnCheckedChangeListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pending = key to sw
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                return@setOnCheckedChangeListener
            }
            enable(key)
        }
        return sw
    }

    private fun lossRow(prefs: android.content.SharedPreferences): LinearLayout {
        val sw = notifyingSwitch(prefs, ProximityMonitor.KEY_ENABLED, false)
        val row = SettingRowFactory.build(
            this, R.drawable.ic_find_buds, R.string.settings_loss_title, R.string.settings_loss_sub, sw
        ) { sw.performClick() }
        lossSubtitle = SettingRowFactory.subtitle(this, row)
        return row
    }

    /** "Only one earbud is in the case" ([com.spizganed.quickbuds.proximity.CaseReminder]). */
    private fun caseRow(prefs: android.content.SharedPreferences): LinearLayout {
        val sw = notifyingSwitch(prefs, CaseReminderMonitor.KEY_ENABLED, true)
        return watchNotify(SettingRowFactory.build(
            this, R.drawable.ic_case, R.string.settings_case_reminder_title, R.string.settings_case_reminder_sub, sw
        ) { sw.performClick() }, CaseReminderMonitor.KEY_ENABLED, true, R.string.settings_case_reminder_sub)
    }

    /** The live battery card in the notifications ([com.spizganed.quickbuds.notify.BatteryCard]). */
    private fun batteryRow(prefs: android.content.SharedPreferences): LinearLayout {
        val sw = notifyingSwitch(prefs, BatteryCard.KEY_ENABLED, true)
        return watchNotify(SettingRowFactory.build(
            this, R.drawable.ic_earbud, R.string.settings_battery_card_title, R.string.settings_battery_card_sub, sw
        ) { sw.performClick() }, BatteryCard.KEY_ENABLED, true, R.string.settings_battery_card_sub)
    }

    /** "Charge it" cards ([com.spizganed.quickbuds.notify.LowBatteryAlert]). */
    private fun lowBatteryRow(prefs: android.content.SharedPreferences): LinearLayout {
        val sw = notifyingSwitch(prefs, LowBatteryAlert.KEY_ENABLED, true)
        return watchNotify(SettingRowFactory.build(
            this, R.drawable.ic_bolt, R.string.settings_low_title, R.string.settings_low_sub, sw
        ) { sw.performClick() }, LowBatteryAlert.KEY_ENABLED, true, R.string.settings_low_sub)
    }

    private fun enable(key: String) {
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, true).apply()
        renderLossStatus()
        renderNotifyRows()
        // Allowed, but blocked in Android's settings (the app or this feature's channel).
        if (!LossNotifier.canNotify(this, channelOf(key))) notificationSheet()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val (key, sw) = pending ?: return
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) return enable(key)
        sw.isChecked = false
        notificationSheet()
    }

    private fun notificationSheet() = ConfirmDialog.show(
        this, getString(R.string.loss_perm_title), getString(R.string.loss_perm_body), getString(R.string.perm_action),
        cancelRes = R.string.dialog_close
    ) {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** The loss row's subtitle: what it does when off, what it is doing (or why it cannot) when on. */
    private fun renderLossStatus() {
        val sub = lossSubtitle ?: return
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(ProximityMonitor.KEY_ENABLED, false)) return sub.setText(R.string.settings_loss_sub)
        if (!BudsService.backgroundAllowed(this)) return sub.setText(R.string.settings_loss_needs_background)
        if (!LossNotifier.canNotify(this)) return sub.setText(R.string.loss_perm_title)
        val state = ProximityMonitor.lastState
        sub.setText(when (state) {
            ProximityState.UNSUPPORTED -> R.string.settings_loss_unsupported
            ProximityState.IN_CASE -> R.string.loss_state_case
            ProximityState.WORN -> R.string.loss_state_worn
            ProximityState.MONITORING, ProximityState.WEAK_SIGNAL, ProximityState.WARNING_SENT,
            ProximityState.RECOVERED -> R.string.loss_state_active
            else -> R.string.loss_state_waiting
        })
        // Not connected now: what the last buds showed decides.
        if (state == ProximityState.IDLE || state == ProximityState.DISCONNECTED) {
            val address = runCatching { BudsDevice.find(this)?.address }.getOrNull() ?: return
            Usage.capabilities(this, address) { caps -> if (caps.rssi == false) sub.setText(R.string.settings_loss_unsupported) }
        }
    }

    private fun retentionSheet() {
        val current = Usage.retentionDays(this)
        BottomSheetDialog(this)
            .title(getString(R.string.settings_retention_title))
            .message(getString(R.string.settings_retention_body))
            .items(UsageRetention.CHOICES.map { d ->
                BottomSheetDialog.Item(UsageFormat.days(this, d), d == current) {
                    Usage.setRetentionDays(this, d)
                    renderUsageValues()
                }
            })
            .show()
    }

    private fun sensitivitySheet() {
        val current = ProximityMonitor.sensitivity(this)
        BottomSheetDialog(this)
            .title(getString(R.string.settings_loss_sensitivity))
            .message(getString(R.string.settings_loss_sensitivity_body))
            .items(Sensitivity.values().map { s ->
                BottomSheetDialog.Item(getString(sensitivityName(s)), s == current) {
                    getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(ProximityMonitor.KEY_SENSITIVITY, s.name).apply()
                    renderUsageValues()
                }
            })
            .show()
    }

    private fun sensitivityName(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> R.string.sensitivity_low
        Sensitivity.MEDIUM -> R.string.sensitivity_medium
        Sensitivity.HIGH -> R.string.sensitivity_high
    }

    private fun renderUsageValues() {
        retentionSubtitle?.text = UsageFormat.days(this, Usage.retentionDays(this))
        sensitivitySubtitle?.setText(sensitivityName(ProximityMonitor.sensitivity(this)))
        renderLossStatus()
    }

    override fun onResume() {
        super.onResume()
        themeSubtitle?.text = ThemeRes.palette(this).name
        callsSubtitle?.text = AutoAnswerActivity.summary(this)
        bikeSubtitle?.text = BikeModeActivity.summary(this)
        renderUsageValues()
        // Back from Android's notification settings: the rows say what is allowed now.
        renderNotifyRows()
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 41
        const val KEY_HAPTICS = "haptics"
        const val KEY_DEV_TOOLS_BUTTON = "devToolsButton"
    }
}
