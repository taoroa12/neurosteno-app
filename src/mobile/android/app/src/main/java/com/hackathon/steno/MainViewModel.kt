package com.hackathon.steno

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlin.concurrent.thread

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var modelReady by mutableStateOf(false)
        private set
    var modelStatus by mutableStateOf("Загружаю модели…")
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var stage by mutableStateOf("")
        private set
    var fileName by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<TranscriptionResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Число говорящих: -1 значит определять автоматически. */
    var speakers by mutableStateOf(-1)

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    private var engine: SpeechEngine? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            positionMs = p.currentPosition.toLong()
            playing = p.isPlaying
            if (p.isPlaying) handler.postDelayed(this, 150)
        }
    }

    init {
        // Модели грузятся один раз при запуске, чтобы не входить во время обработки записи
        thread {
            try {
                engine = SpeechEngine(app)
                modelStatus = "Модели загружены, работаю офлайн"
                modelReady = true
            } catch (t: Throwable) {
                modelStatus = "Не удалось загрузить модели"
                error = t.message ?: t.toString()
            }
        }
    }

    fun transcribe(uri: Uri) {
        val e = engine ?: return
        if (busy) return
        releasePlayer()
        busy = true
        error = null
        result = null
        progress = 0f
        stage = "Читаю аудио"
        fileName = queryName(uri)

        thread {
            try {
                val start = SystemClock.elapsedRealtime()
                val samples = AudioDecoder.decode(getApplication(), uri)
                val audioSec = samples.size / 16000.0
                require(audioSec > 0.5) { "Запись слишком короткая или не содержит звука" }

                val utterances = e.transcribe(samples, speakers) { p, s ->
                    progress = p
                    stage = s
                }
                val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
                result = TranscriptionResult(
                    utterances = utterances,
                    audioSeconds = audioSec,
                    processingSeconds = elapsed,
                    speakerCount = utterances.map { it.speaker }.distinct().size,
                )
                handler.post { preparePlayer(uri) }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                busy = false
            }
        }
    }

    fun playFrom(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.toInt())
        p.start()
        playing = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    fun baseName(): String =
        (fileName ?: "transcript").substringBeforeLast('.').ifBlank { "transcript" }

    private fun preparePlayer(uri: Uri) {
        try {
            player = MediaPlayer().apply {
                setDataSource(getApplication<Application>(), uri)
                prepare()
                setOnCompletionListener { playing = false }
            }
        } catch (_: Throwable) {
            player = null // воспроизведение необязательно, расшифровка от него не зависит
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        playing = false
        positionMs = 0
    }

    private fun queryName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun onCleared() {
        releasePlayer()
        engine?.release()
        super.onCleared()
    }
}
