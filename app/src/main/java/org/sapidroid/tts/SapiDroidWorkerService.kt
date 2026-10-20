package org.sapidroid.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Holds both engine families and their open voices, in its own process.
 *
 * One request at a time on one thread. Cancellation calls the engine's own
 * thread-safe stop; a call that will not return is cleared by the owner
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
    private var currentVoice = ""
    private var busy = false
    private var finished = true
    private var cancelled = false
    private var pending: ByteArray? = null

    private val ziraHandles = HashMap<String, Long>()
    private val s4Handles = HashMap<String, Long>()
    private val s5Handles = HashMap<String, Long>()

    @Volatile private var ziraDir: File? = null
    @Volatile private var s4Dir: File? = null
    @Volatile private var s5Dir: File? = null
    @Volatile private var openError: String = ""
    private var s4Initialised = false

    // ------------------------------------------------------------------
    // Opening voices
    // ------------------------------------------------------------------

    private fun openZira(voice: String): Long {
        ziraHandles[voice]?.let { if (it != 0L) return it }
        val dir = ziraDir ?: return 0
        val handle = NativeZira.open(dir.absolutePath, voice)
        if (handle == 0L) {
            openError = "no OneCore voice data for $voice in ${dir.absolutePath}"
            Log.e(TAG, openError)
            return 0
        }
        ziraHandles[voice] = handle
        return handle
    }

    private fun openSapi4(mode: String): Long {
        s4Handles[mode]?.let { if (it != 0L) return it }
        val dir = s4Dir ?: return 0
        if (!s4Initialised) {
            val dll = File(dir, "msttssyn.dll")
            if (!dll.exists()) {
                openError = "no SAPI 4 data: ${dll.absolutePath} is missing"
                Log.e(TAG, openError)
                return 0
            }
            if (NativeSapi4.init(dll.absolutePath) != 0) {
                openError = "SAPI 4 engine data would not load from ${dll.absolutePath}"
                Log.e(TAG, openError)
                return 0
            }
            s4Initialised = true
        }
        val handle = NativeSapi4.open(dir.absolutePath, mode)
        if (handle == 0L) {
            openError = "SAPI 4 mode $mode did not open"
            Log.e(TAG, openError)
            return 0
        }
        s4Handles[mode] = handle
        return handle
    }

    private fun openSapi5(voice: String): Long {
        s5Handles[voice]?.let { if (it != 0L) return it }
        val dir = s5Dir ?: return 0
        // Internal names carry a 5 so they cannot collide with the SAPI 4 voices.
        val engineVoice = voice.removeSuffix("5")
        val handle = NativeSapi5.open(dir.absolutePath, engineVoice)
        if (handle == 0L) {
            openError = "no SAPI 5 voice data for $engineVoice in ${dir.absolutePath}"
            Log.e(TAG, openError)
            return 0
        }
        s5Handles[voice] = handle
        return handle
    }

    // ------------------------------------------------------------------
    // Binder
    // ------------------------------------------------------------------

    private val binder = object : IZiraWorker.Stub() {

        override fun say(
            voice: String,
            ratePercent: Int,
            pitchPercent: Int,
            text: String,
            waitMs: Int
        ): Int {
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
                currentVoice = voice
                busy = true
                finished = false
                cancelled = false
                pending = null
            }
            synthesis.submit { runRequest(id, voice, ratePercent, pitchPercent, text) }
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
            val engine: VoiceCatalog.Engine?
            val handle: Long
            synchronized(state) {
                if (requestId != currentId) return
                cancelled = true
                pending = null
                engine = VoiceCatalog.infoFor(currentVoice)?.engine
                handle = when (engine) {
                    VoiceCatalog.Engine.ONECORE -> ziraHandles[currentVoice] ?: 0L
                    VoiceCatalog.Engine.SAPI4 -> s4Handles[currentVoice] ?: 0L
                    VoiceCatalog.Engine.SAPI5 -> s5Handles[currentVoice] ?: 0L
                    null -> 0L
                }
                (state as java.lang.Object).notifyAll()
            }
            if (handle != 0L) {
                try {
                    when (engine) {
                        VoiceCatalog.Engine.ONECORE -> NativeZira.cancel(handle)
                        VoiceCatalog.Engine.SAPI4 -> NativeSapi4.cancel(handle)
                        VoiceCatalog.Engine.SAPI5 -> NativeSapi5.cancel(handle)
                        null -> {}
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "engine cancel failed", t)
                }
            }
        }

        override fun shutdown() {
            Log.i(TAG, "retiring worker process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        override fun sampleRate(voice: String): Int {
            val info = VoiceCatalog.infoFor(voice) ?: return 0
            return when (info.engine) {
                VoiceCatalog.Engine.ONECORE ->
                    if (openZira(info.name) != 0L) VoiceCatalog.SAMPLE_RATE else 0
                VoiceCatalog.Engine.SAPI4 -> {
                    val h = openSapi4(info.name)
                    if (h != 0L) NativeSapi4.sampleRate(h) else 0
                }
                VoiceCatalog.Engine.SAPI5 -> {
                    val h = openSapi5(info.name)
                    if (h != 0L) NativeSapi5.sampleRate(h) else 0
                }
            }
        }

        override fun probe(voice: String): String {
            val info = VoiceCatalog.infoFor(voice) ?: return "unknown voice $voice"
            val ok = when (info.engine) {
                VoiceCatalog.Engine.ONECORE -> openZira(info.name) != 0L
                VoiceCatalog.Engine.SAPI4 -> openSapi4(info.name) != 0L
                VoiceCatalog.Engine.SAPI5 -> openSapi5(info.name) != 0L
            }
            return if (ok) "" else openError.ifEmpty { "voice data missing" }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ziraDir = File(filesDir, "voice").also { it.mkdirs() }
        s4Dir = File(filesDir, "sapi4").also { it.mkdirs() }
        s5Dir = File(filesDir, "sapi5").also { it.mkdirs() }
        Thread {
            try {
                openZira(VoiceCatalog.defaultVoice)
            } catch (t: Throwable) {
                Log.e(TAG, "OneCore warm-up failed", t)
            }
        }.name = "sapidroid-warmup"
    }

    // ------------------------------------------------------------------
    // Speaking
    // ------------------------------------------------------------------

    private fun runRequest(
        id: Int,
        voice: String,
        ratePercent: Int,
        pitchPercent: Int,
        text: String
    ) {
        val info = VoiceCatalog.infoFor(voice)
        val samples = try {
            if (BuildConfig.DEBUG && text.contains(HANG_PREFIX)) {
                Log.w(TAG, "debug hang for request $id")
                Thread.sleep(120_000)
                null
            } else if (info == null) {
                null
            } else when (info.engine) {
                VoiceCatalog.Engine.ONECORE -> {
                    val h = openZira(info.name)
                    if (h == 0L) null else {
                        NativeZira.setRate(h, sapi5Rate(ratePercent))
                        NativeZira.setPitch(h, sapi5Pitch(pitchPercent))
                        NativeZira.speak(h, text)
                    }
                }
                VoiceCatalog.Engine.SAPI5 -> {
                    val h = openSapi5(info.name)
                    if (h == 0L) null else {
                        NativeSapi5.setRate(h, sapi5Rate(ratePercent))
                        NativeSapi5.setPitch(h, sapi5Pitch(pitchPercent))
                        NativeSapi5.speak(h, text)
                    }
                }
                VoiceCatalog.Engine.SAPI4 -> {
                    val h = openSapi4(info.name)
                    if (h == 0L) null else {
                        val lim = NativeSapi4.limits(h)
                        if (lim != null && lim.size >= 6) {
                            NativeSapi4.setSpeed(
                                h,
                                scale(lim[3], ratePercent, lim[4], lim[5])
                            )
                            NativeSapi4.setPitch(
                                h,
                                scale(lim[0], pitchPercent, lim[1], lim[2])
                            )
                        }
                        NativeSapi4.speak(h, text.toByteArray(charset("windows-1252")))
                    }
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

    /** Android's percent applied to one of the engine's own limits. */
    private fun scale(default: Int, percent: Int, min: Int, max: Int): Int {
        if (percent <= 0) return default
        val wanted = (default * (percent / 100.0)).roundToInt()
        return wanted.coerceIn(min, max)
    }

    /** Android percent -> SAPI 5 scale, -10..10, one step = 3^(1/10). */
    private fun sapi5Rate(percent: Int): Int {
        if (percent <= 0 || abs(percent - 100) <= 3) return 0
        return (10.0 * ln(percent / 100.0) / ln(3.0)).roundToInt().coerceIn(-10, 10)
    }

    /** Android pitch ratio -> -10..10, one unit = half a semitone. */
    private fun sapi5Pitch(percent: Int): Int {
        if (percent <= 0 || abs(percent - 100) <= 2) return 0
        return (24.0 * log2(percent / 100.0)).roundToInt().coerceIn(-10, 10)
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
            for (h in ziraHandles.values) {
                try {
                    NativeZira.close(h)
                } catch (t: Throwable) {
                    Log.w(TAG, "zira close failed", t)
                }
            }
            ziraHandles.clear()
            for (h in s4Handles.values) {
                try {
                    NativeSapi4.close(h)
                } catch (t: Throwable) {
                    Log.w(TAG, "sapi4 close failed", t)
                }
            }
            s4Handles.clear()
            for (h in s5Handles.values) {
                try {
                    NativeSapi5.close(h)
                } catch (t: Throwable) {
                    Log.w(TAG, "sapi5 close failed", t)
                }
            }
            s5Handles.clear()
        }
        super.onDestroy()
    }
}
