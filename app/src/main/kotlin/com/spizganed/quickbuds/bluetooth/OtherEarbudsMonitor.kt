package com.spizganed.quickbuds.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.spizganed.quickbuds.notify.BatteryCard
import com.spizganed.quickbuds.notify.LowBatteryAlert
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.proximity.DropReason
import com.spizganed.quickbuds.proximity.ProximityMonitor
import com.spizganed.quickbuds.usage.Usage

/**
 * Earbuds of any brand outside `models.json` ([AudioDevice]): boAt, Noise, Redmi, Motorola, JBL, Sony... In
 * [BudsService], next to the RFCOMM link of the supported models, and with no link of its own: everything here
 * is standard Bluetooth.
 *
 *  - the loss warning: its own [ProximityMonitor] per device (the phone reads the signal; no case or wear state,
 *    so the signal alone decides);
 *  - the battery card and the low-battery alert, from the one level the headset reports for the pair;
 *  - usage heartbeats, so a session the phone restarts through ends near its real end.
 *
 * Usage sessions themselves come from the ACL broadcasts in [KeepAliveReceiver], as for the supported models.
 */
class OtherEarbudsMonitor(private val context: Context) {

    /** [proximity] is null for a speaker: it gets usage and battery, not the loss warning. */
    class Tracked internal constructor(
        val device: BluetoothDevice, val name: String, val proximity: ProximityMonitor?, var kind: DeviceCatalog.Kind?,
        /** A hands-free or hi-fi class ([AudioDevice.isAmbiguous]): maybe a car or a receiver. */
        val ambiguous: Boolean = false
    ) {
        var level = -1
        var parts: FastPairBattery.Levels? = null
        /** The device's own Fast Pair Message Stream, when it offers one. */
        var stream: FastPairStream? = null
        /** [parts] came from [stream]: the device's own word, which a broadcast does not replace. */
        var streamed = false
    }

    /** Left, right and case from Fast Pair broadcasts, trusted only under [FastPairBattery.Picker]'s safeguards. */
    private val fastPair = FastPairScanner(context) { levels ->
        // With two pairs of other earbuds connected, whose broadcast it is cannot be told: one ring each.
        val t = tracked.values.singleOrNull() ?: return@FastPairScanner
        if (t.streamed) return@FastPairScanner
        setParts(t, levels, "broadcast")
    }

    private fun setParts(t: Tracked, levels: FastPairBattery.Levels?, source: String) {
        if (t.parts == levels) return
        t.parts = levels
        if (levels != null) PacketLogger.log("OTHER EARBUDS: Fast Pair battery ($source) L=${levels.left} C=${levels.case} R=${levels.right}")
        // A case reported means TWS, whatever the name suggested.
        if (levels != null && levels.case >= 0 && t.kind != DeviceCatalog.Kind.TWS) {
            t.kind = DeviceCatalog.Kind.TWS
            Usage.saveKind(context, t.device.address, DeviceCatalog.Kind.TWS)
        }
        show(t)
    }

    private fun show(t: Tracked) {
        publish(t)
        BatteryCard.showOther(context, t.device.address, t.name, t.level, t.parts, t.kind)
    }

    /** The home screen's "Connected devices" tile follows [tracked]. */
    private fun publish(t: Tracked) =
        ConnectedDevices.put(ConnectedDevices.Entry(t.device.address, t.name, t.kind, t.level, t.parts))

    /** True while the supported buds (models.json) are connected: they broadcast Fast Pair too, near the phone. */
    var supportedBudsConnected: () -> Boolean = { false }

    init {
        // Their broadcast carries the levels QuickBuds reads from them: the picker sets it aside by those.
        fastPair.ownLevels = {
            if (!supportedBudsConnected()) null
            else com.spizganed.quickbuds.widget.WidgetStateStore.read(context).let {
                FastPairBattery.Levels(it.leftBattery, it.rightBattery, it.caseBattery, false, false, false)
            }
        }
    }

