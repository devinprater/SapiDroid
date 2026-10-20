package org.sapidroid.tts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Settings and a test line. The voice data is staged into the app's external files folder. */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "SapiDroidUi"
        private const val EXTRA_AUTOSPEAK = "autospeak"
        private const val EXTRA_VOICE = "voice"
        private const val ENGINE_PACKAGE = "org.sapidroid.tts"
    }

    private var tts: TextToSpeech? = null
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)

        val voiceDir = java.io.File(filesDir, "voice")
        val haveDavid = java.io.File(voiceDir, "M1033David.APM").exists()
        val haveZira = java.io.File(voiceDir, "M1033Zira.APM").exists()
        val haveLoc = java.io.File(voiceDir, "MSTTSLocEnUS.dat").exists()
        statusText.text = buildString {
            append("Voice data folder: ${voiceDir.absolutePath}\n")
            append("David: ${if (haveDavid) "present" else "missing"}\n")
            append("Zira: ${if (haveZira) "present" else "missing"}\n")
            append("Shared data: ${if (haveLoc) "present" else "missing"}")
        }

        findViewById<Button>(R.id.speakButton).setOnClickListener { speakTest() }

        tts = TextToSpeech(this, { status ->
            resultText.text = if (status == TextToSpeech.SUCCESS) "Engine ready." else "Engine init failed ($status)."
            if (status == TextToSpeech.SUCCESS) tryAutospeak()
        }, ENGINE_PACKAGE)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                runOnUiThread { resultText.text = "Speaking…" }
            }

            override fun onDone(id: String?) {
                runOnUiThread { resultText.text = "Done." }
            }

            override fun onError(id: String?) {
                runOnUiThread { resultText.text = "Error speaking. Is the voice data in the folder above?" }
            }
        })
    }

    private fun speakTest() {
        val input = findViewById<EditText>(R.id.inputText)
        val text = input.text.toString().ifBlank { "SapiDroid speaking with Microsoft David." }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sapidroid-test")
    }

    private fun tryAutospeak() {
        if (!BuildConfig.DEBUG) return
        val text = intent.getStringExtra(EXTRA_AUTOSPEAK) ?: return
        intent.getStringExtra(EXTRA_VOICE)?.let { name ->
            VoiceCatalog.infoFor(name)?.let { info ->
                tts?.voice = android.speech.tts.Voice(
                    info.name, info.locale,
                    android.speech.tts.Voice.QUALITY_HIGH,
                    android.speech.tts.Voice.LATENCY_NORMAL,
                    false, emptySet()
                )
            }
        }
        findViewById<EditText>(R.id.inputText).setText(text)
        android.util.Log.i(TAG, "autospeak: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sapidroid-test")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
