package com.hackathon.steno

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Весь пайплайн работает на устройстве, без сети:
 *  1. диаризация (pyannote segmentation + эмбеддинги говорящих + кластеризация),
 *  2. объединение близких отрезков одного говорящего в реплики,
 *  3. распознавание каждой реплики моделью GigaM v3 (CTC, со знаками препинания).
 */
class SpeechEngine(context: Context) {

    private class Turn(var speaker: Int, var start: Float, var end: Float)

    private val assets = context.applicationContext.assets
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val diarConfig = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "models/seg/model.onnx"),
            numThreads = threads,
        ),
        embedding = SpeakerEmbeddingExtractorConfig(
            model = "models/emb/model.onnx",
            numThreads = threads,
        ),
        clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
        minDurationOn = 0.2f,
        minDurationOff = 0.5f,
    )

    private val diarizer = OfflineSpeakerDiarization(assetManager = assets, config = diarConfig)

    private val recognizer = OfflineRecognizer(
        assetManager = assets,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "models/asr/model.onnx"),
                tokens = "models/asr/tokens.txt",
                numThreads = threads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /**
     * @param numSpeakers число говорящих, если оно известно; -1 значит определить автоматически.
     * @param progress доля выполненной работы 0..1 и название этапа.
     */
    fun transcribe(
        samples: FloatArray,
        numSpeakers: Int,
        progress: (Float, String) -> Unit,
    ): List<Utterance> {
        normalizeLoudness(samples)

        // 1. Диаризация (без колбэка: лямбда-колбэк роняет нативную библиотеку)
        diarizer.setConfig(
            diarConfig.copy(
                clustering = FastClusteringConfig(numClusters = numSpeakers, threshold = CLUSTER_THRESHOLD)
            )
        )
        progress(0.02f, "Определяю говорящих")
        val segments = diarizer.process(samples).sortedBy { it.start }
        progress(DIARIZATION_SHARE, "Определяю говорящих")

        // 2. Склейка соседних отрезков одного говорящего
        val turns = ArrayList<Turn>()
        for (seg in segments) {
            val last = turns.lastOrNull()
            if (last != null && last.speaker == seg.speaker && seg.start - last.end <= MERGE_GAP_SEC) {
                last.end = max(last.end, seg.end)
            } else {
                turns.add(Turn(seg.speaker, seg.start, seg.end))
            }
        }
        turns.removeAll { it.end - it.start < MIN_TURN_SEC }

        // Номера говорящих по порядку появления: первый заговоривший = speaker_1
        val order = LinkedHashMap<Int, Int>()
        for (t in turns) if (t.speaker !in order) order[t.speaker] = order.size

        // 3. Распознавание
        val totalSec = samples.size.toFloat() / SR
        val result = ArrayList<Utterance>()
        for (t in turns) {
            val from = ((t.start - PAD_SEC) * SR).toInt().coerceAtLeast(0)
            val to = ((t.end + PAD_SEC) * SR).toInt().coerceAtMost(samples.size)
            val text = recognizeRange(samples, from, to)
            if (text.isNotBlank()) {
                result.add(
                    Utterance(
                        speaker = order.getValue(t.speaker),
                        startMs = (t.start * 1000).toLong(),
                        endMs = (t.end * 1000).toLong(),
                        text = text,
                    )
                )
            }
            progress(
                DIARIZATION_SHARE + (1f - DIARIZATION_SHARE) * min(t.end / totalSec, 1f),
                "Распознаю речь",
            )
        }
        progress(1f, "Готово")
        return result
    }

    /** Длинную реплику режем на куски до MAX_CHUNK_SEC, стараясь резать в самом тихом месте. */
    private fun recognizeRange(x: FloatArray, from: Int, to: Int): String {
        val maxChunk = (MAX_CHUNK_SEC * SR).toInt()
        val parts = ArrayList<String>()
        var s = from
        while (to - s > MIN_CHUNK_SAMPLES) {
            val e = if (to - s <= maxChunk) to else quietestPoint(x, s + (maxChunk * 0.6).toInt(), s + maxChunk)
            val text = decode(x.copyOfRange(s, e))
            if (text.isNotBlank()) parts.add(text)
            s = e
        }
        return parts.joinToString(" ")
    }

    private fun decode(chunk: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(chunk, SR)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Ищем окно 50 мс с минимальной энергией внутри [lo, hi]. */
    private fun quietestPoint(x: FloatArray, lo: Int, hi: Int): Int {
        val frame = SR / 20
        var best = hi
        var bestEnergy = Float.MAX_VALUE
        var p = lo
        while (p + frame <= hi) {
            var e = 0f
            for (i in p until p + frame) e += x[i] * x[i]
            if (e < bestEnergy) {
                bestEnergy = e
                best = p + frame / 2
            }
            p += frame
        }
        return best
    }

    /** Тихие записи подтягиваем до нормального уровня, громкие не трогаем. */
    private fun normalizeLoudness(x: FloatArray) {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak in 0.0001f..0.3f) {
            val k = 0.9f / peak
            for (i in x.indices) x[i] *= k
        }
    }

    fun release() {
        recognizer.release()
        diarizer.release()
    }

    companion object {
        private const val SR = 16000
        private const val CLUSTER_THRESHOLD = 0.5f
        private const val DIARIZATION_SHARE = 0.35f
        private const val MERGE_GAP_SEC = 0.8f
        private const val MIN_TURN_SEC = 0.3f
        private const val PAD_SEC = 0.15f
        private const val MAX_CHUNK_SEC = 20f
        private const val MIN_CHUNK_SAMPLES = SR / 5
    }
}