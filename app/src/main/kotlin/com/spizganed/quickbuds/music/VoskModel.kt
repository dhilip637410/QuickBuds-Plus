package com.spizganed.quickbuds.music

import android.content.Context
import com.spizganed.quickbuds.bluetooth.PacketLogger
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline speech model for voice music: Vosk's small Indian English model (Apache 2.0, about 37 MB), downloaded
 * once from the Vosk project and kept in the app's files. Checked 2026-10-19: its vocabulary has every word the
 * commands use, "jarvis" included. Recognition with it never goes online.
 */
object VoskModel {

    private const val NAME = "vosk-model-small-en-in-0.4"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"
    /** The published size: a download of another size is not used. */
    private const val SIZE = 37_573_330L

    private fun dir(c: Context) = File(c.filesDir, NAME)

    /** Downloaded and unpacked: the parts Vosk loads are there. */
    fun ready(c: Context) = File(dir(c), "am/final.mdl").isFile && File(dir(c), "conf/model.conf").isFile

    @Volatile var downloading = false
        private set

    /**
     * Downloads and unpacks the model on a background thread. [progress] gets 0..100; [done] true when ready.
     * Both are called on that thread: post to the main thread to touch views.
     */
    fun download(context: Context, progress: (Int) -> Unit, done: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        val c = context.applicationContext
        Thread {
            val zip = File(c.cacheDir, "$NAME.zip")
            val ok = runCatching {
                val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input ->
                        zip.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                val pct = (total * 100 / SIZE).toInt().coerceAtMost(99)
                                if (pct != last) { last = pct; progress(pct) }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (zip.length() != SIZE) error("size ${zip.length()}, expected $SIZE")
                unzip(zip, c.filesDir)
                ready(c)
            }.onFailure { PacketLogger.error("voice music model download", it) }.getOrDefault(false)
            zip.delete()
            downloading = false
            PacketLogger.log("VOICE MUSIC: speech model ${if (ok) "ready" else "download failed"}")
            if (ok) progress(100)
            done(ok)
        }.apply { isDaemon = true; name = "vosk-model-download" }.start()
    }

    /** Unpacks [zip] into [into]; refuses entries that would land outside it. */
    private fun unzip(zip: File, into: File) {
        val root = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(root)) error("bad entry ${e.name}")
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    /** Loads the model (a second or two: call off the main thread). Null when not ready or it fails. */
    fun load(c: Context): Model? {
        if (!ready(c)) return null
        return runCatching { Model(dir(c).path) }.onFailure { PacketLogger.error("voice music model load", it) }.getOrNull()
    }

    /** Words of [phrases] the model does not know (Vosk's own lookup, through its native library). */
    fun missingWords(model: Model, phrases: List<String>): List<String> {
        val f = runCatching { com.sun.jna.Function.getFunction("vosk", "vosk_model_find_word") }.getOrNull() ?: return emptyList()
        return phrases.flatMap { it.split(' ') }.filter { it.isNotBlank() && !it.startsWith("[") }.distinct()
            .filter { w -> runCatching { f.invokeInt(arrayOf(model.pointer, w)) }.getOrDefault(0) < 0 }
    }

    fun delete(c: Context) = dir(c).deleteRecursively()
}
