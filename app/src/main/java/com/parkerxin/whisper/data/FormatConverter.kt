package com.parkerxin.whisper.data

import com.parkerxin.whisper.whisper.Segment

object FormatConverter {

    fun toTxt(segments: List<Segment>): String {
        return buildString {
            for (seg in segments) {
                append(seg.text)
            }
        }
    }

    fun toSrt(segments: List<Segment>): String {
        return buildString {
            for ((i, seg) in segments.withIndex()) {
                if (seg.text.isBlank()) continue
                appendLine(i + 1)
                appendLine("${fmtSrt(seg.startMs)} --> ${fmtSrt(seg.endMs)}")
                appendLine(seg.text.trim())
                appendLine()
            }
        }
    }

    fun toTimeline(segments: List<Segment>): String {
        return buildString {
            for (seg in segments) {
                if (seg.text.isBlank()) continue
                appendLine("[${fmtTime(seg.startMs)}] ${seg.text.trim()}")
            }
        }
    }

    private fun fmtTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun fmtSrt(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
