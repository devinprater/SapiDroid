package org.sapidroid.tts

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * SapiDroid: Microsoft's OneCore voices (David, Zira) as an Android TTS engine.
 *
 * The engine runs in a private worker process, so a wedged native call is
 * cleared by replacing that process instead of taking this service down.
 */
class SapiDroidService : TextToSpeechService() {

    companion object {
        private const val TAG = "SapiDroid"
        private const val BUSY = SapiDroidWorkerService.BUSY
        private const val PULL_BYTES = 64 * 1024
        private const val PULL_WAIT_MS = 200
        private const val SAY_WAIT_MS = 400
        private const val RETIRE_GRACE_MS = 1_500L
    }

    private val stopped = AtomicBoolean(false)

    @Volatile private var currentWorker: IZiraWorker? = null
    @Volatile private var currentRequest: Int = -1

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                currentWorker = SapiDroidWorkers.acquire(this)
            } catch (t: Throwable) {
                Log.e(TAG, "engine worker warm-up failed", t)
            }
        }.name = "sapidroid-worker-warmup"
    }

    override fun onDestroy() {
        val worker = currentWorker
        currentWorker = null
        SapiDroidWorkers.retire(worker)
        super.onDestroy()
    }

    override fun onStop() {
        stopped.set(true)
        val worker = currentWorker ?: return
        val id = currentRequest
        if (id <= 0) return
        try {
            worker.cancel(id)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed", t)
        }
        Thread {
            try {
                Thread.sleep(RETIRE_GRACE_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            val done = try {
                worker.finished(id)
            } catch (t: Throwable) {
                false
            }
            if (!done) {
                Log.w(TAG, "engine still busy after stop: retiring worker")
                SapiDroidWorkers.retire(worker)
            }
        }.name = "sapidroid-retire-check"
    }

    // ------------------------------------------------------------------
    // Voices and languages
    // ------------------------------------------------------------------

    override fun onGetVoices(): MutableList<Voice> = VoiceCatalog.all.map { info ->
        Voice(
            info.label,
            info.locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet<String>()
        )
    }.toMutableList()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (VoiceCatalog.infoFor(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when {
            lang == null -> TextToSpeech.LANG_NOT_SUPPORTED
            !lang.startsWith("en", ignoreCase = true) -> TextToSpeech.LANG_NOT_SUPPORTED
            country.equals("US", ignoreCase = true) || country.equals("USA", ignoreCase = true) ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        VoiceCatalog.forLocale(lang, country).label

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped.set(false)
        try {
            synthesize(request, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesis failed", t)
            try {
                callback.error()
            } catch (t2: Throwable) {
                Log.e(TAG, "callback.error failed", t2)
            }
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            callback.done()
            return
        }

        val info = VoiceCatalog.infoFor(request.voiceName)
            ?: VoiceCatalog.forLocale(request.language, request.country)
        val voice = info.label
        val ratePercent = request.speechRate.takeIf { it > 0 } ?: 100
        val pitchPercent = request.pitch.takeIf { it > 0 } ?: 100

        Log.i(TAG, "synth start voice=$voice engine=${info.engine} chars=${text.length} " +
            "rate=$ratePercent pitch=$pitchPercent")

        var worker = currentWorker
        val pcm: ShortArray? = try {
            if (worker == null || !worker.asBinder().isBinderAlive) {
                worker = SapiDroidWorkers.acquire(this)
                currentWorker = worker
            }
            speak(worker, voice, ratePercent, pitchPercent, text)
        } catch (t: Throwable) {
            Log.e(TAG, "worker synthesis failed", t)
            null
        }

        if (stopped.get()) {
            callback.done()
            return
        }
        if (pcm == null || pcm.isEmpty()) {
            callback.error()
            return
        }

        val rateHz = try {
            worker?.sampleRate(voice) ?: 0
        } catch (t: Throwable) {
            0
        }.takeIf { it > 0 } ?: VoiceCatalog.SAMPLE_RATE

        try {
            callback.start(rateHz, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            val bytes = shortsToLittleEndian(pcm)
            val maxBuf = callback.maxBufferSize.coerceAtLeast(1024)
            var off = 0
            while (off < bytes.size && !stopped.get()) {
                val n = minOf(maxBuf, bytes.size - off).coerceAtLeast(2)
                if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) break
                off += n
            }
            callback.done()
            Log.i(TAG, "request done voice=$voice frames=${pcm.size} sr=$rateHz")
        } catch (t: Throwable) {
            Log.e(TAG, "streaming failed", t)
            try {
                callback.error()
            } catch (t2: Throwable) {
                Log.e(TAG, "callback.error failed", t2)
            }
        }
    }

    /** One utterance through the worker, pulled in bounded blocks. */
    private fun speak(
        startWorker: IZiraWorker,
        voice: String,
        ratePercent: Int,
        pitchPercent: Int,
        text: String
    ): ShortArray? {
        var worker = startWorker
        var replaced = false
        while (true) {
            try {
                return speakOnce(worker, voice, ratePercent, pitchPercent, text)
            } catch (e: WorkerGone) {
                if (replaced || stopped.get()) return null
                replaced = true
                Log.w(TAG, "replacing engine worker: ${e.message}")
                worker = SapiDroidWorkers.replace(this, worker)
                currentWorker = worker
            }
        }
    }

    private class WorkerGone(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun speakOnce(
        worker: IZiraWorker,
        voice: String,
        ratePercent: Int,
        pitchPercent: Int,
        text: String
    ): ShortArray? {
        val id = try {
            worker.say(voice, ratePercent, pitchPercent, text, SAY_WAIT_MS)
        } catch (t: Throwable) {
            SapiDroidWorkers.retire(worker)
            throw WorkerGone("say failed", t)
        }
        if (id == BUSY) {
            SapiDroidWorkers.retire(worker)
            throw WorkerGone("engine stayed busy")
        }
        if (id < 0) throw WorkerGone("say refused")
        currentRequest = id
        val buffer = ByteArrayOutputStream(text.length * 32)
        try {
            while (true) {
                if (stopped.get()) {
                    try {
                        worker.cancel(id)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cancel failed", t)
                    }
                    return null
                }
                val block = worker.pull(id, PULL_BYTES, PULL_WAIT_MS)
                if (block != null && block.isNotEmpty()) {
                    buffer.write(block)
                    continue
                }
                if (worker.finished(id)) break
            }
        } catch (t: Throwable) {
            SapiDroidWorkers.retire(worker)
            throw WorkerGone("pull failed", t)
        } finally {
            currentRequest = -1
        }
        return littleEndianToShorts(buffer.toByteArray())
    }

    private fun shortsToLittleEndian(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            val v = s.toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun littleEndianToShorts(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        if (count == 0) return ShortArray(0)
        val out = ShortArray(count)
        var j = 0
        for (i in 0 until count) {
            out[i] = ((bytes[j].toInt() and 0xFF) or (bytes[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        return out
    }
}
