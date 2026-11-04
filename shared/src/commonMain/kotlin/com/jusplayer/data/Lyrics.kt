package com.jusplayer.data

/**
 * A single time-coded lyric line from an LRC file.
 * Time is in seconds from the start of the track.
 */
data class LyricLine(
    val time: Double,
    val text: String,
)

/**
 * Minimal LRC parser. Supports `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]`,
 * multiple timestamps per line and ignores metadata tags like `[ar: ...]`.
 */
fun parseLrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    val lines = mutableListOf<LyricLine>()
    for (line in raw.lines()) {
        val stamps = timestamp.findAll(line).toList()
        if (stamps.isEmpty()) continue
        val text = line.substring(stamps.last().range.last + 1).trim()
        for (stamp in stamps) {
            val minutes = stamp.groupValues[1].toIntOrNull() ?: continue
            val seconds = stamp.groupValues[2].toIntOrNull() ?: continue
            val fractionRaw = stamp.groupValues[3]
            val fraction = when (fractionRaw.length) {
                0 -> 0.0
                1 -> fractionRaw.toDouble() / 10.0
                2 -> fractionRaw.toDouble() / 100.0
                else -> fractionRaw.toDouble() / 1000.0
            }
            lines.add(LyricLine(minutes * 60.0 + seconds + fraction, text))
        }
    }
    return lines.sortedBy { it.time }
}

/** Index of the line that should be highlighted at [position] seconds, or -1. */
fun currentLyricIndex(lines: List<LyricLine>, position: Double): Int {
    if (lines.isEmpty()) return -1
    var lo = 0
    var hi = lines.lastIndex
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lines[mid].time <= position + 0.2) {
            result = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}

/** Shared gateway client for screens that don't receive one via parameters. */
object Api {
    val yt: YtMusicApi by lazy { YtMusicApi() }
}
