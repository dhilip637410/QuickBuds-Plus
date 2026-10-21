package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.app.Notification
import android.content.pm.ServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.notify.BatteryCard
import com.spizganed.quickbuds.notify.LowBatteryAlert
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.proximity.CaseReminderMonitor
import com.spizganed.quickbuds.proximity.DropReason
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.proximity.ProximityMonitor
import com.spizganed.quickbuds.ui.OnCallGesture
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.usage.Usage
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.WidgetSettings
import com.spizganed.quickbuds.widget.WidgetStateStore

class BudsService : Service(), BudsConnectionManager.Listener {

    private val binder = LocalBinder()
    var manager: BudsConnectionManager? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    private fun statusLog(msg: String) {
        Log.d("BudsConn", msg)
    }

    private val widgetCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WIDGET_COMMAND) return
            statusLog("<< WIDGET broadcast")
            executeWidgetCommand(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BudsService = this@BudsService
    }

    /** Loss protection rides this service's link ([ProximityMonitor]). */
    private var proximity: ProximityMonitor? = null
    /** "Only one earbud is in the case", from the wear reports ([CaseReminderMonitor]). */
    private var caseReminder: CaseReminderMonitor? = null
    /** Earbuds of any other brand: loss warning, battery card and alerts over standard Bluetooth. */
    private var other: OtherEarbudsMonitor? = null
    /** Auto-answer: a ringing call answered after the delay while the earbuds are connected. */
    private var autoAnswer: com.spizganed.quickbuds.calls.AutoAnswerMonitor? = null

    /**
     * Connects and disconnects of any headphones, heard here too while the service runs. Some phones hold the
     * manifest broadcasts back from apps ([KeepAliveReceiver]); a missed disconnect kept a usage session and a
     * battery card going. Every action here is idempotent, so hearing an event twice changes nothing.
     */
    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = androidx.core.content.IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val buds = BudsDevice.isBuds(this@BudsService, device)
            if (!buds && !AudioDevice.isAudio(device)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> Usage.connected(this@BudsService, device, newLink = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Usage.disconnected(this@BudsService, device.address)
                    if (!buds) {
                        val adapterOn = getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_ON
                        other?.onDisconnected(device.address, if (adapterOn) DropReason.LINK_LOST else DropReason.BLUETOOTH_OFF)
                    }
                }
                else -> when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (!buds && OtherEarbudsMonitor.wanted(this@BudsService)) other?.onConnected(device)
                    BluetoothProfile.STATE_DISCONNECTED ->
                        if (buds) checkAudioDisconnectedOutside(device.address) else other?.onMaybeGone()
                }
            }
        }
    }

    /** The battery level a headset reports through standard Bluetooth: for earbuds of other brands. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = androidx.core.content.IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val level = intent.getIntExtra(AudioDevice.EXTRA_BATTERY_LEVEL, -1)
            other?.onBattery(device, level)
        }
    }

    /** Bluetooth turning off ends every usage session; the ACL broadcasts are not guaranteed then. */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                statusLog("[SVC] Bluetooth off: usage sessions end")
                Usage.allDisconnected(this@BudsService)
                other?.onAllDisconnected(DropReason.BLUETOOTH_OFF)
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            ProximityMonitor.KEY_ENABLED, ProximityMonitor.KEY_SENSITIVITY, PREF_BACKGROUND -> {
                autoAnswer?.update()
                proximity?.onSettingsChanged()
                other?.onSettingsChanged()
                discoverOther()
            }
            CaseReminderMonitor.KEY_ENABLED -> caseReminder?.onSettingsChanged()
            com.spizganed.quickbuds.calls.AutoAnswer.KEY_ENABLED, com.spizganed.quickbuds.calls.AutoAnswer.KEY_DELAY,
            com.spizganed.quickbuds.calls.VoiceProbe.KEY_ARMED -> {
                autoAnswer?.update()
            }
            // Voice music listens while Bike mode is on: any of these can start or stop it.
            com.spizganed.quickbuds.calls.BikeMode.KEY_ON, com.spizganed.quickbuds.music.VoiceMusic.KEY_ENABLED,
            com.spizganed.quickbuds.music.VoiceMusic.KEY_APP -> {
                voiceMusic.sync()
                if (key == com.spizganed.quickbuds.music.VoiceMusic.KEY_ENABLED && !micWanted()) setMicrophone(false)
            }
            com.spizganed.quickbuds.calls.VoiceAnswer.KEY_ENABLED -> {
                autoAnswer?.update()
                // Turned off: the microphone goes now. Turned on: it comes when an app screen asks (it is open now).
                if (!micWanted()) setMicrophone(false)
            }
            com.spizganed.quickbuds.proximity.DisconnectPlaces.KEY_ENABLED -> syncLocationBuffer()
            BatteryCard.KEY_ENABLED -> { BatteryCard.update(this); other?.onSettingsChanged(); discoverOther() }
            LowBatteryAlert.KEY_ENABLED -> {
                if (!LowBatteryAlert.enabled(this)) LowBatteryAlert.turnedOff(this)
                discoverOther()
            }
            Usage.KEY_ENABLED -> if (Usage.enabled(this)) usageHeartbeat()
        }
    }

    override fun onCreate() {
        super.onCreate()
        statusLog("[SVC] onCreate")
        // Alarms do not survive a reboot or an update: a sleep timer still due is set again.
        com.spizganed.quickbuds.sleep.SleepTimer.rearm(this)
        // A new service has no link yet: a battery card left by a killed process is stale (and fixed, so the user
        // could not clear it). The store says so too, so nothing posts it again until the buds report.
        WidgetStateStore.read(this).takeIf { it.connected }?.let { WidgetStateStore.write(this, it.copy(connected = false)) }
        BatteryCard.hide(this)
        BatteryCard.hideAllOther(this)

        manager = BudsConnectionManager(this).also { it.addListener(this) }
        proximity = ProximityMonitor(this).also { it.connected = { manager?.isConnected() == true } }
        caseReminder = CaseReminderMonitor(this)
        other = OtherEarbudsMonitor(this).also { it.supportedBudsConnected = { manager?.isConnected() == true } }
        autoAnswer = com.spizganed.quickbuds.calls.AutoAnswerMonitor(this).also {
            it.onProbeDone = {
                handler.postDelayed({ if (!micWanted()) setMicrophone(false) }, 15_000L)
            }
            it.update()
        }
        ConnectedDevices.addListener(connectedDevicesListener)
        // A new version on GitHub: a notification, also with the app closed (an hourly job, kept by Android).
        com.spizganed.quickbuds.ui.UpdateJob.sync(this)

        // Not exported on every Android version: before 13 a plain registration let any app send widget commands
        // (noise control, game mode) to the buds. androidx guards older versions with a signature permission.
        androidx.core.content.ContextCompat.registerReceiver(this, widgetCommandReceiver,
            IntentFilter(ACTION_WIDGET_COMMAND), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // A protected system broadcast: exported or not changes nothing, so the plain call.
        registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // Sent by the system (protected), so exported or not changes nothing; the flag only quiets the check.
        val battery = IntentFilter(AudioDevice.ACTION_BATTERY_LEVEL_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(batteryReceiver, battery, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(batteryReceiver, battery)
        val links = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(linkReceiver, links, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(linkReceiver, links)
        // Other earbuds already connected when the process started get no broadcast: look for them.
        handler.postDelayed({ if (OtherEarbudsMonitor.wanted(this)) other?.discover() }, OTHER_DISCOVER_DELAY_MS)
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)

        startForegroundService()
        // Once the link had time to come up: end sessions whose disconnect this process never saw.
        handler.postDelayed({ reconcileUsage() }, RECONCILE_DELAY_MS)
    }

    /**
     * The phone's audio to our buds went down, from outside the app (Android's Bluetooth settings, the quick panel).
     * Our RFCOMM link alone keeps the buds half connected: still "connected" here, so the battery card stayed and
     * the usage session ran on. When Bluetooth lists no audio to them a moment later, that was the user's disconnect:
     * our link closes too, and with it the session, the card and the loss warning.
     */
    private fun checkAudioDisconnectedOutside(address: String) {
        handler.postDelayed({
            if (manager?.isConnected() != true || manager?.device?.address != address) return@postDelayed
            ConnectedAudio.query(this) { connected ->
                if (!OutsideDisconnect.shouldClose(manager?.isConnected() == true, manager?.device?.address, address,
                        connected?.keys)) return@query
                statusLog("[SVC] Phone audio disconnected outside the app: closing our link too")
                manager?.disconnect()
                Usage.disconnected(this, address)
            }
        }, AUDIO_GONE_CHECK_MS)
    }

    /**
     * A feature for other devices was turned on: pick up the ones already connected. Was: only a device that
     * connected after the change was seen.
     */
    private fun discoverOther() {
        if (OtherEarbudsMonitor.wanted(this)) other?.discover()
    }

    /** A sign of life from the connected buds: keeps their usage session's last-seen time fresh. */
    private fun usageHeartbeat() {
        if (manager?.isConnected() != true) return
        manager?.device?.let { Usage.connected(this, it) }
    }

    /**
     * Closes usage sessions left open by a process that died without seeing the disconnect. Connected =
     * our link's buds, or any device with phone audio (A2DP or headset) up.
     */
    private fun reconcileUsage() {
        ConnectedAudio.query(this) { connected ->
            if (connected == null) return@query Usage.reconcile(this, null)
            val found = connected.keys.toMutableSet()
            manager?.takeIf { it.isConnected() }?.device?.let { found += it.address }
            Usage.reconcile(this, found)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        statusLog("[SVC] onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_FORCE_CONNECT -> {
                val withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                // A delayed connect is the ACL fallback (KeepAliveReceiver): the audio-profile
                // broadcast normally connects first, and then this finds us connected and does nothing.
                if (delayMs > 0) handler.postDelayed({ forceConnect(withAudio) }, delayMs)
                else forceConnect(withAudio)
            }
            ACTION_FORCE_DISCONNECT -> {
                statusLog("[SVC] FORCE_DISCONNECT")
                manager?.disconnect()
                // Only the deliberate disconnect drops phone audio — the manager's own
                // disconnect() also runs after a failed connect attempt.
                BudsDevice.find(this)?.let { manager?.setPhoneAudio(it, on = false) }
            }
            ACTION_TOGGLE_BRIDGE -> manager?.setBridge(!RfcommBridge.running)
            ACTION_WIDGET_COMMAND -> {
                statusLog("<< startService WIDGET_COMMAND")
                if (manager?.isConnected() == true) {
                    executeWidgetCommand(intent)
                } else {
                    statusLog("<< not connected yet — will retry in 800ms")
                    handler.postDelayed({ executeWidgetCommand(intent) }, 800)
                }
            }
            ACTION_SET_GESTURE -> {
                val device = intent.getIntExtra(EXTRA_GESTURE_DEVICE, -1)
                val gestureAction = intent.getIntExtra(EXTRA_GESTURE_ACTION, -1)
                val function = intent.getIntExtra(EXTRA_GESTURE_FUNCTION, -1)
                statusLog("<< SET_GESTURE: dev=0x${
                    "%02X".format(device)} act=0x${
                    "%02X".format(gestureAction)} fn=0x${"%02X".format(function)}")
                if (device < 0 || gestureAction < 0 || function < 0) {
                    statusLog("<< SET_GESTURE: malformed extras, ignored")
                } else if (manager?.isConnected() != true) {
                    statusLog("<< SET_GESTURE: not connected, not sent")
                } else {
                    // WHICH SLOTS GET THE VALUE is the manager's business, not this caller's:
                    // it reads them from the buds' own table (see writeGestureBinding), because
                    // a gesture's button group is not fixed — slide has appeared in btn 0x01
                    // AND split across 0x02/0x03, on different buds and in different sessions.
                    // The manager returns false when no slot exists for the gesture or when it
                    // has no 0x8108 reading to rebuild the full table from. Both refusals are
                    // the SAFE outcome, so they are logged and NOT retried: a write aimed at a
                    // slot the device does not have does nothing, and a made-up table could
                    // wipe bindings we don't model.
                    val sent = manager?.writeGestureBinding(device, gestureAction, function) ?: false
                    if (!sent) statusLog("<< SET_GESTURE: refused (see KEYFN WRITE line above)")
                }
            }
            ACTION_SET_HOLD_MODES -> {
                val mask = intent.getIntExtra(EXTRA_HOLD_MASK, -1)
                statusLog("<< SET_HOLD_MODES: mask=0x${"%04X".format(mask)}")
                if (mask < 0) {
                    statusLog("<< SET_HOLD_MODES: malformed extras, ignored")
                } else if (manager?.isConnected() != true) {
                    statusLog("<< SET_HOLD_MODES: not connected, not sent")
                } else {
                    manager?.sendHoldAncModes(mask, intent.getIntExtra(EXTRA_HOLD_TYPE, OpoProtocol.HOLD_TYPE_SHARED))
                }
            }
            ACTION_VOICE_PROBE -> {
                setMicrophone(intent.getBooleanExtra(EXTRA_FIND_ON, false))
                // An app screen is open: notification access or the music app may have changed too.
                voiceMusic.sync()
            }
            ACTION_FIND_BUDS -> {
                val on = intent.getBooleanExtra(EXTRA_FIND_ON, false)
                statusLog("<< FIND_BUDS: $on")
                manager?.setFindTone(on)
            }
            // Notification buttons and swipes: the loss card's find tone and dismiss, the battery card's swipe.
            LossNotifier.ACTION_FIND -> {
                val on = intent.getBooleanExtra(LossNotifier.EXTRA_ON, false)
                statusLog("<< LOSS FIND: $on")
                manager?.setFindTone(on)
                if (!intent.getBooleanExtra(LossNotifier.EXTRA_SILENT, false)) LossNotifier.setRinging(this, on)
            }
            LossNotifier.ACTION_DISMISS -> {
                val id = intent.getIntExtra(LossNotifier.EXTRA_ID, -1)
                LossNotifier.dismiss(this, id)
                if (id == LossNotifier.idFor(null)) proximity?.onWarningDismissed()
                else if (LossNotifier.isOtherLossId(id)) other?.onWarningDismissed(id)
            }
            BatteryCard.ACTION_DISMISSED -> {
                // Fixed while the buds are connected and out: a swiped card is posted again straight away.
                val address = intent.getStringExtra(BatteryCard.EXTRA_ADDRESS)
                BatteryCard.onDismissed(address)
                // Again at once, but only while it is true: the buds' link up, or that device still tracked.
                if (address == null) { if (manager?.isConnected() == true) BatteryCard.update(this) else BatteryCard.hide(this) }
                else other?.onSettingsChanged()
            }
            ACTION_OTHER_EARBUDS -> {
                val device = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device != null) {
                    if (intent.getBooleanExtra(EXTRA_CONNECTED, false)) other?.onConnected(device)
                    else {
                        val adapterOn = getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_ON
                        other?.onDisconnected(device.address, if (adapterOn) DropReason.LINK_LOST else DropReason.BLUETOOTH_OFF)
                    }
                }
            }
            ACTION_SET_ON_CALL -> {
                val row = intent.getStringExtra(EXTRA_ON_CALL_ROW)
                val enabled = intent.getBooleanExtra(EXTRA_ON_CALL_ENABLED, false)
                statusLog("<< SET_ON_CALL: row=$row enabled=$enabled")
                if (manager?.isConnected() != true) {
                    statusLog("<< SET_ON_CALL: not connected, not sent")
                } else {
                    val g = OnCallGesture.values().firstOrNull { it.serviceRow == row }
                    if (g == null) statusLog("<< SET_ON_CALL: unknown row '$row', ignored")
                    else manager?.sendOnCall(g.act, if (enabled) g.enabledFn else 0x00, g.button)
                }
            }
            else -> statusLog("[SVC] onStartCommand (no action)")
        }
        return START_STICKY
    }

    private fun forceConnect(withAudio: Boolean) {
        if (manager?.isConnected() == true) {
            statusLog("[SVC] FORCE_CONNECT ignored (already connected)")
            return
        }
        statusLog("[SVC] FORCE_CONNECT: connecting...")
        try {
            val device = BudsDevice.find(this)
            if (device == null) { statusLog("[SVC] No paired OPPO / OnePlus / realme buds found"); return }
            // As on opening the app (MainActivity.connectOnOpen): saved buds that are not connected while other earbuds
            // are get no connect, from the pill, the widget or Dev tools. Was: four rounds of three UUIDs at buds out of
            // range (Page Timeout), and the audio pulled toward them, away from the earbuds in use.
            ConnectedAudio.query(this) { connected ->
                val others = connected?.values?.filter { it.address != device.address }.orEmpty()
                if (connected != null && device.address !in connected && others.isNotEmpty()) {
                    PacketLogger.log("BUDS: ${device.name} not connected, " +
                        "${others.joinToString { runCatching { it.name }.getOrNull() ?: it.address }} in use: connect skipped")
                    return@query
                }
                if (manager?.isConnected() != true) manager?.connect(device, withAudio)
            }
        } catch (e: Exception) {
            statusLog("[SVC] Force connect failed: ${e.message}")
        }
    }

    private fun executeWidgetCommand(intent: Intent) {
        val widgetAction = intent.getStringExtra(EXTRA_WIDGET_ACTION)
        val ancMode = intent.getStringExtra(EXTRA_WIDGET_ANC_MODE)
        val gameMode = intent.getBooleanExtra(EXTRA_WIDGET_GAME_MODE, false)
        val feature = intent.getIntExtra(EXTRA_WIDGET_FEATURE, -1)
        val featureOn = intent.getBooleanExtra(EXTRA_WIDGET_FEATURE_ON, false)
        val connected = manager?.isConnected() == true
        statusLog("<< executeWidgetCommand: action=$widgetAction (anc=$ancMode, game=$gameMode, feature=$feature $featureOn, connected=$connected)")

        if (!connected) {
            statusLog("<< SKIPPED — not connected")
            return
        }

        when (widgetAction) {
            // ANC_CYCLE carries a mode name (levels and Adaptive); the manager picks the model's bit.
            "ANC_CYCLE" -> ancMode?.let { statusLog("<< sending ANC $it"); manager?.sendAnc(it) }
            "TRANS" -> { statusLog("<< sending Transparency"); manager?.sendAnc(AncModes.TRANSPARENCY) }
            "OFF" -> { statusLog("<< sending ANC Off"); manager?.sendAnc(AncModes.OFF) }
            "GAME_TOGGLE" -> { statusLog("<< sending Game $gameMode"); manager?.setGameMode(gameMode) }
            "FEATURE" -> if (feature >= 0) manager?.setFeatures(feature to featureOn)
            else -> statusLog("<< UNKNOWN widget action: '$widgetAction'")
        }
    }

    private fun startForegroundService() {
        val channelId = "QuickBuds_Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Buds Connection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                description = "Keeps the connection to your earbuds alive"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("QuickBuds")
                .setContentText("Maintaining connection to earbuds")
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setOngoing(false)
                .setShowWhen(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("QuickBuds")
                .setContentText("Maintaining connection to earbuds")
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setOngoing(false)
                .setShowWhen(false)
                .build()
        }
        // Android 12+ refuses the foreground from the background (seen on Android 16: a sticky restart). Was: a
        // crash. Is: the service stops; the next app start or buds connect starts it again.
        fgNotification = notification
        try {
            // The type named: the manifest also lists microphone, used only while the voice probe is armed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, notification)
            // A fresh foreground start holds no microphone: voice answer waits for the next app screen.
            microphoneReady = false
        } catch (e: IllegalStateException) {
            PacketLogger.error("service foreground refused", e)
            stopSelf()
        }
    }

    private var fgNotification: Notification? = null

    /** Voice music's listener (Bike mode on, voice music on). */
    private val voiceMusic by lazy { com.spizganed.quickbuds.music.VoiceMusicLoop(this) }

    /** The microphone stays while voice answer or voice music is on. */
    private fun micWanted() = com.spizganed.quickbuds.calls.VoiceAnswer.switchedOn(this) ||
        com.spizganed.quickbuds.music.VoiceMusic.switchedOn(this)

    /**
     * Voice answer probe: adds the microphone to this running foreground service, or takes it off. Android allows
     * the microphone in the background only to a foreground service of that type started while the app is on
     * screen: [on] is sent from Dev tools, so the app is on screen then. The probe measures whether this holds.
     */
    private fun setMicrophone(on: Boolean) {
        val n = fgNotification ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        if (on == microphoneReady) return
        try {
            startForeground(1, n, type)
            microphoneReady = on
            PacketLogger.log("VOICE: background service microphone ${if (on) "on" else "off"}")
        } catch (e: Exception) {
            PacketLogger.log("VOICE: background service microphone refused: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        statusLog("[SVC] onBind")
        return binder
    }

    override fun onDestroy() {
        statusLog("[SVC] onDestroy")
        handler.removeCallbacksAndMessages(null)
        // Close the RFCOMM link with the service: with the background service switched off,
        // leaving the app stops the service, and a live socket must not outlive it.
        try { manager?.disconnect() } catch (e: Exception) { PacketLogger.error("service stop disconnect", e) }
        try { manager?.setBridge(false) } catch (_: Exception) {}
        try { manager?.removeListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(widgetCommandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(adapterReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(linkReceiver) } catch (_: Exception) {}
        other?.destroy()
        autoAnswer?.stop()
        voiceMusic.destroy()
        ConnectedDevices.removeListener(connectedDevicesListener)
        com.spizganed.quickbuds.proximity.LocationBuffer.sync(this, anyConnected = false)
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        proximity?.destroy()
        caseReminder?.destroy()
        BatteryCard.hide(this)
        // No service, no updates: a low-battery card would go stale.
        LowBatteryAlert.clear(this)
        super.onDestroy()
    }

    override fun onLinkDropped(drop: BudsConnectionManager.Drop) {
        val adapterOn = getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_ON
        proximity?.onDisconnected(when {
            !adapterOn -> DropReason.BLUETOOTH_OFF
            // Disconnected by the sleep timer: the user's choice, not a loss.
            com.spizganed.quickbuds.sleep.SleepTimer.justDisconnected() -> DropReason.USER
            drop == BudsConnectionManager.Drop.CASE_CLOSED -> DropReason.CASE_CLOSED
            drop == BudsConnectionManager.Drop.RESTART -> DropReason.BUDS_RESTART
            else -> DropReason.LINK_LOST
        })
    }

    override fun onCapabilities() {
        // The product id names the model: the statistics and the battery card show it instead of the Bluetooth name.
        usageHeartbeat()
        BatteryCard.update(this)
    }

    /**
     * Disconnect locations: recent positions are kept while any earbuds are connected (ours or another brand's),
     * so a disconnect can be placed where the phone was while still connected.
     */
    private fun syncLocationBuffer() = com.spizganed.quickbuds.proximity.LocationBuffer.sync(this,
        manager?.isConnected() == true || ConnectedDevices.all().isNotEmpty())

    private val connectedDevicesListener: () -> Unit = { syncLocationBuffer() }

    override fun onConnected(connected: Boolean) {
        syncLocationBuffer()
        other?.onSupportedBudsChanged()
        if (connected) {
            usageHeartbeat()
            manager?.device?.let { proximity?.onConnected(it) }
        } else {
            // Our own disconnect, or the second half of a drop (onLinkDropped came first): never a warning.
            proximity?.onDisconnected(DropReason.USER)
            // Our link can drop while the buds stay connected for audio. If they are gone, their usage session ends
            // now, even when the disconnect broadcast never reaches us.
            manager?.device?.address?.let { address ->
                handler.postDelayed({
                    ConnectedAudio.query(this) { c ->
                        if (c != null && address !in c && manager?.isConnected() != true) Usage.disconnected(this, address)
                    }
                }, LINK_CHECK_DELAY_MS)
            }
            caseReminder?.onDisconnected()
        }
        val st = WidgetStateStore.read(this)
        st.connected = connected
        if (connected) {
            statusLog("[SVC] Connected")
            st.caseLidClosed = false
            st.leftDocked = false
            st.rightDocked = false
        } else {
            statusLog("[SVC] Disconnected")
            st.leftBattery = -1
            st.caseBattery = -1
            st.rightBattery = -1
            // Socket died with a bud in the case (4 = docked, 0 = docked & asleep)
            // => lid closed. Freeze the docked set so those icons stay hidden.
            if (st.leftStatus == 4 || st.rightStatus == 4 ||
                st.leftStatus == 0 || st.rightStatus == 0) {
                st.caseLidClosed = true
                st.leftDocked = (st.leftStatus == 4 || st.leftStatus == 0)
                st.rightDocked = (st.rightStatus == 4 || st.rightStatus == 0)
            }
        }
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
        BatteryCard.update(this, st)
    }

    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {
        statusLog("[SVC] Battery: L=$left C=$case R=$right " +
                "(chg: $chargingLeft/$chargingCase/$chargingRight)")
        val st = WidgetStateStore.read(this)
        val clear = clearMissingBattery(this)
        if (left != null || clear) st.leftBattery = left ?: -1
        if (case != null || clear) st.caseBattery = case ?: -1
        if (case != null) {
            // heartbeat: a closed lid stops these, so freshness == lid open
            st.caseBatteryAt = System.currentTimeMillis()
        }
        if (right != null || clear) st.rightBattery = right ?: -1
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
        usageHeartbeat()
        BatteryCard.leftCharging = chargingLeft
        BatteryCard.rightCharging = chargingRight
        BatteryCard.caseCharging = chargingCase
        BatteryCard.update(this, st)
        LowBatteryAlert.onBattery(this, left, case, right, chargingLeft, chargingCase, chargingRight)
        // Battery life (Usage statistics): how fast the buds drain while out of the case (wear status 4 = in case).
        BudsDevice.find(this)?.address?.let { address ->
            val level = com.spizganed.quickbuds.usage.BatteryDrain.budsLevel(st.leftBattery, st.rightBattery,
                leftInUse = !chargingLeft && st.leftStatus != 4, rightInUse = !chargingRight && st.rightStatus != 4)
            com.spizganed.quickbuds.usage.BatteryDrain.record(this, address, level ?: -1, draining = level != null)
        }
    }

    override fun onWearState(left: Int, right: Int, caseSt: Int) {
        proximity?.onWear(left, right)
        caseReminder?.onWear(left, right)
        usageHeartbeat()
        val st = WidgetStateStore.read(this)
        val wasWorn = isWorn(st.leftStatus) || isWorn(st.rightStatus)
        var changed = false
        if (left >= 0 && st.leftStatus != left) {
            st.leftStatus = left
            st.leftInBox = (left == 4)
            changed = true
        }
        if (right >= 0 && st.rightStatus != right) {
            st.rightStatus = right
            st.rightInBox = (right == 4)
            changed = true
        }
        if (changed) {
            statusLog("[SVC] Wear state: L=$left R=$right case=$caseSt")
            WidgetStateStore.write(this, st)
            AncWidgetProvider.refreshAll(this)
        }
        BatteryCard.update(this, st)
        if (wasWorn && !isWorn(st.leftStatus) && !isWorn(st.rightStatus)) smartPause()
    }

    private fun isWorn(st: Int) = st == 3 || st == 7

    /**
     * Smart auto-pause (our own, not the firmware's): pause when the LAST bud leaves an ear.
     * One bud out keeps playing, and nothing ever auto-plays. Off unless enabled on the wear screen.
     */
    private fun smartPause() {
        if (!getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SMART_PAUSE, false)) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!audio.isMusicActive) return
        statusLog("[SVC] Smart pause: both buds out")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    /**
     * Game mode changed ON THE BUDS (0x0204 subType 0x05).
     *
     * This fires for our own setGameMode() AND for the user's buds gesture, so it is
     * the authoritative state. Persisting it here is all that is needed to refresh
     * BOTH UIs: the widget renders from this store, and MainActivity re-renders via
     * its WidgetStateStore listener — no view code has to know about this event.
     */
    override fun onGameModeState(on: Boolean) {
        val st = WidgetStateStore.read(this)
        if (st.gameMode == on) return
        st.gameMode = on
        statusLog("[SVC] Game mode (from buds): ${if (on) "ON" else "OFF"}")
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
    }

    /**
     * ANC mode changed ON THE BUDS (0x0204 subType 0x03).
     *
     * Same contract as onGameModeState above: persist, then let the store listener
     * repaint both surfaces. Nothing here touches views directly.
     *
     * The equality check is what makes OUR OWN command's echo a no-op, so a gesture
     * and a tap cannot fight each other over the last write.
     */
    /** The widget buttons' feature values last drawn; a `0x810D` reply repaints only when one changed. */
    private var widgetFeatures: List<Int?>? = null

    override fun onFeatureStates(states: Map<Int, Int>) {
        val now = WidgetSettings.FEATURE_BUTTONS.values.map { states[it] }
        if (now == widgetFeatures) return
        widgetFeatures = now
        AncWidgetProvider.refreshAll(this)
    }

    override fun onAncModeState(mode: String) {
        val st = WidgetStateStore.read(this)
        if (st.ancMode == mode) return
        st.ancMode = mode
        statusLog("[SVC] ANC mode (from buds): $mode")
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
    }

    companion object {
        const val ACTION_FORCE_CONNECT = "com.spizganed.quickbuds.FORCE_CONNECT"
        /** On FORCE_CONNECT: true when the user asked (pill), so phone audio is brought up too. */
        const val EXTRA_WITH_AUDIO = "with_audio"
        const val ACTION_FORCE_DISCONNECT = "com.spizganed.quickbuds.FORCE_DISCONNECT"
        /** Dev tools › Bridge on/off ([RfcommBridge]). */
        const val ACTION_TOGGLE_BRIDGE = "com.spizganed.quickbuds.TOGGLE_BRIDGE"
        const val ACTION_WIDGET_COMMAND = "com.spizganed.quickbuds.WIDGET_COMMAND"
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val EXTRA_WIDGET_ANC_MODE = "widget_anc_mode"
        const val EXTRA_WIDGET_GAME_MODE = "widget_game_mode"
        /** FEATURE: the `0x0403` switch id and the value to write. */
        const val EXTRA_WIDGET_FEATURE = "widget_feature"
        const val EXTRA_WIDGET_FEATURE_ON = "widget_feature_on"

        /** Earbud controls: change one gesture binding. See GestureActivity.writeToBuds(). */
        const val ACTION_SET_GESTURE = "com.spizganed.quickbuds.SET_GESTURE"
        const val EXTRA_GESTURE_DEVICE = "gesture_device"
        const val EXTRA_GESTURE_ACTION = "gesture_action"
        const val EXTRA_GESTURE_FUNCTION = "gesture_function"

        /** The hold's ANC-cycle membership. See BudsConnectionManager.sendHoldAncModes(). */
        const val ACTION_SET_HOLD_MODES = "com.spizganed.quickbuds.SET_HOLD_MODES"
        const val EXTRA_HOLD_MASK = "hold_mask"
        const val EXTRA_HOLD_TYPE = "hold_type"

        /** On-call gestures (`btn 0x06`). See BudsConnectionManager.sendOnCall*(). */
        const val ACTION_SET_ON_CALL = "com.spizganed.quickbuds.SET_ON_CALL"
        const val ACTION_FIND_BUDS = "com.spizganed.quickbuds.FIND_BUDS"
        /**
         * True while this service holds the microphone type: voice answer can listen in the background. Lost when
         * the service restarts in the background; back on the next app screen ([QuickBudsApp]).
         */
        @Volatile var microphoneReady = false
            private set

        /** Dev tools › Voice and voice answer: the microphone on the foreground service (extra [EXTRA_FIND_ON] true) or off. */
        const val ACTION_VOICE_PROBE = "com.spizganed.quickbuds.VOICE_PROBE"
        const val PREF_SMART_PAUSE = "smart_auto_pause"

        /** Earbuds of other brands: connected / disconnected ([OtherEarbudsMonitor]), the device in EXTRA_DEVICE. */
        const val ACTION_OTHER_EARBUDS = "com.spizganed.quickbuds.OTHER_EARBUDS"
        const val EXTRA_CONNECTED = "connected"
        private const val OTHER_DISCOVER_DELAY_MS = 3_000L
        /** After the phone's audio to our buds went down: time for both profiles to settle before checking. */
        private const val AUDIO_GONE_CHECK_MS = 3_000L
        /** After our link drops: time for the system to settle before asking what is still connected. */
        private const val LINK_CHECK_DELAY_MS = 5_000L

        /** After the service starts, usage sessions are checked against what is connected ([reconcileUsage]). */
        private const val RECONCILE_DELAY_MS = 15_000L

        /** Delay for a FORCE_CONNECT, in ms (the ACL fallback in KeepAliveReceiver). */
        const val EXTRA_DELAY_MS = "delay_ms"

        /**
         * Settings › Background service (default on). Off: the service runs only while the app is
         * open, nothing starts it from Bluetooth events, and the widget only works with the app open.
         */
        const val PREF_BACKGROUND = "backgroundService"

        fun backgroundAllowed(c: Context) =
            c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_BACKGROUND, true)

        /**
         * Settings › Hide old battery levels (default off). On: each battery report replaces all three
         * levels, so a part it leaves out (the case after the lid closes) shows blank instead of its last level.
         */
        const val PREF_CLEAR_BATTERY = "clearMissingBattery"

        fun clearMissingBattery(c: Context) =
            c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_CLEAR_BATTERY, false)
        const val EXTRA_FIND_ON = "find_on"
        const val EXTRA_ON_CALL_ROW = "on_call_row"   // OnCallGesture.serviceRow
        const val EXTRA_ON_CALL_ENABLED = "on_call_enabled"
    }
}
