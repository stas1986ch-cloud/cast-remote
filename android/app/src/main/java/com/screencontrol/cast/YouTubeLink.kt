/**
 * @file YouTubeLink.kt
 * @summary Parses YouTube URLs and raw 11-character video IDs into a normalized video ID and start offset in seconds.
 * @description Supports watch?v=, youtu.be/, /shorts/, /embed/, /live/, bare IDs, and t= timestamps (e.g. 90s, 1h2m3s).
 * @testing Validated against standard YouTube URL patterns and boundary timestamp inputs.
 */
package com.screencontrol.cast

data class ParsedYouTubeLink(val videoId: String?, val startSeconds: Int)

object YouTubeLink {
    private val patterns = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})") to 1,
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})") to 1,
        Regex("/(shorts|embed|live)/([A-Za-z0-9_-]{11})") to 2,
        Regex("^([A-Za-z0-9_-]{11})$") to 1
    )
    private val timeParam = Regex("[?&]t=([^&]+)")
    private val hoursRe = Regex("(\\d+)h")
    private val minsRe = Regex("(\\d+)m")
    private val secsRe = Regex("(\\d+)s")

    fun parse(raw: String): ParsedYouTubeLink {
        val text = raw.trim()
        var videoId: String? = null
        for ((re, group) in patterns) {
            val match = re.find(text)
            if (match != null && match.groupValues.size > group) {
                videoId = match.groupValues[group]
                break
            }
        }
        val start = timeParam.find(text)?.groupValues?.getOrNull(1)?.let { parseStart(it) } ?: 0
        return ParsedYouTubeLink(videoId, start)
    }

    fun parseStart(raw: String): Int {
        val t = raw.trim()
        if (t.endsWith("s")) {
            t.dropLast(1).toIntOrNull()?.let { return maxOf(0, it) }
        }
        t.toIntOrNull()?.let { return maxOf(0, it) }
        var total = 0
        hoursRe.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { total += it * 3600 }
        minsRe.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { total += it * 60 }
        secsRe.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { total += it }
        return maxOf(0, total)
    }
}
