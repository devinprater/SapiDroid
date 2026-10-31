package org.sapidroid.tts

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * The downloadable OneCore voices, grouped by language: the voice's name first,
 * then the language it speaks. A pack that is already installed says so and is
 * not offered again.
 */
class OneCoreVoicesActivity : Activity() {

    private companion object {
        private const val TAG = "SapiDroidUi"
    }

    private lateinit var list: ListView
    private lateinit var status: TextView

    /** Rows are either a language heading or a pack. */
    private val rows = mutableListOf<Any>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onecore_voices)
        list = findViewById(R.id.voiceList)
        status = findViewById(R.id.voiceStatus)
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }

        list.setOnItemClickListener { _, _, position, _ ->
            val pack = rows.getOrNull(position) as? OneCorePacks.Pack ?: return@setOnItemClickListener
            confirm(pack)
        }
        refresh()
    }

    /** Usable only with the voice's own files and the shared table beside them. */
    private fun installed(pack: OneCorePacks.Pack): Boolean {
        val dir = File(filesDir, "voice")
        return File(dir, "M1033${pack.voice}.APM").isFile &&
            File(dir, SappleVoices.SHARED_DATA).isFile
    }

    private fun refresh() {
        rows.clear()
        for ((language, packs) in OneCorePacks.all.groupBy { it.language }) {
            rows.add(language)
            rows.addAll(packs)
        }
        val labels = rows.map { row ->
            when (row) {
                is String -> row.uppercase()
                is OneCorePacks.Pack ->
                    if (installed(row)) "${row.voice} — ${row.language} (installed)"
                    else "${row.voice} — ${row.language} (download)"
                else -> ""
            }
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        // Language headings are not choices.
        list.setOnItemClickListener { _, _, position, _ ->
            val pack = rows.getOrNull(position) as? OneCorePacks.Pack
                ?: return@setOnItemClickListener
            confirm(pack)
        }
        status.text = "Choose a voice to download it." + System.lineSeparator() +
            System.lineSeparator() + OneCorePacks.NOTE
    }

    private fun confirm(pack: OneCorePacks.Pack) {
        if (installed(pack)) {
            status.text = "${pack.voice} is already installed."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Download ${pack.voice}?")
            .setMessage(
                listOf(
                    "SapiDroid will download ${pack.voice}, Microsoft's " +
                        "${pack.language} voice, from Sapple's app bundle at " +
                        "${SappleVoices.HOST}. The download is about 200 MB and holds " +
                        "every voice below.",
                    "The voice belongs to Microsoft, is not part of SapiDroid, and is for " +
                        "your own use.",
                    "The voice's files, and ${SappleVoices.SHARED_DATA}, the shared table " +
                        "the engine reads with every voice, are unpacked into this app's " +
                        "private storage. The bundle itself is deleted when the app closes."
                ).joinToString(System.lineSeparator() + System.lineSeparator())
            )
            .setPositiveButton("Download") { _, _ -> download(pack) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(pack: OneCorePacks.Pack) {
        status.text = "Downloading ${pack.voice}…"
        list.isEnabled = false
        Thread {
            try {
                SappleVoices.installVoice(this, pack.voice, pack.prefix) { message ->
                    runOnUiThread { status.text = message }
                }
                runOnUiThread {
                    status.text = "${pack.voice} is installed."
                    list.isEnabled = true
                    refresh()
                    status.text = "${pack.voice} is installed. Choose another, or close."
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "OneCore pack download failed", t)
                runOnUiThread {
                    status.text = "${pack.voice} download failed: ${t.message}"
                    list.isEnabled = true
                }
            }
        }.apply { name = "sapidroid-onecore-${pack.voice}" }.start()
    }
}