    /**
     * Fast Pair listening runs only while it can help and cannot mislead: one other device connected, which is
     * earbuds (or of unknown kind; a neckband, headphones or a speaker has no left, right or case), and the battery
     * card on. Supported buds next to it are set aside by their levels ([FastPairScanner.ownLevels]).
     */
    private fun updateFastPair() {
        // For this check a hands-free or hi-fi device of unknown kind is not earbuds: no scan for a car.
        val kinds = tracked.values.map { if (it.ambiguous && it.kind == null) DeviceCatalog.Kind.HEADPHONES else it.kind }
        val listen = FastPairBattery.allowed(kinds, BatteryCard.enabled(context)) && tracked.values.none { it.streamed }
        val why = "${kinds.size} other device(s) ${kinds.joinToString()}, card ${if (BatteryCard.enabled(context)) "on" else "off"}"
        if (listen != fastPairListening) {
            fastPairListening = listen
            PacketLogger.log("OTHER EARBUDS: Fast Pair battery ${if (listen) "listening" else "not listening"} ($why)")
        }
        if (listen) {
            fastPair.standardLevel = tracked.values.first().level.takeIf { it > 0 }
            fastPair.start()
        } else fastPair.stop()
    }

    private var fastPairListening = false

    private val tracked = LinkedHashMap<String, Tracked>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Every [HEARTBEAT_MS]: Bluetooth itself says which earbuds are still connected ([verify]); only those get a
     * last-seen update. Was: an update for every tracked device, which restarted the usage session of earbuds whose
     * disconnect broadcast never arrived, and kept their battery card up.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            verify { still -> still.forEach { Usage.connected(context, it.device) } }
            if (tracked.isNotEmpty()) handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val verifySoon = Runnable { verify() }

    /**
     * Ends everything for tracked earbuds that Bluetooth no longer lists as connected: the loss warning, the battery
     * card and the usage session. [then] gets the ones still connected. Nothing changes when Bluetooth cannot be asked.
     */
    fun verify(then: (List<Tracked>) -> Unit = {}) {
        if (tracked.isEmpty()) return
        ConnectedAudio.query(context) { connected ->
            if (connected == null) return@query
            for (address in tracked.keys.toList()) {
                if (address in connected) continue
                PacketLogger.log("OTHER EARBUDS: no longer connected (checked with Bluetooth)")
                onDisconnected(address, DropReason.LINK_LOST)
                Usage.disconnected(context, address)
            }
            then(tracked.values.toList())
        }
    }

    /** A hint that earbuds may be gone (an audio profile went down): checked with Bluetooth a moment later. */
    fun onMaybeGone() {
        handler.removeCallbacks(verifySoon)
        handler.postDelayed(verifySoon, VERIFY_DELAY_MS)
    }

    fun onConnected(device: BluetoothDevice) {
        if (device.address in tracked || BudsDevice.isBuds(context, device)) return
        val name = AudioDevice.name(device)
        val kind = DeviceCatalog.kindOf(name, speakerClass = AudioDevice.isSpeaker(device))
        // A speaker stays where it is put: no loss warning for it. Nor for a hands-free or hi-fi device of unknown
        // kind: a car kit or an AV receiver announces those classes too.
        val ambiguous = kind == null && AudioDevice.isAmbiguous(device)
        val proximity = if (kind == DeviceCatalog.Kind.SPEAKER || ambiguous) null
            else ProximityMonitor(context, primary = false).also { it.connected = { device.address in tracked } }
        val t = Tracked(device, name, proximity, kind, ambiguous)
        tracked[device.address] = t
        publish(t)
        // No address in the log: exported logs go out with problem reports.
        PacketLogger.log("OTHER EARBUDS: connected, ${kind ?: "kind unknown"} (standard Bluetooth: usage, battery" +
            (if (proximity != null) ", loss warning)" else ")"))
        Usage.connected(context, device)
        proximity?.onConnected(device)
        AudioDevice.batteryLevel(device).takeIf { it > 0 }?.let { onBattery(device, it) }
        // Earbuds only (or of unknown kind, not a car or receiver): those offer the Message Stream.
        if (kind == null && !ambiguous || kind == DeviceCatalog.Kind.TWS) {
            t.stream = FastPairStream(device) { levels ->
                if (tracked[device.address] !== t) return@FastPairStream
                t.streamed = true
                setParts(t, levels, "stream")
                // Exact levels from the device itself: no broadcast scan needed for it.
                updateFastPair()
            }.also { it.start() }
        }
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        updateFastPair()
    }

