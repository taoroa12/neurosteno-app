package com.hackathon.steno

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Локальная LLM (Qwen2.5-0.5B-Instruct, int8, формат MediaPipe .task, встроена в APK).
 * Делает краткое резюме диалога уже после показа расшифровки, поэтому на WER и RTF не влияет.
 */
class Summarizer(private val context: Context) {

    private fun modelFile(status: (String) -> Unit): File {
        val dir = File(context.filesDir, "llm").apply { mkdirs() }
        val target = File(dir, "model.task")
        val expected = context.assets.openFd(ASSET).use { it.length }
        if (target.exists() && target.length() == expected) return target

        status("Подготавливаю языковую модель (первый запуск)…")
        val tmp = File(dir, "model.task.tmp")
        context.assets.open(ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }
        tmp.renameTo(target)
        return target
    }

    fun summarize(utterances: List<Utterance>, status: (String) -> Unit): String {
        if (utterances.isEmpty()) return "Нет текста для резюме."
        val path = modelFile(status).absolutePath

        status("Загружаю модель…")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            .build()
        val llm = LlmInference.createFromOptions(context, options)
        try {
            val lines = utterances.map { "Спикер ${it.speaker + 1}: ${it.text}" }
            val chunks = splitChunks(lines)

            if (chunks.size == 1) {
                status("Пишу резюме…")
                return clean(llm.generateResponse(chat(SYSTEM, "Диалог:\n${chunks[0]}\n\n$TASK_SHORT")))
            }

            val parts = ArrayList<String>()
            chunks.forEachIndexed { i, chunk ->
                status("Читаю часть ${i + 1} из ${chunks.size}…")
                parts.add(
                    clean(llm.generateResponse(chat(SYSTEM, "Фрагмент диалога:\n$chunk\n\nПерескажи кратко, 2 предложения.")))
                )
            }
            status("Собираю итоговое резюме…")
            val joined = parts.joinToString("\n") { "- $it" }
            return clean(
                llm.generateResponse(chat(SYSTEM, "Пересказы частей одного разговора:\n$joined\n\n$TASK_FINAL"))
            )
        } finally {
            llm.close() // освобождаем память: модели распознавания остаются загруженными
        }
    }

    /** Режем диалог по репликам на куски, которые помещаются в контекст 1280 токенов. */
    private fun splitChunks(lines: List<String>): List<String> {
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        for (l in lines) {
            val line = if (l.length > CHUNK_CHARS) l.take(CHUNK_CHARS) else l
            if (cur.isNotEmpty() && cur.length + line.length > CHUNK_CHARS) {
                chunks.add(cur.toString())
                cur.clear()
            }
            cur.append(line).append('\n')
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        if (chunks.size <= MAX_CHUNKS) return chunks
        // слишком длинная запись: берём куски равномерно по всей записи
        return List(MAX_CHUNKS) { i -> chunks[i * (chunks.size - 1) / (MAX_CHUNKS - 1)] }
    }

    private fun chat(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private fun clean(s: String): String =
        s.replace("<|im_end|>", "").replace("<|im_start|>", "").trim()

    companion object {
        const val ASSET = "models/llm/qwen2.5-0.5b-q8.task"
        private const val MAX_TOKENS = 1280
        private const val CHUNK_CHARS = 1100
        private const val MAX_CHUNKS = 5
        private const val SYSTEM =
            "Ты помощник, который кратко и точно пересказывает диалоги на русском языке. Не выдумывай фактов, которых нет в тексте."
        private const val TASK_SHORT =
            "Напиши краткое резюме на русском языке: 2-3 предложения о том, что обсуждали и о чём договорились."
        private const val TASK_FINAL =
            "Объедини пересказы в одно краткое резюме на русском языке: 3-4 предложения о том, что обсуждали и о чём договорились."
    }
}