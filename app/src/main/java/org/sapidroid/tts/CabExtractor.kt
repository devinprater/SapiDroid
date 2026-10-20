package org.sapidroid.tts

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * The part of Microsoft's cabinet format that the voice-data installers need:
 * CFHEADER, CFFOLDER, CFFILE and MSZIP data blocks.
 *
 * MSZIP is raw deflate whose 32 KB dictionary carries across the blocks of one
 * folder. java.util.zip.Inflater does exactly that with setDictionary.
 */
object CabExtractor {

    private class Entry(val name: String, val folder: Int, val start: Long, val size: Long)
    private class Folder(val dataStart: Long, val blocks: Int, val compression: Int)

    /**
     * Extracts entries whose (original, usually mangled) name satisfies
     * [wanted] into [outDir]. [wanted] returns the name to write, or null.
     */
    fun extract(cab: File, wanted: (String) -> String?, outDir: File): List<File> {
        outDir.mkdirs()
        RandomAccessFile(cab, "r").use { raf ->
            val head = ByteArray(36)
            raf.readFully(head)
            if (String(head, 0, 4) != "MSCF") throw IOException("not a cabinet")
            val coffFiles = u32(head, 16)
            val cFolders = u16(head, 26)
            val cFiles = u16(head, 28)
            val flags = u16(head, 30)

            var reservedData = 0
            if (flags and 0x0004 != 0) {
                val extra = ByteArray(4)
                raf.readFully(extra)
                reservedData = u16(extra, 2) and 0xFF
            }
            if (flags and 0x0001 != 0) { readSz(raf); readSz(raf) }
            if (flags and 0x0002 != 0) { readSz(raf); readSz(raf) }

            val folderBytes = ByteArray(cFolders * 8)
            raf.readFully(folderBytes)
            val folders = (0 until cFolders).map { i ->
                Folder(
                    dataStart = u32(folderBytes, i * 8).toLong() and 0xFFFFFFFFL,
                    blocks = u16(folderBytes, i * 8 + 4),
                    compression = u16(folderBytes, i * 8 + 6) and 0x000F
                )
            }

            raf.seek(coffFiles.toLong())
            val entries = (0 until cFiles).map {
                val fixed = ByteArray(16)
                raf.readFully(fixed)
                val size = u32(fixed, 0).toLong() and 0xFFFFFFFFL
                val start = u32(fixed, 4).toLong() and 0xFFFFFFFFL
                val folder = u16(fixed, 8)
                Entry(readSz(raf), folder, start, size)
            }

            // The same file can appear more than once in one cabinet (the SDK
            // ships it under two components). Keep the first of each name, or
            // the copies would be appended to each other.
            val byName = LinkedHashMap<String, Triple<Entry, String, File>>()
            for (e in entries) {
                val outName = wanted(e.name) ?: continue
                byName.putIfAbsent(outName, Triple(e, outName, File(outDir, outName)))
            }
            val targets = byName.values.toList()
            if (targets.isEmpty()) return emptyList()

            val open = HashMap<File, FileOutputStream>()
            try {
                for (folderIndex in targets.map { it.first.folder }.distinct()) {
                    val folder = folders[folderIndex]
                    if (folder.compression != 1) {
                        throw IOException("cabinet compression ${folder.compression} is not MSZIP")
                    }
                    val mine = targets.filter { it.first.folder == folderIndex }
                    val lastNeeded = mine.maxOf { it.first.start + it.first.size }

                    var blockPos = folder.dataStart
                    var folderOffset = 0L
                    var dict = ByteArray(0)
                    val inflater = Inflater(true)
                    try {
                        var block = 0
                        while (block < folder.blocks && folderOffset < lastNeeded) {
                            raf.seek(blockPos)
                            val cf = ByteArray(8)
                            raf.readFully(cf)
                            val cbData = u16(cf, 4)
                            val cbUncomp = u16(cf, 6)
                            val payload = ByteArray(cbData)
                            raf.readFully(payload)
                            blockPos += 8 + cbData + reservedData
                            block++

                            val out = ByteArray(cbUncomp)
                            var outLen = 0
                            inflater.reset()
                            if (dict.isNotEmpty()) inflater.setDictionary(dict, 0, dict.size)
                            inflater.setInput(payload, 2, cbData - 2)
                            while (!inflater.finished()) {
                                val n = inflater.inflate(out, outLen, out.size - outLen)
                                if (n == 0) {
                                    if (inflater.needsInput() || inflater.needsDictionary()) break
                                } else {
                                    outLen += n
                                }
                            }

                            for ((entry, outName, dest) in mine) {
                                val a = maxOf(entry.start, folderOffset)
                                val b = minOf(entry.start + entry.size, folderOffset + outLen)
                                if (a < b) {
                                    val stream = open.getOrPut(dest) {
                                        dest.delete()
                                        FileOutputStream(dest)
                                    }
                                    stream.write(out, (a - folderOffset).toInt(), (b - a).toInt())
                                }
                            }
                            folderOffset += outLen

                            val combined = ByteArray(dict.size + outLen)
                            System.arraycopy(dict, 0, combined, 0, dict.size)
                            System.arraycopy(out, 0, combined, dict.size, outLen)
                            dict = if (combined.size <= 32768) combined
                            else combined.copyOfRange(combined.size - 32768, combined.size)
                        }
                    } finally {
                        inflater.end()
                    }
                }
            } finally {
                for (s in open.values) {
                    try {
                        s.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            return targets.map { it.third }
        }
    }

    private fun readSz(raf: RandomAccessFile): String {
        val sb = StringBuilder()
        while (true) {
            val b = raf.read()
            if (b < 0 || b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
