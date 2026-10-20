package org.sapidroid.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Voice data from Sapple, the iPhone app that carries Microsoft's voices as
 * plain files inside its bundle. One download holds Microsoft Anna and the
 * Windows 10/11 English OneCore voices; the app takes out only what it needs
 * and leaves the rest.
 *
 * The data belongs to Microsoft. Sapple includes it for personal use, and it is
 * not part of SapiDroid; the notice says so before anything is downloaded.
 */
object SappleVoices {

    private const val TAG = "SapiDroidData"

    const val IPA_URL = "https://quintonwilliams.me/sapple/Sapple-1.1.1.ipa"
    const val HOST = "quintonwilliams.me"
    const val BYTES = 199_534_081L
    const val SHA256 = "5fc076aedf21473caf70db663632005839c637d7f0c14ebd0dd46bbfbb9829f1"

    private const val APP = "Payload/Sapple.app/PlugIns/ClassicVoicesExtension.appex/"

    /** True when the bundle is already on disk, so a second pick costs nothing. */
    fun cached(context: Context): File? {
        val f = File(File(context.cacheDir, "sapple"), "Sapple.ipa")
        return if (f.exists() && f.length() == BYTES) f else null
    }

    /**
     * Anna's eleven files. Returns the names written into files/anna.
     */
    fun installAnna(context: Context, progress: (String) -> Unit): List<String> {
        val ipa = ensure(context, progress)
        val out = File(context.filesDir, "anna").also { it.mkdirs() }
        val wanted = listOf("CSD", "IDX", "UNT", "CRT", "APL", "UDT", "TTS", "LTS", "WIH", "KEY", "HAN")
        val written = mutableListOf<String>()
        java.util.zip.ZipFile(ipa).use { zip ->
            for (ext in wanted) {
                val name = "M1033DSK.$ext"
                val entry = zip.getEntry(APP + "AnnaVoice/" + name)
                    ?: throw java.io.IOException("$name is not in the bundle")
                val dest = File(out, name)
                dest.delete()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output, 1 shl 16) }
                }
                written += name
            }
        }
        Log.i(TAG, "Anna installed from Sapple: ${written.size} files")
        return written
    }

    /**
     * One voice's own files, renamed to the M1033<voice> form the engine reads.
     * [prefix] is the name the bundle gives them, such as M2057George.
     */
    fun installVoice(
        context: Context,
        voice: String,
        prefix: String,
        progress: (String) -> Unit
    ): List<String> {
        val ipa = ensure(context, progress)
        val out = File(context.filesDir, "voice").also { it.mkdirs() }
        val written = mutableListOf<String>()
        java.util.zip.ZipFile(ipa).use { zip ->
            val entries = zip.entries().toList()
                .filter { it.name.startsWith(APP + "OneCoreVoice/$prefix.") }
            if (entries.isEmpty()) throw java.io.IOException("$prefix is not in the bundle")
            for (entry in entries) {
                val ext = entry.name.substringAfterLast('.')
                val dest = File(out, "M1033$voice.$ext")
                dest.delete()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output, 1 shl 16) }
                }
                written += dest.name
            }
        }
        Log.i(TAG, "$voice installed from Sapple: $written")
        return written
    }

    /** Deletes the cached bundle; called when the app closes. */
    fun dropCache(context: Context) {
        try {
            File(context.cacheDir, "sapple").deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "could not delete the cached bundle", t)
        }
    }

    /** Downloads the bundle once and keeps it for the next pick. */
    private fun ensure(context: Context, progress: (String) -> Unit): File {
        cached(context)?.let {
            progress("Using the bundle already downloaded.")
            return it
        }
        val dir = File(context.cacheDir, "sapple").also { it.mkdirs() }
        val dest = File(dir, "Sapple.ipa")
        dest.delete()
        progress("Downloading Sapple's bundle from ${HOST}…")
        download(dest) { done, total ->
            val pct = if (total > 0) (done * 100 / total) else 0
            progress("Downloading from $HOST: $pct%")
        }
        if (dest.length() != BYTES) {
            val got = dest.length()
            dest.delete()
            throw java.io.IOException("the bundle is $got bytes, not $BYTES")
        }
        val hash = sha256(dest)
        if (hash != SHA256) {
            dest.delete()
            throw java.io.IOException("the bundle did not arrive intact")
        }
        return dest
    }

    private fun download(dest: File, progress: (Long, Long) -> Unit) {
        var attempt = 0
        while (dest.length() < BYTES) {
            if (attempt++ >= 4) {
                throw java.io.IOException("download stopped at ${dest.length()} of $BYTES bytes")
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (java.net.URL(IPA_URL).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "SapiDroid")
                    if (dest.length() > 0) setRequestProperty("Range", "bytes=${dest.length()}-")
                }
                val code = connection.responseCode
                if (code !in 200..299 && code != 206) {
                    throw java.io.IOException("download failed: HTTP $code")
                }
                val total = BYTES
                var done = dest.length()
                val append = dest.length() > 0 && code == 206
                connection.inputStream.use { input ->
                    FileOutputStream(dest, append).use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
            } catch (t: java.io.IOException) {
                Log.w(TAG, "Sapple download attempt $attempt ended: ${t.message}")
                if (attempt >= 4) throw t
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
