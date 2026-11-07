package com.hackathon.steno

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Декодирует любой аудиофайл, который понимает Android (wav, mp3, m4a, ogg, flac, aac,
 * а также аудиодорожку из видео), в моно float PCM с частотой 16 кГц.
 * Ресемплинг идёт потоком, поэтому длинная запись не раздувает память.
 */
object AudioDecoder {
    const val TARGET_RATE = 16000

    private class FloatSink {
        var data = FloatArray(1 shl 20)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** Линейная интерполяция между соседними отсчётами, состояние хранится между блоками. */
    private class Resampler(srcRate: Int) {
        private val step = srcRate.toDouble() / TARGET_RATE
        private var nextPos = 0.0
        private var consumed = 0L
        private var prev = 0f

        fun feed(chunk: FloatArray, n: Int, out: FloatSink) {
            if (n == 0) return
            while (true) {
                val i0 = floor(nextPos).toLong()
                if (i0 + 1 >= consumed + n) break
                val frac = (nextPos - i0).toFloat()
                val a = if (i0 < consumed) prev else chunk[(i0 - consumed).toInt()]
                val b = chunk[(i0 + 1 - consumed).toInt()]
                out.add(a + (b - a) * frac)
                nextPos += step
            }
            prev = chunk[n - 1]
            consumed += n
        }
    }

    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "В файле нет аудиодорожки" }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var isFloat = false
            var resampler: Resampler? = null
            val sink = FloatSink()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.LITTLE_ENDIAN)

                            if (info.size > 0) {
                                if (resampler == null && rate != TARGET_RATE) resampler = Resampler(rate)
                                val bytesPerSample = if (isFloat) 4 else 2
                                val frames = info.size / bytesPerSample / channels
                                val mono = FloatArray(frames)
                                if (isFloat) {
                                    val fb = ob.asFloatBuffer()
                                    for (i in 0 until frames) {
                                        var acc = 0f
                                        for (c in 0 until channels) acc += fb.get()
                                        mono[i] = acc / channels
                                    }
                                } else {
                                    val sb = ob.asShortBuffer()
                                    for (i in 0 until frames) {
                                        var acc = 0
                                        for (c in 0 until channels) acc += sb.get()
                                        mono[i] = acc / (32768f * channels)
                                    }
                                }
                                if (resampler == null) {
                                    for (i in 0 until frames) sink.add(mono[i])
                                } else {
                                    resampler.feed(mono, frames, sink)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return sink.toArray()
        } finally {
            extractor.release()
        }
    }
}
