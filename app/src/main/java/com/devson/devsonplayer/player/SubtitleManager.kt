package com.devson.devsonplayer.player

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SubtitleManager
 *
 * Parses SRT and ASS/SSA subtitle files and provides timed cues.
 * Designed to be queried each frame by the UI with current position.
 */
class SubtitleManager {

    private val TAG = "SubtitleManager"

    data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private val _currentCue = MutableStateFlow<SubtitleCue?>(null)
    val currentCue: StateFlow<SubtitleCue?> = _currentCue.asStateFlow()

    private var cues: List<SubtitleCue> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────

    /** Load SRT subtitle file content. */
    fun loadSrt(content: String) {
        cues = parseSrt(content)
        Log.i(TAG, "Loaded ${cues.size} SRT cues")
    }

    /** Load ASS/SSA subtitle file content. */
    fun loadAss(content: String) {
        cues = parseAss(content)
        Log.i(TAG, "Loaded ${cues.size} ASS/SSA cues")
    }

    /** Call this with the current playback position to update [currentCue]. */
    fun onPositionChanged(positionMs: Long) {
        val active = cues.firstOrNull { positionMs in it.startMs..it.endMs }
        _currentCue.value = active
    }

    fun clear() {
        cues = emptyList()
        _currentCue.value = null
    }

    // ─── SRT Parser ──────────────────────────────────────────────────────────

    private fun parseSrt(content: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        // SRT blocks separated by blank lines
        val blocks = content.trim().split(Regex("\r?\n\r?\n"))

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 3) continue

            // Line 0: sequence number (ignore)
            // Line 1: timecode  "00:00:01,000 --> 00:00:04,000"
            val timeLine = lines[1]
            val times = parseSrtTimeLine(timeLine) ?: continue

            // Lines 2+: subtitle text (strip SRT HTML-like tags)
            val text = lines.drop(2).joinToString("\n").stripSrtTags()

            result.add(SubtitleCue(times.first, times.second, text))
        }
        return result
    }

    private fun parseSrtTimeLine(line: String): Pair<Long, Long>? {
        // Format: 00:00:01,000 --> 00:00:04,500
        val regex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
        val m = regex.find(line) ?: return null
        val g = m.groupValues
        val start = toMs(g[1], g[2], g[3], g[4])
        val end   = toMs(g[5], g[6], g[7], g[8])
        return Pair(start, end)
    }

    // ─── ASS/SSA Parser ──────────────────────────────────────────────────────

    private fun parseAss(content: String): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        var inEvents = false
        var startIdx = -1
        var endIdx   = -1
        var textIdx  = -1

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            }
            if (inEvents) {
                if (trimmed.startsWith("Format:")) {
                    val fields = trimmed.removePrefix("Format:").split(",").map { it.trim() }
                    startIdx = fields.indexOf("Start")
                    endIdx   = fields.indexOf("End")
                    textIdx  = fields.indexOf("Text")
                    continue
                }
                if (trimmed.startsWith("Dialogue:") && startIdx >= 0) {
                    val parts = trimmed.removePrefix("Dialogue:").split(",", limit = textIdx + 2)
                    if (parts.size > textIdx) {
                        val start = parseAssTime(parts[startIdx].trim()) ?: continue
                        val end   = parseAssTime(parts[endIdx].trim())   ?: continue
                        val text  = parts[textIdx].trim().stripAssTags()
                        result.add(SubtitleCue(start, end, text))
                    }
                }
            }
        }

        return result.sortedBy { it.startMs }
    }

    private fun parseAssTime(t: String): Long? {
        // Format: H:MM:SS.cs (centiseconds)
        val regex = Regex("""(\d):(\d{2}):(\d{2})\.(\d{2})""")
        val m = regex.find(t) ?: return null
        val g = m.groupValues
        val h  = g[1].toLong()
        val mn = g[2].toLong()
        val s  = g[3].toLong()
        val cs = g[4].toLong()   // centiseconds
        return (h * 3600L + mn * 60L + s) * 1000L + cs * 10L
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L +
               s.toLong() * 1_000L + ms.toLong()
    }

    private fun String.stripSrtTags(): String =
        replace(Regex("<[^>]+>"), "")

    private fun String.stripAssTags(): String =
        replace(Regex("\\{[^}]+}"), "")
            .replace(Regex("\\\\N"), "\n")
}
