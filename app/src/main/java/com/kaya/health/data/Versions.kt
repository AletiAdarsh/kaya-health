package com.kaya.health.data

/** "v1.2.10" vs "1.2.9": numeric, part by part. Anything after a "-" (like "-beta") is ignored. */
object Versions {
    fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
