package com.kaya.health.band

import java.io.ByteArrayOutputStream

/**
 * Just enough of the protobuf wire format for the Noise REP. The band reuses Xiaomi's wearable
 * "Command" envelope (field 1 = type, field 2 = subtype, nested messages in higher fields), so
 * messages are read by walking fields instead of a fixed schema.
 * Ported from OpenNoiseRep (MIT, github.com/mohammedr3za/OpenNoiseRep).
 */
object Wire {
    class Field(val number: Int, val wireType: Int, val varint: Long = 0, val bytes: ByteArray? = null)

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var pos = start
        var shift = 0
        var result = 0L
        while (true) {
            require(pos < buf.size) { "truncated varint" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
            require(shift <= 63) { "varint too long" }
        }
    }

    fun parse(buf: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var pos = 0
        while (pos < buf.size) {
            val (tag, p1) = readVarint(buf, pos)
            pos = p1
            val fno = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> { val (v, p) = readVarint(buf, pos); pos = p; out += Field(fno, 0, varint = v) }
                2 -> {
                    val (len, p) = readVarint(buf, pos)
                    pos = p
                    val end = pos + len.toInt()
                    require(len >= 0 && end <= buf.size) { "truncated length-delimited field" }
                    out += Field(fno, 2, bytes = buf.copyOfRange(pos, end))
                    pos = end
                }
                5, 1 -> {
                    val n = if (wt == 5) 4 else 8
                    require(pos + n <= buf.size) { "truncated fixed field" }
                    var v = 0L
                    for (i in 0 until n) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += n
                    out += Field(fno, wt, varint = v)
                }
                else -> throw IllegalArgumentException("unsupported wire type $wt")
            }
        }
        return out
    }

    fun parseOrNull(buf: ByteArray?): List<Field>? =
        if (buf == null) null else try { parse(buf) } catch (_: IllegalArgumentException) { null }

    /** First field [fno] as a number, or null if absent or not numeric. */
    fun int(buf: ByteArray?, fno: Int): Long? =
        parseOrNull(buf)?.firstOrNull { it.number == fno }?.takeIf { it.wireType != 2 }?.varint

    /** First field [fno] as bytes / sub-message, or null. */
    fun bytes(buf: ByteArray?, fno: Int): ByteArray? =
        parseOrNull(buf)?.firstOrNull { it.number == fno }?.bytes

    fun allBytes(buf: ByteArray?, fno: Int): List<ByteArray> =
        parseOrNull(buf)?.filter { it.number == fno && it.wireType == 2 }?.mapNotNull { it.bytes } ?: emptyList()

    /** Follows nested sub-messages: sub(buf, 4, 1) = field 4 → field 1. */
    fun sub(buf: ByteArray?, vararg path: Int): ByteArray? {
        var cur = buf
        for (fno in path) cur = bytes(cur, fno) ?: return null
        return cur
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "negative varint" }
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); return }
        }
    }

    /** encode(1 to 32, 9 to subMessage): Int/Long values become varints, ByteArrays become sub-messages. */
    fun encode(vararg fields: Pair<Int, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((fno, value) in fields) {
            when (value) {
                is Int -> { writeVarint(out, (fno.toLong() shl 3)); writeVarint(out, value.toLong()) }
                is Long -> { writeVarint(out, (fno.toLong() shl 3)); writeVarint(out, value) }
                is ByteArray -> { writeVarint(out, (fno.toLong() shl 3) or 2); writeVarint(out, value.size.toLong()); out.write(value) }
                else -> throw IllegalArgumentException("field $fno: unsupported ${value::class.simpleName}")
            }
        }
        return out.toByteArray()
    }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
