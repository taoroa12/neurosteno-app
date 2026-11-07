package com.hackathon.steno

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────── Тема ─────────────────────────

private class StenoPalette(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val disabled: Color,
    val speakers: List<Color>,
)

private val LightPalette = StenoPalette(
    bg = Color(0xFFF6F5F2),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE3E1DA),
    text = Color(0xFF1A1A1A),
    textDim = Color(0xFF6A6A6A),
    accent = Color(0xFF2B50C8),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    disabled = Color(0xFFE9E7E1),
    speakers = listOf(
        Color(0xFF3B6FD4), Color(0xFFC65D3B), Color(0xFF3E8E6A),
        Color(0xFFB7862B), Color(0xFF8A5BB5), Color(0xFF2A8C96),
    ),
)

private val DarkPalette = StenoPalette(
    bg = Color(0xFF111214),
    surface = Color(0xFF1B1C1F),
    border = Color(0xFF2A2B2F),
    text = Color(0xFFECECEC),
    textDim = Color(0xFF9A9A9A),
    accent = Color(0xFF7C9AF5),
    onAccent = Color(0xFF0B1020),
    error = Color(0xFFEF8A80),
    disabled = Color(0xFF25262A),
    speakers = listOf(
        Color(0xFF7BA3F0), Color(0xFFE2866A), Color(0xFF6CC09A),
        Color(0xFFD9A94F), Color(0xFFB58BDB), Color(0xFF5FBBC5),
    ),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
private fun StenoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, error = p.error,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent,
            background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, error = p.error,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private val ShapeS = RoundedCornerShape(8.dp)
private val ShapeM = RoundedCornerShape(10.dp)
private val ShapeL = RoundedCornerShape(12.dp)

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.transcribe(uri)
            }
            val saveJson = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri -> write(uri, vm.result?.toJson()) }
            val saveTxt = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain")
            ) { uri -> write(uri, vm.result?.toPlainText()) }

            StenoTheme {
                Screen(
                    vm = vm,
                    onPick = { pick.launch(arrayOf("audio/*")) },
                    onSaveJson = { saveJson.launch(vm.baseName() + ".json") },
                    onSaveTxt = { saveTxt.launch(vm.baseName() + ".txt") },
                )
            }
        }
    }

    private fun write(uri: Uri?, content: String?) {
        if (uri == null || content == null) return
        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }
}

// ───────────────────────── Экран ─────────────────────────

