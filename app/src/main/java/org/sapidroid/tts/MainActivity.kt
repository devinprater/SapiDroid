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
        private val SEP = System.lineSeparator() + System.lineSeparator()
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
        showVoiceData()
        android.util.Log.i(TAG, "debug=${BuildConfig.DEBUG} download=${intent.getBooleanExtra("download", false)} extras=${intent.extras}")


        findViewById<Button>(R.id.speakButton).setOnClickListener { speakTest() }
        findViewById<Button>(R.id.getSapi5Button).setOnClickListener { confirmSapi5Download() }
        findViewById<Button>(R.id.getSapi4Button).setOnClickListener { confirmSapi4Download() }

        tts = TextToSpeech(this, { status ->
            resultText.text = if (status == TextToSpeech.SUCCESS) "Engine ready." else "Engine init failed ($status)."
            if (status == TextToSpeech.SUCCESS) {
                if (BuildConfig.DEBUG) {
                    intent.getStringExtra("localexe")?.let { installFromLocalExe(it) }
                    if (intent.getBooleanExtra("download", false)) downloadSapi5()
                }
                tryAutospeak()
            }
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

    /**
     * The SAPI 5 voices live in Microsoft's Speech SDK 5.1, which is only
     * available from the Internet Archive's copy of Microsoft's download. Ask
     * first, say where it comes from, then fetch and unpack it.
     */
    private fun confirmSapi5Download() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Download the SAPI 5 voices?")
            .setMessage(
                listOf(
                    "SapiDroid will download Microsoft's Speech SDK 5.1 from the Internet " +
                        "Archive (web.archive.org), a saved copy of Microsoft's own " +
                        "download. It is about 71 MB.",
                    "It contains the SAPI 5 voices Sam, Mike and Mary. Those voices " +
                        "belong to Microsoft, are not part of SapiDroid, and are for " +
                        "your own use.",
                    "The download is unpacked into this app's private storage and " +
                        "the downloaded file is deleted afterwards."
                ).joinToString(System.lineSeparator() + System.lineSeparator())
            )
            .setPositiveButton("Download") { _, _ -> downloadSapi5() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The SAPI 4 voices come from the unpacked copy on GitHub, next to the
     * original 1999 installer, whose packing is damaged. Ask first and name the
     * host, the size and who owns the voices.
     */
    private fun confirmSapi4Download() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Download the SAPI 4 voices?")
            .setMessage(
                listOf(
                    "SapiDroid will download the SAPI 4 voices from GitHub " +
                        "(github.com), where the files from Microsoft's old installer " +
                        "are published unpacked. They are about 6.5 MB.",
                    "The voices are Sam, Mike and Mary. They belong to Microsoft, are " +
                        "not part of SapiDroid, and are for your own use.",
                    "They are saved in this app's private storage, and each file is " +
                        "checked against Microsoft's copy before it is used."
                ).joinToString(System.lineSeparator() + System.lineSeparator())
            )
            .setPositiveButton("Download") { _, _ -> downloadSapi4() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadSapi4() {
        val button = findViewById<Button>(R.id.getSapi4Button)
        button.isEnabled = false
        Thread {
            try {
                val names = VoiceDataInstaller.installSapi4(this) { message ->
                    runOnUiThread { resultText.text = message }
                }
                runOnUiThread {
                    resultText.text = "SAPI 4 voices installed: ${names.joinToString(", ")}"
                    button.isEnabled = true
                    showVoiceData()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "SAPI 4 download failed", t)
                runOnUiThread {
                    resultText.text = "SAPI 4 download failed: ${t.message}"
                    button.isEnabled = true
                }
            }
        }.apply { name = "sapidroid-sapi4-download" }.start()
    }

    private fun downloadSapi5() {
        android.util.Log.i(TAG, "downloadSapi5 start")
        val button = findViewById<Button>(R.id.getSapi5Button)
        button.isEnabled = false
        Thread {
            android.util.Log.i(TAG, "download thread running")
            try {
                val names = VoiceDataInstaller.installSapi5(this) { message ->
                    runOnUiThread { resultText.text = message }
                }
                runOnUiThread {
                    resultText.text = "SAPI 5 voices installed: ${names.joinToString(", ")}"
                    button.isEnabled = true
                    showVoiceData()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "SAPI 5 download failed", t)
                runOnUiThread {
                    resultText.text = "Download failed: ${t.message}"
                    button.isEnabled = true
                }
            }
        }.apply { name = "sapidroid-sapi5-download" }.start()
    }

    private fun showVoiceData() {
        val oneCore = java.io.File(filesDir, "voice")
        val s4 = java.io.File(filesDir, "sapi4")
        val s5 = java.io.File(filesDir, "sapi5")
        fun has(dir: java.io.File, name: String) = java.io.File(dir, name).exists()
        statusText.text = buildString {
            append("OneCore: ")
            append(if (has(oneCore, "M1033David.APM")) "David " else "David missing ")
            append(if (has(oneCore, "M1033Zira.APM")) "Zira" else "Zira missing")
            append(if (has(oneCore, "M1033Mark.APM")) " Mark" else " Mark missing")
            append(if (has(oneCore, "MSTTSLocEnUS.dat")) "" else ", shared data missing")
            append(SEP + "SAPI 4: ")
            append(
                if (has(s4, "msttssyn.dll") && has(s4, "sam.vce")) "installed"
                else "not installed"
            )
            append(SEP + "SAPI 5: ")
            val count = listOf(
                "Sam.spd", "Sam.sdf", "Mike.spd", "Mike.sdf",
                "Mary.spd", "Mary.sdf", "LTTS1033.LXA", "r1033tts.LXA"
            ).count { has(s5, it) }
            append(if (count == 8) "installed" else "$count of 8 files")
        }
    }

    /** Debug only: run the unpacking from a file already on the device. */
    private fun installFromLocalExe(path: String) {
        Thread {
            try {
                val names = VoiceDataInstaller.installSapi5(this, java.io.File(path)) { message ->
                    runOnUiThread { resultText.text = message }
                }
                runOnUiThread {
                    resultText.text = "Local extraction: ${names.size} files"
                    showVoiceData()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "local extraction failed", t)
                runOnUiThread { resultText.text = "Local extraction failed: ${t.message}" }
            }
        }.apply { name = "sapidroid-local-extract" }.start()
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
                    info.label, info.locale,
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
