package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID

/**
 * Left, right and case battery from the Fast Pair Message Stream of earbuds outside `models.json`. The stream is an
 * RFCOMM service that Fast Pair earbuds offer; the format is Google's published Fast Pair specification ("Message
 * Stream"), nothing here is guessed:
 *
 *     GG CC LL LL <LL bytes>   group, code, length (big-endian), data
 *     03 03 00 03  L R C       Device information › battery updated: one byte each, as in [FastPairBattery.parse]
 *
 * Read only: QuickBuds writes nothing to the stream. The earbuds send their state on each connection. The Airdopes 800
 * (model 829F6D, firmware 0.0.2.3) then close it within 100 ms, so the stream opens again every [REFRESH_MS].
 *
 * The levels come from the connected device itself, so they need none of [FastPairBattery.Picker]'s safeguards.
 */
@SuppressLint("MissingPermission")
class FastPairStream(private val device: BluetoothDevice, private val onLevels: (FastPairBattery.Levels) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "FastPairStream").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        thread?.interrupt()
    }

    /**
     * Until [stop]: a session, then [REFRESH_MS] after one that read the battery, or the next of [RETRY_MS] after one
     * that did not. Google Play services opens the stream as the link comes up (Bluetooth on, buds out of the case)
     * and holds it for a few seconds: early attempts fail. Was: two attempts, then no stream until the next connect,
     * and the card kept the one standard level.
     */
    private fun run() {
        var failures = 0
        var everRead = false
        while (!stopped) {
            val read = session()
            if (read) {
                if (!everRead) PacketLogger.log("FASTPAIR STREAM: battery read (read only, refresh every ${REFRESH_MS / 60_000} min)")
                else if (failures > 0) PacketLogger.log("FASTPAIR STREAM: battery read again after $failures failed attempt(s)")
                everRead = true
                failures = 0
            } else {
                failures++
                if (failures == 1 || failures == RETRY_MS.size) PacketLogger.log("FASTPAIR STREAM: no battery yet (attempt $failures), retrying")
            }
            val wait = if (read) REFRESH_MS else RETRY_MS[minOf(failures, RETRY_MS.size) - 1]
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    /** One connection: reads until the earbuds close it. True when it brought a battery reading. */
    private fun session(): Boolean {
        val s = try {
            device.createRfcommSocketToServiceRecord(UUID_STREAM).also { socket = it; it.connect() }
        } catch (_: IOException) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            return false
        } catch (_: SecurityException) {
            PacketLogger.log("FASTPAIR STREAM: no Bluetooth permission")
            stopped = true
            return false
        }
        gotBattery = false
        read(s)
        try { s.close() } catch (_: IOException) {}
        socket = null
        return gotBattery
    }

    @Volatile private var gotBattery = false

    private fun read(s: BluetoothSocket) {
        var pending = ByteArray(0)
        val buf = ByteArray(512)
        try {
            val input = s.inputStream
            while (!stopped) {
                val n = input.read(buf)
                if (n < 0) break
                pending += buf.copyOf(n)
                pending = frames(pending)
            }
        } catch (_: IOException) {}
    }

    /** Handles every whole frame in [data]; returns the rest. */
    private fun frames(data: ByteArray): ByteArray {
        var i = 0
        while (data.size - i >= HEADER) {
            val group = data[i].toInt() and 0xFF
            val code = data[i + 1].toInt() and 0xFF
            val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (data.size - i - HEADER < length) break
            val body = data.copyOfRange(i + HEADER, i + HEADER + length)
            if (group == GROUP_DEVICE_INFO && code == CODE_BATTERY) {
                val levels = FastPairBattery.levels(body)
                if (levels != null) {
                    gotBattery = true
                    handler.post { if (!stopped) onLevels(levels) }
                }
            }
            i += HEADER + length
        }
        return data.copyOfRange(i, data.size)
    }

    companion object {
        /** The Message Stream's RFCOMM service (Fast Pair specification). */
        val UUID_STREAM: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
        private const val HEADER = 4
        private const val GROUP_DEVICE_INFO = 0x03
        private const val CODE_BATTERY = 0x03
        /** Waits after a session without a battery reading; the last one repeats. */
        private val RETRY_MS = longArrayOf(3_000, 5_000, 10_000, 20_000, 40_000, 60_000)
        /** Battery moves slowly: a fresh reading every 5 min. */
        private const val REFRESH_MS = 5 * 60_000L
    }
}
