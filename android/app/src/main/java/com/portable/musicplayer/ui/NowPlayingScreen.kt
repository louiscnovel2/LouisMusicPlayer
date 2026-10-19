package com.portable.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.UiState
import com.portable.musicplayer.fmtTime
import com.portable.musicplayer.player.LiveVenues

@Composable
fun NowPlayingScreen(
    state: UiState,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCycleLoop: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLive: () -> Unit,
    onAddCheer: () -> Unit,
) {
    val track = state.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp)
    ) {
        val side = minOf(
            maxWidth - 8.dp,
            maxHeight * 0.44f,
        ).coerceAtLeast(120.dp)

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBarRow(
                playing = state.playing,
                index = state.index,
                total = state.total,
                loopMode = state.loopMode,
                shuffle = state.shuffle,
            )

            Spacer(Modifier.height(2.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CoverArt(track, Modifier.size(side))
            }

            // time row
            Row(Modifier.fillMaxWidth()) {
                ElideText(fmtTime(state.positionMs), 11, Palette.TextSecondary,
                    align = TextAlign.Start, modifier = Modifier.weight(1f))
                ElideText(
                    if (state.total > 0)
                        String.format(java.util.Locale.US, "%03d/%03d", state.index, state.total)
                    else "---",
                    11, Palette.TextSecondary, align = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                ElideText(fmtTime(state.durationMs), 11, Palette.TextSecondary,
                    align = TextAlign.End, modifier = Modifier.weight(1f))
            }

            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onPreview = { onSeek(it) },
                onSeek = { onSeek(it) },
            )

            Spacer(Modifier.height(2.dp))

            ElideText(
                track?.displayTitle ?: "Louis Music Player",
                20, Palette.TextPrimary, FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(),
            )
            ElideText(
                track?.displayArtist ?: "曲を選んでください",
                14, Palette.TextSecondary,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(18.dp)) { drawGlyph("list", Palette.TextSecondary, 1.6f) }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = track?.displayAlbum ?: "Portable Digital Audio",
                    color = Palette.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (track != null && track.isHires) {
                    Spacer(Modifier.width(8.dp))
                    Badge("HR")
                }
            }

            Spacer(Modifier.height(6.dp))

            // live effect + cheer
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LivePill(
                    label = if (state.liveEnabled) "LIVE · ${LiveVenues.label(state.liveVenue)}" else "LIVE",
                    active = state.liveEnabled,
                    onClick = onToggleLive,
                )
                LivePill(label = "＋ 歓声", active = false, onClick = onAddCheer)
            }

            Spacer(Modifier.weight(1f))

            // transport row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton("shuffle", size = 30.dp, active = state.shuffle, onClick = onToggleShuffle)
                TransportButton("prev", diameter = 46.dp, onClick = onPrev)
                TransportButton(
                    if (state.playing) "pause" else "play",
                    diameter = 58.dp,
                    onClick = onToggle,
                )
                TransportButton("next", diameter = 46.dp, onClick = onNext)
                Box(contentAlignment = Alignment.Center) {
                    GlyphButton(
                        if (state.loopMode == "one") "repeat_one" else "repeat",
                        size = 30.dp,
                        active = state.loopMode != "off",
                        onClick = onCycleLoop,
                    )
                    if (state.loopMode == "one") {
                        Text("1", color = if (state.loopMode != "off") Palette.TextPrimary else Palette.TextSecondary,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun LivePill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) Color(0x46CBAD77) else Color(0x14FFFFFF))
            .border(
                1.dp,
                if (active) Palette.HrGold else Color(0x28FFFFFF),
                RoundedCornerShape(3.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Palette.Accent else Palette.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
