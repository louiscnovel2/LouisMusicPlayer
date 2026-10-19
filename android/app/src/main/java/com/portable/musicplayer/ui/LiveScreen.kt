package com.portable.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.UiState
import com.portable.musicplayer.fmtTime
import com.portable.musicplayer.player.LiveEvent
import com.portable.musicplayer.player.LiveKinds
import com.portable.musicplayer.player.LiveVenues
import kotlin.math.roundToInt

/** Live Effect settings: venue, intensity, auto reactions and manual cheers. */
@Composable
fun LiveScreen(
    state: UiState,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onVenue: (String) -> Unit,
    onIntensity: (Float) -> Unit,
    onCrowd: (Float) -> Unit,
    onWidth: (Float) -> Unit,
    onAutoShow: (Boolean) -> Unit,
    onAddCheer: (String) -> Unit,
    onRemoveCheer: (Int) -> Unit,
    onClearCheers: () -> Unit,
    onSeek: (Long) -> Unit,
) {
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
                text = "ライブ演出",
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = buildString {
                append(if (state.liveEnabled) "オン" else "オフ")
                append("  ・  ")
                append(LiveVenues.label(state.liveVenue))
                if (!state.liveReady) append("  ・  準備中…")
            },
            color = Palette.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        SwitchRow("ライブ演出を有効にする", state.liveEnabled, onEnabled)

        // -- venue grid ------------------------------------------------------
        Text(
            text = "会場",
            color = Palette.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val venues = LiveVenues.ORDER
        for (rowIdx in 0 until 2) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (col in 0 until 3) {
                    val idx = rowIdx * 3 + col
                    val key = venues.getOrNull(idx) ?: return@Row
                    VenueChip(
                        label = LiveVenues.label(key),
                        selected = state.liveVenue == key,
                        onClick = { onVenue(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // -- sliders ---------------------------------------------------------
        PercentSlider("演出の強さ", state.liveIntensity, onIntensity)
        PercentSlider("歓声の音量", state.liveCrowd, onCrowd)
        PercentSlider("立体感", state.liveWidth, onWidth)

        SwitchRow("自動演出（楽曲を解析して歓声を配置）", state.liveAutoShow, onAutoShow)

        if (state.liveStatus.isNotEmpty()) {
            Text(
                text = state.liveStatus,
                color = Palette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // -- manual cheers ---------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "手動の歓声",
                color = Palette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${state.liveManual.size} 件",
                color = Palette.TextSecondary,
                fontSize = 11.sp,
            )
        }

        if (state.liveManual.isEmpty()) {
            Text(
                text = "「現在位置に追加」で好きな位置に歓声を置けます",
                color = Palette.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        } else {
            for ((i, ev) in state.liveManual.withIndex()) {
                ManualRow(ev, i, onSeek, onRemoveCheer)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("＋ 拍手", { onAddCheer("applause") }, Modifier.weight(1f))
            ActionButton("＋ 歓声", { onAddCheer("cheer") }, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("＋ 大歓声", { onAddCheer("big_cheer") }, Modifier.weight(1f))
            ActionButton("全消去", onClearCheers, Modifier.weight(1f))
        }

        Text(
            text = "会場の響きと歓声はアプリ内で合成しています。曲を解析してサビや盛り上がりに自動で喝采を配置します。OFF のときは原音のまま再生します。",
            color = Palette.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VenueChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Color(0x46CBAD77) else Color(0x14FFFFFF))
            .border(
                1.dp,
                if (selected) Palette.HrGold else Color(0x28FFFFFF),
                RoundedCornerShape(3.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PercentSlider(
    label: String,
    value: Float,
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
            modifier = Modifier.width(88.dp),
            maxLines = 1,
        )
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(value * 100).roundToInt()}%",
            color = Palette.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(42.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
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
private fun ManualRow(
    event: LiveEvent,
    index: Int,
    onSeek: (Long) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fmtTime((event.time * 1000.0).toLong()),
            color = Palette.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(56.dp),
            maxLines = 1,
        )
        Text(
            text = LiveKinds.label(event.kind),
            color = Palette.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).clickable { onSeek((event.time * 1000.0).toLong()) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "削除",
            color = Palette.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.clickable { onRemove(index) }.padding(horizontal = 4.dp, vertical = 2.dp),
        )
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
