package org.futo.voiceinput.shared.engine

import java.io.File

/**
 * The phone's fast cores, for pinning model threads. On a phone with fast and slow cores, threads
 * spread over all of them wait for the slow ones at every step: Nemotron took 4.5 s for 10.6 s of
 * audio on all 8 cores of a Dimensity (2 fast + 6 slow), 3.7 s on just the 2 fast ones.
 */
object FastCores {
    /**
     * Each core's speed (EAS capacity, else max frequency) by core id, or null if unavailable.
     * Only the cores the app may run on count: pinning to others fails.
     */
    private fun scores(): Map<Int, Long>? {
        val allowed = try {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.let { parseCoreList(it.substringAfter(':')) }
        } catch (e: Exception) {
            null
        }
        val ids = allowed ?: (0 until Runtime.getRuntime().availableProcessors()).toList()
        fun read(path: String): Long? = try {
            File(path).readText().trim().toLong().takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
        for (file in listOf("cpu_capacity", "cpufreq/cpuinfo_max_freq")) {
            val score = ids.associateWith { read("/sys/devices/system/cpu/cpu$it/$file") }
            if (score.values.all { it != null }) return score.mapValues { it.value!! }
        }
        return null
    }

    /**
     * Core ids from a kernel list such as "0-3,6-7" (the system can limit an app to some cores,
     * and ids needn't run from 0 to the core count), or null if it doesn't parse.
     */
    fun parseCoreList(list: String): List<Int>? = try {
        list.split(',').flatMap { range ->
            val ends = range.trim().split('-').map { it.trim().toInt() }
            if (ends.size > 2 || ends.first() > ends.last()) return null
            (ends.first()..ends.last()).toList()
        }.distinct().sorted().takeIf { it.isNotEmpty() }
    } catch (e: NumberFormatException) {
        null
    }

    /**
     * Ids of the [n] fastest cores, or null if the cores are all alike, [n] covers them all, or
     * the information is unavailable.
     */
    fun fastest(n: Int): IntArray? = scores()?.let { fastest(it, n) }

    fun fastest(score: Map<Int, Long>, n: Int): IntArray? {
        if (n <= 0 || score.values.distinct().size < 2 || n >= score.size) return null
        return score.keys.sortedWith(compareByDescending<Int> { score.getValue(it) }.thenByDescending { it })
            .take(n).sorted().toIntArray()
    }

    /**
     * Ids of the fast cores (within [TIER] of the fastest, at most [max]), or null if the cores
     * are all alike or the information is unavailable.
     */
    fun fastTier(max: Int = 4): IntArray? = scores()?.let { fastTier(it, max) }

    fun fastTier(score: Map<Int, Long>, max: Int = 4): IntArray? {
        if (score.values.distinct().size < 2) return null
        val top = score.values.max()
        val fast = score.keys.filter { score.getValue(it) >= top * TIER }
        if (fast.size >= score.size) return null
        return fast.sortedWith(compareByDescending<Int> { score.getValue(it) }.thenBy { it })
            .take(max).sorted().toIntArray()
    }

    /** A core this close to the fastest counts as fast (prime + big cores; a 2.0 GHz little core next to a 2.6 GHz big one doesn't). */
    private const val TIER = 0.8
}
