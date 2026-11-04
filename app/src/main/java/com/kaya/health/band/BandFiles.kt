package com.kaya.health.band

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Files the band sends (type 4097), such as last night's beat-to-beat record "2026-1-15-0700_RRI_Data.bin.txt".
 * The band streams them when an app asks (NoiseFit does on every sync); because the Bluetooth link is shared,
 * Kaya sees them too and keeps a copy, on the phone only.
 *
 * Layout seen so far: {1: 4097, 16: {1: {1: file name, 2: data chunk, other fields: position/size}}}.
 */
class BandFiles(context: Context) {
    private val dir = File(context.filesDir, "bandfiles").apply { mkdirs() }

    /** True once an RRI file named for [day] (e.g. "2026-1-15-0700_RRI_Data…") has been saved. */
    fun hasNightFile(day: java.time.LocalDate): Boolean =
        list().any { it.name.startsWith("${day.year}-${day.monthValue}-${day.dayOfMonth}-") && it.name.contains("RRI") }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /** Saves one 4097 chunk. Chunks are stored by position when the band gives one, so repeats don't double up. */
    fun capture(payload: ByteArray) {
        val outer = Wire.bytes(payload, 16) ?: return
        val inner = Wire.bytes(outer, 1) ?: return
        val fields = Wire.parseOrNull(inner) ?: return
        val name = fields.firstOrNull { it.number == 1 && it.wireType == 2 }?.bytes?.toString(Charsets.US_ASCII)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: return
        val data = fields.firstOrNull { it.number == 2 && it.wireType == 2 }?.bytes ?: return
        val meta = fields.filter { it.number > 2 }.joinToString(" ") { if (it.wireType == 2) "${it.number}:<${it.bytes!!.size}b>" else "${it.number}=${it.varint}" }
        // Field 3 is a flag (1 = more to come, 2 = last), not a position: keep chunks in arrival order and skip
        // exact repeats (the band can resend a chunk).
        val chunkDir = File(dir, "$name.parts").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }.take(16)
        val existing = chunkDir.listFiles().orEmpty()
        if (existing.none { it.name.endsWith("-$hash") }) File(chunkDir, "%06d-%s".format(existing.size, hash)).writeBytes(data)
        // Reassemble the whole file from its parts every time (cheap; files are tens of KB).
        File(dir, name).outputStream().use { out -> chunkDir.listFiles()?.sortedBy { it.name }?.forEach { out.write(it.readBytes()) } }
        Log.i(BandClient.TAG, "file chunk $name: ${data.size} bytes $meta")
    }

    /** Research: logs every saved file's size and bytes (tag KayaProbe). */
    fun dump(maxBytes: Int = 120_000) {
        for (f in list()) {
            val b = f.readBytes()
            Log.i("KayaProbe", "file ${f.name} ${b.size} bytes")
            var off = 0
            while (off < minOf(b.size, maxBytes)) {
                Log.i("KayaProbe", "file ${f.name} @$off: " + b.copyOfRange(off, minOf(b.size, off + 400)).joinToString(" ") { "%02x".format(it) })
                off += 400
            }
        }
    }
}
