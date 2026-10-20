package org.sapidroid.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Fetches voice data that Microsoft shipped in an installer and unpacks it into
 * the app's private folder. The files are Microsoft's; the app only ever uses
 * the user's own download.
 */
object VoiceDataInstaller {

    private const val TAG = "SapiDroidData"

    /** Microsoft Speech SDK 5.1, as archived by the Internet Archive. */
    const val SAPI5_URL =
        "https://web.archive.org/web/20110805062427if_/" +
            "https://download.microsoft.com/download/B/4/3/" +
            "B4314928-7B71-4336-9DE7-6FA4CF00B7B3/SpeechSDK51.exe"

    /** Approximate size, for the notice. */
    const val SAPI5_BYTES = 71_275_856L

    /**
     * The SAPI 4 voices, from the unpacked copy published on GitHub next to the
     * original installer. That installer's cabinet header is damaged, so the
     * files are fetched one at a time instead, each checked against the SHA-256
     * of the file Microsoft shipped.
     */
    const val SAPI4_BASE =
        "https://raw.githubusercontent.com/LouisGameDev/" +
            "Microsoft-Sam-Mary-Mike-TruVoice-WSAPI4/main/msttsl/"

    const val SAPI4_BYTES = 6_529_024L

    private val SAPI4_FILES = linkedMapOf(
        "msttssyn.dll" to (851_456L to
            "a8a2ddb9b82b4325e1315f481302cd5ba9edcf3742425ed582370f1b3940f731"),
        "sam.vce" to (1_623_552L to
            "7ebd8216f022db406c11064af11109d80c62d162db3ee6aef388e51fd90579b7"),
        "sam.cfg" to (7_680L to
            "63d67120fab657fe2fa995e0131734397a73e9e55497567d34cdc188fcc86a89"),
        "male.vce" to (1_939_456L to
            "516716a1b9ef27c44acdab6f1610f4cc3a0cc7a368b4e4607a87a147255990f0"),
        "male.cfg" to (26_624L to
            "a2de20cbd52e3df9937829c7e5ac16ea70245447abbe444719db396cac7cd911"),
        "female.vce" to (2_053_632L to
            "24aacb9a15a7fbcb72b26ea54c03f593e6a8b4f757145582ab0833c00462171f"),
        "female.cfg" to (26_624L to
            "5a2b7d9e47e555c4ae5d2b58f20e37c789ecf1136eb50cb06fc8d7ebd43396fc")
    )

    /**
     * Downloads the SDK, takes Data.Cab out of it, and extracts the eight voice
     * files into files/sapi5. Returns the names written.
     */
    fun installSapi5(
        context: Context,
        sourceExe: File? = null,
        progress: (String) -> Unit
    ): List<String> {
        val cache = File(context.cacheDir, "sapi5-download")
        cache.mkdirs()
        val exe = File(cache, "SpeechSDK51.exe")
        val cab = File(cache, "Data.Cab")

        try {
            if (sourceExe != null) {
                progress("Using the local copy…")
                sourceExe.inputStream().use { i ->
                    FileOutputStream(exe).use { o -> i.copyTo(o, 1 shl 16) }
                }
            } else if (!exe.exists() || exe.length() < 60_000_000L) {
                progress("Downloading Microsoft's Speech SDK 5.1 from archive.org…")
                download(SAPI5_URL, exe, SAPI5_BYTES) { done, total ->
                    val pct = if (total > 0) (done * 100 / total) else 0
                    progress("Downloading from archive.org: $pct%")
                }
            }

            progress("Opening the SDK…")
            cab.delete()
            // The self-extractor leaves bytes after the zip end record, which
            // Android's ZipFile refuses; trim to the record it points at.
            // The self-extractor is a zip behind an MZ stub whose central
            // directory offsets are relative to the zip, which Android's zip
            // readers reject. Its local headers are well formed, so walk
            // those instead: signature, sizes, name, then inflate the entry.
            if (!extractZipEntry(exe, "Data.Cab", cab)) {
                throw java.io.IOException("no Data.Cab in the SDK")
            }

            progress("Unpacking the voices…")
            val outDir = File(context.filesDir, "sapi5")
            val files = CabExtractor.extract(cab, ::wantedName, outDir)
            val names = files.map { it.name }.sorted()
            Log.i(TAG, "SAPI 5 installed: $names")
            if (names.size < 8) {
                throw java.io.IOException("only ${names.size} of 8 voice files were found")
            }
            return names
            // Success: the downloaded installer and the cabinet are no longer needed.
            exe.delete()
            cab.delete()
        } finally {
            cab.delete()
        }
    }

