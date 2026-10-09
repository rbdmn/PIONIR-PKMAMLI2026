package com.smartplug.app.util

/** Sums per-device cumulative series on shared 5-minute buckets, carrying each device forward. */
fun sumDaySeries(perDevice: List<List<Pair<Long, Double>>>): List<Pair<Long, Double>> {
    if (perDevice.isEmpty()) return emptyList()
    val bucket = 300_000L
    val keys = perDevice.flatMap { series -> series.map { it.first / bucket * bucket } }.toSortedSet()
    return keys.map { key ->
        val total = perDevice.sumOf { series ->
            series.lastOrNull { it.first / bucket * bucket <= key }?.second ?: 0.0
        }
        key to total
    }
}
