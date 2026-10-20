package org.sapidroid.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds the voice engine and its open voice handles, in its own process.
 *
 * One request at a time on one thread.  Cancellation calls the engine's own
 * thread-safe cancel; a call that will not return is cleared by the owner
 * killing this process.
 */
class SapiDroidWorkerService : Service() {

    companion object {
        private const val TAG = "SapiDroidWorker"
        const val BUSY = -2
        const val HANG_PREFIX = "\$HANG"
    }

    private val synthesis = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sapidroid-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private val state = Object()
    private var currentId = 0
    private var busy = false
    private var finished = true
    private var cancelled = false
    private var pending: ByteArray? = null

    private val handles = HashMap<String, Long>()

    @Volatile private var dataDir: File? = null
    @Volatile private var openError: String = ""

    private fun ensureOpen(voice: String): Long {
        handles[voice]?.let { if (it != 0L) return it }
        val dir = dataDir ?: return 0
        val handle = NativeZira.open(dir.absolutePath, voice)
        if (handle == 0L) {
            openError = "no voice data for $voice in ${dir.absolutePath}"
            Log.e(TAG, openError)
            return 0
        }
        handles[voice] = handle
        return handle
    }

    private val binder = object : IZiraWorker.Stub() {

        override fun say(voice: String, rate: Int, pitch: Int, text: String, waitMs: Int): Int {
            val id: Int
            synchronized(state) {
                val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
                while (busy) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        Log.w(TAG, "say refused: request $currentId still running")
                        return BUSY
                    }
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return BUSY
                    }
                }
                currentId += 1
                id = currentId
                busy = true
                finished = false
                cancelled = false
                pending = null
            }
            synthesis.submit { runRequest(id, voice, rate, pitch, text) }
            return id
        }

        override fun pull(requestId: Int, maxBytes: Int, waitMs: Int): ByteArray? {
            val limit = maxBytes.coerceIn(1, 256 * 1024)
            val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
            synchronized(state) {
                while (true) {
                    if (requestId != currentId) return null
                    if (cancelled) return null
                    val buf = pending
                    if (buf != null && buf.isNotEmpty()) {
                        val n = minOf(limit, buf.size)
                        val out = buf.copyOfRange(0, n)
                        pending = if (n == buf.size) null else buf.copyOfRange(n, buf.size)
                        return out
                    }
                    if (finished) return null
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }

        override fun finished(requestId: Int): Boolean = synchronized(state) {
            requestId == currentId && finished
        }

        override fun cancel(requestId: Int) {
            val handle: Long
            synchronized(state) {
                if (requestId != currentId) return
                cancelled = true
                pending = null
                handle = handles.values.firstOrNull() ?: 0L
                (state as java.lang.Object).notifyAll()
            }
            // The engine's own cancel is thread safe and stops within a chunk.
            if (handle != 0L) {
                try {
                    NativeZira.cancel(handle)
                } catch (t: Throwable) {
                    Log.w(TAG, "engine cancel failed", t)
                }
            }
        }

        override fun shutdown() {
            Log.i(TAG, "retiring worker process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        override fun sampleRate(voice: String): Int =
            if (ensureOpen(voice) != 0L) VoiceCatalog.SAMPLE_RATE else 0

        override fun probe(voice: String): String {
            if (ensureOpen(voice) == 0L) return openError.ifEmpty { "voice data missing" }
            return ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Internal storage: private, and always readable by this process.
        dataDir = File(filesDir, "voice").also { it.mkdirs() }
        Thread {
            try {
                // Warm the default voice so the first utterance pays no load cost.
                ensureOpen(VoiceCatalog.defaultVoice)
            } catch (t: Throwable) {
                Log.e(TAG, "voice warm-up failed", t)
            }
        }.name = "sapidroid-warmup"
    }

    private fun runRequest(id: Int, voice: String, rate: Int, pitch: Int, text: String) {
        val samples = try {
            if (BuildConfig.DEBUG && text.contains(HANG_PREFIX)) {
                Log.w(TAG, "debug hang for request $id")
                Thread.sleep(120_000)
                null
            } else {
                val handle = ensureOpen(voice)
                if (handle == 0L) {
                    null
                } else {
                    NativeZira.setRate(handle, rate)
                    NativeZira.setPitch(handle, pitch)
                    NativeZira.speak(handle, text)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesis failed", t)
            null
        }
        val bytes = samples?.let { toBytes(it) }
        synchronized(state) {
            if (id == currentId && !cancelled) pending = bytes
            busy = false
            finished = true
            (state as java.lang.Object).notifyAll()
        }
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            val v = s.toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        synthesis.shutdownNow()
        synchronized(state) {
            for (h in handles.values) {
                try {
                    NativeZira.close(h)
                } catch (t: Throwable) {
                    Log.w(TAG, "close failed", t)
                }
            }
            handles.clear()
        }
        super.onDestroy()
    }
}