    /** The supported buds' link changed: Fast Pair listening may have to stop or may start. */
    fun onSupportedBudsChanged() {
        updateFastPair()
    }

    /** [reason]: the cause as far as the phone knows; without the brand protocol a closed case looks like a lost link. */
    fun onDisconnected(address: String, reason0: DropReason) {
        // Disconnected by the sleep timer: the user's own choice, not a lost device.
        val reason = if (com.spizganed.quickbuds.sleep.SleepTimer.justDisconnected()) DropReason.USER else reason0
        val t = tracked.remove(address) ?: return
        t.stream?.stop()
        PacketLogger.log("OTHER EARBUDS: disconnected ($reason)")
        // The loss warning first: its Find nearby button carries the device's name and last level from the entry.
        t.proximity?.onDisconnected(reason)
        ConnectedDevices.remove(address)
        t.proximity?.destroy()
        BatteryCard.hideOther(context, address)
        // Gone: its low-battery card says nothing true any more.
        LowBatteryAlert.clearOther(context, address)
        if (tracked.isEmpty()) handler.removeCallbacks(heartbeat)
        // Broadcast levels were picked for the old set of devices; a stream's are the device's own and stay.
        tracked.values.forEach { if (it.parts != null && !it.streamed) { it.parts = null; publish(it) } }
        updateFastPair()
    }

    /** "I have them" on the loss card [id] of one of these devices. */
    fun onWarningDismissed(id: Int) =
        tracked.values.firstOrNull { LossNotifier.idFor(it.device.address) == id }?.proximity?.onWarningDismissed()

    fun onAllDisconnected(reason: DropReason) = tracked.keys.toList().forEach { onDisconnected(it, reason) }

    /** The standard battery broadcast for [device]: one level for the pair. */
    fun onBattery(device: BluetoothDevice, level: Int) {
        val t = tracked[device.address] ?: return
        if (level <= 0) return
        t.level = level
        fastPair.standardLevel = level
        show(t)
        LowBatteryAlert.onOther(context, device.address, t.name, level, t.kind)
        // Battery life (Usage statistics): connected means in use; in the case they disconnect.
        com.spizganed.quickbuds.usage.BatteryDrain.record(context, device.address, level, draining = true)
        Usage.connected(context, device)
    }

    /** Loss protection, the battery card or low-battery alerts changed in Settings. */
    fun onSettingsChanged() {
        for (t in tracked.values) {
            t.proximity?.onSettingsChanged()
            show(t)
        }
        updateFastPair()
    }

    /** After the service starts: earbuds already connected (no broadcast came for them in this process). */
    fun discover() {
        ConnectedAudio.query(context) { connected ->
            connected?.values?.filter { AudioDevice.isAudio(it) }?.forEach { onConnected(it) }
        }
    }

    fun destroy() {
        fastPair.stop()
        onAllDisconnected(DropReason.USER)
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        /** The connection check and last-seen update for usage: the headset sends nothing else while idle. */
        private const val HEARTBEAT_MS = 2 * 60_000L
        /** After an audio profile went down: time for the rest of the link to follow before checking. */
        private const val VERIFY_DELAY_MS = 3_000L

        /** Any feature here on, so the service is worth starting for other earbuds. */
        fun wanted(c: Context) = ProximityMonitor.enabled(c) || BatteryCard.enabled(c) || LowBatteryAlert.enabled(c)
    }
}
