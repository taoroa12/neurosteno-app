package com.hackathon.steno

import org.json.JSONArray
import org.json.JSONObject

/** Одна реплика: кто говорит, когда и что. */
data class Utterance(
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    val speakerId: String get() = "speaker_${speaker + 1}"
}

data class TranscriptionResult(
    val utterances: List<Utterance>,
    val audioSeconds: Double,
    val processingSeconds: Double,
    val speakerCount: Int,
) {
    /** Real-time factor: время обработки / длительность записи. */
    val rtf: Double get() = if (audioSeconds > 0) processingSeconds / audioSeconds else 0.0

    /** JSON строго в формате из ТЗ: {"transcript": [{speaker_id, start_ms, end_ms, text}]}. */
    fun toJson(): String {
        val arr = JSONArray()
        for (u in utterances) {
            arr.put(
                JSONObject()
                    .put("speaker_id", u.speakerId)
                    .put("start_ms", u.startMs)
                    .put("end_ms", u.endMs)
                    .put("text", u.text)
            )
        }
        return JSONObject().put("transcript", arr).toString(2)
    }

    fun toPlainText(): String = buildString {
        for (u in utterances) {
            append('[').append(formatTime(u.startMs)).append(" – ").append(formatTime(u.endMs)).append("] ")
            append("Спикер ").append(u.speaker + 1).append(": ").append(u.text).append('\n')
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
