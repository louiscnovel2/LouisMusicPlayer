package com.portable.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.UiState
import com.portable.musicplayer.player.EqBands
import com.portable.musicplayer.player.EqPresets
import java.util.Locale
import kotlin.math.roundToInt

/** Equalizer / sound-shaping screen: 10-band EQ, bass boost, normaliser. */
@Composable
fun EqualizerScreen(
    state: UiState,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onNormalize: (Boolean) -> Unit,
    onBand: (Int, Float) -> Unit,
    onBass: (Float) -> Unit,
    onPreamp: (Float) -> Unit,
    onPreset: (String) -> Unit,
    onResetFlat: () -> Unit,
    onSavePreset: (String) -> Unit,
) {
    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // -- header ----------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton("back", size = 26.dp, onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "イコライザー",
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = "プリセット: ${EqPresets.label(state.eqPreset)}" +
                if (state.eqNormalize) "  ・  ノーマライズ オン" else "",
            color = Palette.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // -- presets ---------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((id, _) in EqPresets.GAINS) {
                PresetChip(EqPresets.label(id), state.eqPreset == id) { onPreset(id) }
            }
            for ((name, _) in state.eqCustomPresets) {
                PresetChip(name, state.eqPreset == name) { onPreset(name) }
            }
        }

        // -- band faders -----------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (band in 0 until EqBands.COUNT) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = fmtDb(state.eqGains.getOrElse(band) { 0f }),
                        color = Palette.TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                    VerticalFader(
                        value = state.eqGains.getOrElse(band) { 0f },
                        min = EqBands.GAIN_MIN,
                        max = EqBands.GAIN_MAX,
                        onChange = { onBand(band, it) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Text(
                        text = EqBands.LABELS[band],
                        color = Palette.TextMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        // -- bass / preamp ---------------------------------------------------
        SliderRow("Bass Boost", state.eqBass, 0f, EqBands.BASS_MAX, onBass)
        SliderRow("プリセット音量", state.eqPreamp, EqBands.PREAMP_MIN, EqBands.PREAMP_MAX, onPreamp)

        // -- switches --------------------------------------------------------
        SwitchRow("イコライザーを有効にする", state.eqEnabled, onEnabled)
        SwitchRow("音量を自動調整（ダイナミックノーマライザー）", state.eqNormalize, onNormalize)

        // -- actions ---------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("フラットに戻す", onResetFlat, Modifier.weight(1f))
            ActionButton("カスタム設定を保存", {
                presetName = ""
                showSave = true
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("カスタム設定を保存") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    label = { Text("プリセット名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSavePreset(presetName)
                    showSave = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Color(0x46CBAD77) else Color(0x14FFFFFF))
            .border(
                1.dp,
                if (selected) Palette.HrGold else Color(0x28FFFFFF),
                RoundedCornerShape(3.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Palette.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(78.dp),
            maxLines = 1,
        )
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = fmtDb(value),
            color = Palette.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(46.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Palette.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.TextPrimary, fontSize = 12.sp, maxLines = 1)
    }
}

/** Vertical band fader drawn with a canvas (drag or tap to set the value). */
@Composable
private fun VerticalFader(
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = (max - min).coerceAtLeast(0.001f)
    Box(
        modifier
            .pointerInput(min, max) {
                val pad = 8.dp.toPx()
                detectTapGestures { offset ->
                    val usable = (size.height - 2 * pad).coerceAtLeast(1f)
                    val ratio = 1f - ((offset.y - pad) / usable).coerceIn(0f, 1f)
                    onChange(snap(min + ratio * range))
                }
            }
            .pointerInput(min, max) {
                val pad = 8.dp.toPx()
                detectVerticalDragGestures { change, _ ->
                    val usable = (size.height - 2 * pad).coerceAtLeast(1f)
                    val ratio = 1f - ((change.position.y - pad) / usable).coerceIn(0f, 1f)
                    onChange(snap(min + ratio * range))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 8.dp.toPx()
            val cx = size.width / 2f
            val top = pad
            val bottom = size.height - pad
            val usable = (bottom - top).coerceAtLeast(1f)
            val ratio = ((value - min) / range).coerceIn(0f, 1f)
            val thumbY = bottom - ratio * usable

            drawLine(
                color = Palette.TrackRest,
                start = Offset(cx, top),
                end = Offset(cx, bottom),
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                color = Palette.TrackFill,
                start = Offset(cx, thumbY),
                end = Offset(cx, bottom),
                strokeWidth = 3.dp.toPx(),
            )
            val thumbW = 13.dp.toPx()
            val thumbH = 6.dp.toPx()
            drawRoundRect(
                color = Palette.TrackFill,
                topLeft = Offset(cx - thumbW / 2f, thumbY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** Quantise to 0.5 dB so the readout stays tidy. */
private fun snap(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun fmtDb(value: Float): String =
    String.format(Locale.US, "%+.1f", value)