@Composable
private fun Screen(vm: MainViewModel, onPick: () -> Unit, onSaveJson: () -> Unit, onSaveTxt: () -> Unit) {
    val p = LocalPalette.current
    val result = vm.result
    val activeIndex = if (result != null && (vm.playing || vm.positionMs > 0))
        result.utterances.indexOfLast { it.startMs <= vm.positionMs } else -1

    Scaffold(containerColor = p.bg, contentColor = p.text) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header(vm) }

            item { Controls(vm, onPick, hasResult = result != null) }

            vm.error?.let { err ->
                item { Text("Ошибка: $err", color = p.error, fontSize = 14.sp, lineHeight = 20.sp) }
            }

            if (result == null) {
                if (!vm.busy) {
                    item { EmptyState() }
                }
            } else {
                item { ResultCard(vm, onSaveJson, onSaveTxt) }
                item { SummaryCard(vm) }
                item {
                    Text(
                        "Расшифровка · ${result.utterances.size}",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.textDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                itemsIndexed(result.utterances) { index, u ->
                    UtteranceRow(
                        speaker = u.speaker,
                        time = "${formatTime(u.startMs)} – ${formatTime(u.endMs)}",
                        text = u.text,
                        active = index == activeIndex,
                        onClick = { vm.playFrom(u.startMs) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(vm: MainViewModel) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Нейро-стенографист",
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = p.text,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!vm.modelReady && vm.error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(vm.modelStatus, fontSize = 13.sp, lineHeight = 18.sp, color = p.textDim)
        }
    }
}

@Composable
private fun Controls(vm: MainViewModel, onPick: () -> Unit, hasResult: Boolean) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Говорящих в записи", fontSize = 13.sp, color = p.textDim)
            SegmentedSpeakers(
                options = listOf(-1 to "Авто", 2 to "2", 3 to "3", 4 to "4", 5 to "5"),
                selected = vm.speakers,
                enabled = !vm.busy,
                onSelect = { vm.speakers = it },
            )
        }

        StenoButton(
            text = if (hasResult) "Выбрать другой файл" else "Выбрать аудиофайл",
            primary = !hasResult,
            enabled = vm.modelReady && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onPick,
        )

        if (vm.busy) {
            val animated by animateFloatAsState(vm.progress.coerceIn(0f, 1f), label = "progress")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .clip(CircleShape).background(p.border)
                ) {
                    Box(
                        Modifier.fillMaxWidth(animated).height(4.dp)
                            .clip(CircleShape).background(p.accent)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        vm.stage, fontSize = 13.sp, color = p.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(vm.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = p.textDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Записи пока нет",
            fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = p.text,
        )
        Text(
            "Выберите аудиозапись разговора — приложение расшифрует её и разделит реплики по говорящим. " +
                    "Всё работает без интернета, данные остаются на устройстве.",
            fontSize = 14.sp, lineHeight = 21.sp, color = p.textDim,
        )
    }
}

@Composable
private fun ResultCard(vm: MainViewModel, onSaveJson: () -> Unit, onSaveTxt: () -> Unit) {
    val p = LocalPalette.current
    val result = vm.result ?: return
    Group {
        val name = vm.fileName
        if (!name.isNullOrBlank()) {
            Text(
                name,
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = p.text,
                maxLines = 2,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Metric("Длительность", formatTime((result.audioSeconds * 1000).toLong()), Modifier.weight(1f))
            Metric("Говорящих", result.speakerCount.toString(), Modifier.weight(1f))
            Metric("Обработка", "%.1f с".format(result.processingSeconds), Modifier.weight(1f))
            Metric("RTF", "%.2f".format(result.rtf), Modifier.weight(1f))
        }
        Hairline()
        StenoButton(
            text = if (vm.playing) "Пауза" else "Слушать",
            primary = false,
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.togglePlay() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StenoButton("Сохранить JSON", false, true, Modifier.weight(1f), onSaveJson)
            StenoButton("Сохранить TXT", false, true, Modifier.weight(1f), onSaveTxt)
        }
    }
}

@Composable
private fun SummaryCard(vm: MainViewModel) {
    val p = LocalPalette.current
    Group {
        Text(
            "Резюме · локальная LLM",
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = p.text,
        )
        if (vm.summaryBusy) {
            Row(
                Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = p.accent,
                )
                Spacer(Modifier.width(10.dp))
                Text(vm.summaryStatus, fontSize = 14.sp, lineHeight = 20.sp, color = p.textDim)
            }
        } else {
            StenoButton(
                text = if (vm.summaryText == null) "Составить резюме" else "Составить заново",
                primary = false,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (!vm.summaryBusy) vm.summarize() },
            )
        }
        vm.summaryText?.let {
            Text(it, fontSize = 15.sp, lineHeight = 22.sp, color = p.text)
        }
    }
}

@Composable
private fun UtteranceRow(
    speaker: Int,
    time: String,
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val n = p.speakers.size
    val color = p.speakers[((speaker % n) + n) % n]
    val bg by animateColorAsState(
        if (active) color.copy(alpha = 0.12f) else Color.Transparent,
        label = "utteranceBg",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ShapeS)
                .background(bg)
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Спикер ${speaker + 1}",
                        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = color,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        time,
                        fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                        color = p.textDim,
                    )
                }
                Text(text, fontSize = 16.sp, lineHeight = 24.sp, color = p.text)
            }
        }
        Box(Modifier.padding(horizontal = 8.dp)) { Hairline() }
    }
}

// ───────────────────────── Компоненты ─────────────────────────

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeL)
            .background(p.surface)
            .border(1.dp, p.border, ShapeL)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border))
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace, color = p.text, maxLines = 1,
        )
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = p.textDim, maxLines = 1)
    }
}

@Composable
private fun StenoButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val bg = when {
        !enabled -> p.disabled
        primary -> p.accent
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> p.textDim.copy(alpha = 0.7f)
        primary -> p.onAccent
        else -> p.text
    }
    val borderColor = if (primary && enabled) p.accent else p.border
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(ShapeM)
            .background(bg)
            .border(1.dp, borderColor, ShapeM)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SegmentedSpeakers(
    options: List<Pair<Int, String>>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ShapeM)
            .background(p.surface)
            .border(1.dp, p.border, ShapeM)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSel = selected == value
            val bg = when {
                isSel && enabled -> p.accent
                isSel -> p.disabled
                else -> Color.Transparent
            }
            val fg = when {
                isSel && enabled -> p.onAccent
                !enabled -> p.textDim.copy(alpha = 0.7f)
                else -> p.text
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = fg, fontSize = 14.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}