package com.hackathon.steno

import org.json.JSONArray
import org.json.JSONObject

data class Utterance(
    val speaker: Int,      // с нуля; в JSON и интерфейсе превращается в speaker_1, "Спикер 1"
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
    /** RTF = время обработки / длительность записи. */
    val rtf: Double get() = if (audioSeconds > 0) processingSeconds / audioSeconds else 0.0

    /** Формат строго по ТЗ: {"transcript":[{"speaker_id","start_ms","end_ms","text"}]}. */
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

    fun toPlainText(): String = utterances.joinToString("\n") {
        "[${formatTime(it.startMs)} – ${formatTime(it.endMs)}] Спикер ${it.speaker + 1}: ${it.text}"
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}