@file:OptIn(ExperimentalMaterial3Api::class)

package com.hackathon.steno

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF1B1F3B)
private val Accent = Color(0xFF3949AB)
private val Page = Color(0xFFF4F5FA)

private val SpeakerColors = listOf(
    Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF00897B),
    Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF8E24AA),
)

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

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Accent,
                    background = Page,
                    surface = Color.White,
                    onSurface = Ink,
                    onBackground = Ink,
                )
            ) {
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

@Composable
private fun Screen(vm: MainViewModel, onPick: () -> Unit, onSaveJson: () -> Unit, onSaveTxt: () -> Unit) {
    val result = vm.result
    val activeIndex = if (result != null && (vm.playing || vm.positionMs > 0))
        result.utterances.indexOfLast { it.startMs <= vm.positionMs } else -1

    Scaffold(containerColor = Page) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text("Нейро-стенографист", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "Расшифровка разговоров с разделением по говорящим. Всё считается на телефоне, без интернета.",
                        fontSize = 13.sp, color = Ink.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!vm.modelReady && vm.error == null) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Box(Modifier.width(10.dp))
                            }
                            Text(vm.modelStatus, fontSize = 14.sp)
                        }

                        Text("Сколько говорящих в записи", fontSize = 12.sp, color = Ink.copy(alpha = 0.6f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(-1 to "Авто", 2 to "2", 3 to "3", 4 to "4", 5 to "5").forEach { (n, label) ->
                                FilterChip(
                                    selected = vm.speakers == n,
                                    onClick = { vm.speakers = n },
                                    label = { Text(label) },
                                    enabled = !vm.busy,
                                )
                            }
                        }

                        Button(
                            onClick = onPick,
                            enabled = vm.modelReady && !vm.busy,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Выбрать аудиофайл") }

                        if (vm.busy) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                                Text(
                                    "${vm.stage} · ${(vm.progress * 100).toInt()}%",
                                    fontSize = 12.sp, color = Ink.copy(alpha = 0.6f),
                                )
                            }
                        }

                        vm.error?.let {
                            Text("Ошибка: $it", color = Color(0xFFB00020), fontSize = 13.sp)
                        }
                    }
                }
            }

            if (result != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(vm.fileName ?: "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Stat("Длительность", formatTime((result.audioSeconds * 1000).toLong()))
                                Stat("Обработка", "%.1f с".format(result.processingSeconds))
                                Stat("RTF", "%.2f".format(result.rtf))
                                Stat("Говорящих", result.speakerCount.toString())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onSaveJson, shape = RoundedCornerShape(10.dp)) { Text("Сохранить JSON") }
                                OutlinedButton(onClick = onSaveTxt, shape = RoundedCornerShape(10.dp)) { Text("Сохранить TXT") }
                            }
                            OutlinedButton(
                                onClick = { vm.togglePlay() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (vm.playing) "Пауза" else "Слушать запись") }
                            Text(
                                "Нажмите на реплику, чтобы услышать этот фрагмент",
                                fontSize = 12.sp, color = Ink.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                itemsIndexed(result.utterances) { index, u ->
                    val color = SpeakerColors[u.speaker % SpeakerColors.size]
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (index == activeIndex) color.copy(alpha = 0.12f) else Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { vm.playFrom(u.startMs) },
                    ) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Спикер ${u.speaker + 1}", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Box(Modifier.width(8.dp))
                                    Text(
                                        "${formatTime(u.startMs)} – ${formatTime(u.endMs)}",
                                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                        color = Ink.copy(alpha = 0.55f),
                                    )
                                }
                                Text(u.text, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = Ink.copy(alpha = 0.55f))
    }
}
