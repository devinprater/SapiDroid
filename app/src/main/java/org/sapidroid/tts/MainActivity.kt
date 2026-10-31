package org.sapidroid.tts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
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
    private lateinit var voiceSpinner: Spinner

    /** The voices whose data is on the device, in the picker's order. */
    private var voices: List<VoiceCatalog.Info> = emptyList()

    /** The picker's choice, kept while the picker itself is rebuilt. */
    private var selectedVoice: String? = null
    private var refreshingVoices = false
    private var engineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (refreshingVoices) return
                val info = voices.getOrNull(position) ?: return
                selectedVoice = info.label
                useVoice(info)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        android.util.Log.i(TAG, "debug=${BuildConfig.DEBUG} download=${intent.getBooleanExtra("download", false)} extras=${intent.extras}")


        findViewById<Button>(R.id.speakButton).setOnClickListener { speakTest() }
        findViewById<Button>(R.id.getSapi5Button).setOnClickListener { confirmSapi5Download() }
        findViewById<Button>(R.id.getAnnaButton).setOnClickListener { confirmAnnaDownload() }
        findViewById<Button>(R.id.oneCoreVoicesButton).setOnClickListener {
            startActivity(android.content.Intent(this, OneCoreVoicesActivity::class.java))
        }
        findViewById<Button>(R.id.getSapi4Button).setOnClickListener { confirmSapi4Download() }

        // Headless test hook, independent of the engine: --es getdata anna|sapi4|sapi5
        if (BuildConfig.DEBUG) {
            when (intent.getStringExtra("getdata")) {
                "anna" -> downloadAnna()
                "sapi4" -> downloadSapi4()
                "sapi5" -> downloadSapi5()
                "onecore" -> {
                    val requested = intent.getStringExtra("voice")
                    val pack = requested?.let { OneCorePacks.byVoice(it) }
                    if (pack != null) {
                        Thread {
                            try {
                                SappleVoices.installVoice(this, pack.voice, pack.prefix) { m ->
                                    runOnUiThread { resultText.text = m }
                                }
                                runOnUiThread {
                                    resultText.text = "${pack.voice} installed."
                                    showVoiceData()
                                    refreshVoices()
                                }
                            } catch (t: Throwable) {
                                android.util.Log.e(TAG, "pack failed", t)
                                runOnUiThread { resultText.text = "failed: ${t.message}" }
                            }
                        }.apply { name = "sapidroid-pack" }.start()
                    }
                }
            }
        }

        tts = TextToSpeech(this, { status ->
            engineReady = status == TextToSpeech.SUCCESS
            if (status == TextToSpeech.SUCCESS) {
                resultText.text = "Engine ready."
                selectedVoice?.let { name -> VoiceCatalog.infoFor(name)?.let { useVoice(it, announce = false) } }
                if (BuildConfig.DEBUG) {
                    intent.getStringExtra("localexe")?.let { installFromLocalExe(it) }
                    if (intent.getBooleanExtra("download", false)) downloadSapi5()
                }
                tryAutospeak()
            } else {
                resultText.text = "Engine init failed ($status)."
            }
        }, ENGINE_PACKAGE)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                showResult(getString(R.string.speak_started), announce = true)
            }

            override fun onDone(id: String?) {
                showResult(getString(R.string.speak_done), announce = true)
            }

            /** Android calls this form from API 21 on; the one-argument form is dead. */
            override fun onError(id: String?, errorCode: Int) {
                val message = if (errorCode == TextToSpeech.ERROR_SYNTHESIS) {
                    getString(R.string.speak_failed)
                } else {
                    getString(R.string.speak_error, errorCode)
                }
                showResult(message, announce = true)
            }

            override fun onError(id: String?) = onError(id, TextToSpeech.ERROR_SYNTHESIS)

            override fun onStop(id: String?, interrupted: Boolean) {
                showResult(getString(R.string.speak_stopped))
            }
        })
    }

    override fun onResume() {
        super.onResume()
        showVoiceData()
        refreshVoices()
    }

    /** Rebuilds the picker from the data now on the device. */
    private fun refreshVoices() {
        val previous = selectedVoice
        voices = VoiceCatalog.installed(this)
        val saved = VoiceCatalog.savedVoice(this)?.label
        val restored = voices.indexOfFirst { it.label == previous }
        val savedIndex = voices.indexOfFirst { it.label == saved }
        val preferred = voices.indexOfFirst { it.name == VoiceCatalog.defaultVoice }
        val index = when {
            restored >= 0 -> restored
            savedIndex >= 0 -> savedIndex
            preferred >= 0 -> preferred
            else -> 0
        }
        refreshingVoices = true
        try {
            val labels = voices.map { it.label }
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                if (labels.isEmpty()) listOf(getString(R.string.no_voices)) else labels
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            voiceSpinner.adapter = adapter
            voiceSpinner.isEnabled = voices.isNotEmpty()
            if (voices.isNotEmpty()) voiceSpinner.setSelection(index)
            selectedVoice = voices.getOrNull(index)?.label
        } finally {
            refreshingVoices = false
        }
        // The picker's choice is the engine's default, so other apps (Backtalk
        // among them) speak with it from their next utterance.
        if (engineReady) voices.getOrNull(index)?.let { useVoice(it, announce = false) }
    }

    /** Hands an installed voice to the engine, remembers it, says if it would not take it. */
    private fun useVoice(info: VoiceCatalog.Info, announce: Boolean = true) {
        val engine = tts ?: return
        if (!engineReady) return
        val voice = Voice(
            info.label,
            info.locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
        val result = engine.setVoice(voice)
        if (result == TextToSpeech.SUCCESS) {
            VoiceCatalog.saveVoice(this, info)
            if (announce) showResult(getString(R.string.voice_selected, info.label))
        } else if (announce) {
            showResult(getString(R.string.voice_refused, info.label), announce = true)
        }
    }

    /** Shows a message, and optionally announces it so a screen reader hears it. */
    private fun showResult(message: String, announce: Boolean = false) {
        runOnUiThread {
            resultText.text = message
            if (announce) resultText.announceForAccessibility(message)
        }
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
                    refreshVoices()
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

    /**
     * Anna comes out of Sapple's bundle: Microsoft's own files, carried there
     * for personal use. Name the host, the size and the owner before fetching.
     */
    private fun confirmAnnaDownload() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Download Microsoft Anna?")
            .setMessage(
                listOf(
                    "SapiDroid will download Anna from Sapple's app bundle at " +
                        "${SappleVoices.HOST}. It is about 200 MB and holds Anna and " +
                        "the English OneCore voices.",
                    "Anna is the voice of Windows Vista and 7. She belongs to Microsoft, " +
                        "is not part of SapiDroid, and is for your own use.",
                    "Her files are unpacked into this app's private storage. The " +
                        "downloaded bundle stays on the phone only while this app is " +
                        "open, so the OneCore voices can come from it too, and is then " +
                        "deleted."
                ).joinToString(System.lineSeparator() + System.lineSeparator())
            )
            .setPositiveButton("Download") { _, _ -> downloadAnna() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAnna() {
        val button = findViewById<Button>(R.id.getAnnaButton)
        button.isEnabled = false
        Thread {
            try {
                SappleVoices.installAnna(this) { message ->
                    runOnUiThread { resultText.text = message }
                }
                runOnUiThread {
                    resultText.text = "Anna installed."
                    button.isEnabled = true
                    showVoiceData()
                    refreshVoices()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Anna download failed", t)
                runOnUiThread {
                    resultText.text = "Anna download failed: ${t.message}"
                    button.isEnabled = true
                }
            }
        }.apply { name = "sapidroid-anna-download" }.start()
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
                    refreshVoices()
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
            append(
                listOf("David", "Zira", "Mark").joinToString(", ") { name ->
                    if (has(oneCore, "M1033$name.APM")) name else "$name missing"
                }
            )
            if (!has(oneCore, SappleVoices.SHARED_DATA)) append("; shared data missing")
            append(SEP + "Anna: ")
            append(if (has(java.io.File(filesDir, "anna"), "M1033DSK.CSD")) "installed" else "not installed")
            append(SEP + "SAPI 4: ")
            val sapi4 = has(s4, "msttssyn.dll") && has(s4, "sam.vce") &&
                has(s4, "male.vce") && has(s4, "female.vce")
            append(if (sapi4) "installed" else "not installed")
            append(SEP + "SAPI 5: ")
            val count = VoiceCatalog.sapi5Files.count { has(s5, it) }
            append(
                if (count == VoiceCatalog.sapi5Files.size) "installed"
                else "$count of ${VoiceCatalog.sapi5Files.size} files"
            )
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
                    refreshVoices()
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "local extraction failed", t)
                runOnUiThread { resultText.text = "Local extraction failed: ${t.message}" }
            }
        }.apply { name = "sapidroid-local-extract" }.start()
    }

    private fun speakTest() {
        val engine = tts
        if (engine == null || !engineReady) {
            showResult(getString(R.string.engine_not_ready), announce = true)
            return
        }
        val input = findViewById<EditText>(R.id.inputText)
        val name = VoiceCatalog.infoFor(selectedVoice)?.name ?: "David"
        val text = input.text.toString().ifBlank { "SapiDroid speaking with Microsoft $name." }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sapidroid-test") == TextToSpeech.ERROR) {
            showResult(getString(R.string.speak_refused), announce = true)
        }
    }

    private fun tryAutospeak() {
        if (!BuildConfig.DEBUG) return
        val text = intent.getStringExtra(EXTRA_AUTOSPEAK) ?: return
        intent.getStringExtra(EXTRA_VOICE)?.let { name ->
            VoiceCatalog.infoFor(name)?.let { info ->
                selectedVoice = info.label
                useVoice(info)
            }
        }
        findViewById<EditText>(R.id.inputText).setText(text)
        android.util.Log.i(TAG, "autospeak: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sapidroid-test")
    }

    override fun onDestroy() {
        SappleVoices.dropCache(this)
        tts?.shutdown()
        super.onDestroy()
    }
}