    /**
     * Fetches the seven SAPI 4 files one at a time into files/sapi4 and checks
     * each against its known SHA-256, so a partial or altered file can never
     * reach the engine. Returns the names written.
     */
    fun installSapi4(context: Context, progress: (String) -> Unit): List<String> {
        val outDir = File(context.filesDir, "sapi4")
        outDir.mkdirs()
        val names = mutableListOf<String>()
        var doneBytes = 0L
        for ((name, meta) in SAPI4_FILES) {
            val size = meta.first
            val sha = meta.second
            val dest = File(outDir, name)
            if (dest.exists() && dest.length() == size && sha256(dest) == sha) {
                progress("Already have $name")
                doneBytes += size
                names += name
                continue
            }
            dest.delete()
            progress("Downloading $name from GitHub…")
            download(SAPI4_BASE + name, dest, size) { done, _ ->
                val pct = ((doneBytes + done) * 100 / SAPI4_BYTES).toInt()
                progress("Downloading from GitHub: $pct%")
            }
            if (dest.length() != size || sha256(dest) != sha) {
                val got = dest.length()
                dest.delete()
                throw java.io.IOException("$name did not arrive intact ($got of $size bytes)")
            }
            doneBytes += size
            names += name
        }
        Log.i(TAG, "SAPI 4 installed: $names")
        return names
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
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

    /** Copies [source] up to its zip end record, so ZipFile accepts it. */
    private fun trimToZipEnd(source: File, out: File): File {
        val len = source.length()
        val window = minOf(len, 1_048_576L).toInt()
        val start = len - window
        val buf = ByteArray(window)
        java.io.RandomAccessFile(source, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buf)
        }
        var idx = -1
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 'P'.code.toByte() && buf[i + 1] == 'K'.code.toByte() &&
                buf[i + 2] == 5.toByte() && buf[i + 3] == 6.toByte()
            ) {
                idx = i
                break
            }
        }
        if (idx < 0) throw java.io.IOException("no zip end record in the download")
        val commentLen = (buf[idx + 20].toInt() and 0xFF) or
            ((buf[idx + 21].toInt() and 0xFF) shl 8)
        val end = start + idx + 22 + commentLen
        out.delete()
        source.inputStream().use { input ->
            FileOutputStream(out).use { output ->
                var remaining = end
                val b = ByteArray(1 shl 16)
                while (remaining > 0) {
                    val n = input.read(b, 0, minOf(b.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    output.write(b, 0, n)
                    remaining -= n
                }
            }
        }
        return out
    }

    /** Finds [wantedName]'s local header in an SFX zip and inflates it to [out]. */
    private fun extractZipEntry(sfx: File, wantedName: String, out: File): Boolean {
        java.io.RandomAccessFile(sfx, "r").use { raf ->
            val fileLen = raf.length()
            val buf = ByteArray(1 shl 20)
            var base = 0L
            while (base < fileLen) {
                raf.seek(base)
                val n = raf.read(buf)
                if (n <= 0) break
                var i = 0
                while (i <= n - 30) {
                    if (buf[i] == 'P'.code.toByte() && buf[i + 1] == 'K'.code.toByte() &&
                        buf[i + 2] == 3.toByte() && buf[i + 3] == 4.toByte()
                    ) {
                        val flags = u16(buf, i + 6)
                        val method = u16(buf, i + 8)
                        val csize = u32(buf, i + 18).toLong() and 0xFFFFFFFFL
                        val nlen = u16(buf, i + 26)
                        val elen = u16(buf, i + 28)
                        if (i + 30 + nlen <= n && csize > 0 && flags and 8 == 0) {
                            val name = String(buf, i + 30, nlen, Charsets.ISO_8859_1)
                            if (name.equals(wantedName, ignoreCase = true)) {
                                if (method != 8) {
                                    throw java.io.IOException("$name is not deflated (method $method)")
                                }
                                inflateEntry(raf, base + i + 30 + nlen + elen, csize, out)
                                return true
                            }
                        }
                    }
                    i++
                }
                if (n < buf.size) break
                base += n.toLong() - 64
            }
        }
        return false
    }

    private fun inflateEntry(
        raf: java.io.RandomAccessFile,
        offset: Long,
        compressedSize: Long,
        out: File
    ) {
        raf.seek(offset)
        val inflater = java.util.zip.Inflater(true)
        val inBuf = ByteArray(1 shl 16)
        val outBuf = ByteArray(1 shl 16)
        try {
            FileOutputStream(out).use { o ->
                var remaining = compressedSize
                while (remaining > 0) {
                    val want = minOf(inBuf.size.toLong(), remaining).toInt()
                    raf.readFully(inBuf, 0, want)
                    remaining -= want
                    inflater.setInput(inBuf, 0, want)
                    while (true) {
                        val n = inflater.inflate(outBuf)
                        if (n == 0) break
                        o.write(outBuf, 0, n)
                    }
                }
                while (!inflater.finished()) {
                    val n = inflater.inflate(outBuf)
                    if (n == 0) break
                    o.write(outBuf, 0, n)
                }
            }
        } finally {
            inflater.end()
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun wantedName(original: String): String? {
        val lower = original.lowercase()
        return when {
            lower.contains("sam.spd") -> "Sam.spd"
            lower.contains("sam.sdf") -> "Sam.sdf"
            lower.contains("mike.spd") -> "Mike.spd"
            lower.contains("mike.sdf") -> "Mike.sdf"
            lower.contains("mary.spd") -> "Mary.spd"
            lower.contains("mary.sdf") -> "Mary.sdf"
            lower.contains("ltts1033.lxa") -> "LTTS1033.LXA"
            lower.contains("r1033tts.lxa") -> "r1033tts.LXA"
            else -> null
        }
    }

    /**
     * Downloads to [dest], resuming a partial file, and fails unless the whole
     * expected size arrived. archive.org ends long transfers now and then, so
     * a partial file is kept and picked up again rather than thrown away.
     */
    private fun download(url: String, dest: File, expected: Long, progress: (Long, Long) -> Unit) {
        var attempt = 0
        while (dest.length() < expected) {
            if (attempt++ >= 4) {
                throw java.io.IOException(
                    "download stopped at ${dest.length()} of $expected bytes"
                )
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "SapiDroid")
                    if (dest.length() > 0) {
                        setRequestProperty("Range", "bytes=${dest.length()}-")
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299 && code != 206) {
                    throw java.io.IOException("download failed: HTTP $code")
                }
                val total = if (expected > 0) expected else connection.contentLengthLong
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
                Log.w(TAG, "download attempt $attempt ended: ${t.message}")
                if (attempt >= 4) throw t
            } finally {
                connection?.disconnect()
            }
        }
    }
}
