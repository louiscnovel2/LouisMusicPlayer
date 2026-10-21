package com.portable.musicplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

// -- device shell -------------------------------------------------------------

/**
 * Full-bleed dark "screen". The gold shell was removed so the player UI fills
 * the entire phone display.
 */
@Composable
fun DeviceFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Palette.ScreenBgTop, Palette.ScreenBgBottom))
            )
    ) {
        content()
    }
}

// -- buttons ------------------------------------------------------------------

@Composable
fun GlyphButton(
    icon: String,
    size: Dp = 30.dp,
    active: Boolean = false,
    lineWidth: Float = 1.8f,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = when {
        active || pressed -> Palette.TextPrimary
        else -> Palette.TextSecondary
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = this.size.width * 0.13f
            if (active) {
                drawCircle(Color(0x40FFFFFF), radius = this.size.minDimension / 2f - 1f)
                drawCircle(Color(0x96FFFFFF), radius = this.size.minDimension / 2f - 2.2f, style = Stroke(1.2f))
            } else if (pressed) {
                drawCircle(Color(0x33FFFFFF), radius = this.size.minDimension / 2f - 1f)
            }
            val inset = Offset(pad, pad)
            val inner = Size(this.size.width - pad * 2f, this.size.height - pad * 2f)
            translateGlyph(inset, inner) { drawGlyph(icon, color, lineWidth) }
        }
    }
}

private fun DrawScope.translateGlyph(
    offset: Offset,
    size: Size,
    block: DrawScope.() -> Unit,
) {
    translate(offset.x, offset.y) {
        scale(
            scaleX = size.width / this.size.width,
            scaleY = size.height / this.size.height,
            pivot = Offset.Zero,
        ) {
            block()
        }
    }
}

@Composable
fun TransportButton(
    icon: String,
    diameter: Dp = 52.dp,
    showRing: Boolean = true,
    overlay: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(diameter)
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f - 1.5f
            if (showRing) {
                drawCircle(Color(0xD2FFFFFF), radius = r, style = Stroke(1.6f))
            }
            if (pressed) {
                drawCircle(Color(0x46FFFFFF), radius = r)
            }
            val glyphSize = Size(r * 2f * 0.52f, r * 2f * 0.52f)
            val off = Offset(this.size.width / 2f - glyphSize.width / 2f, this.size.height / 2f - glyphSize.height / 2f)
            translateGlyph(off, glyphSize) { drawGlyph(icon, Color.White, 1.8f) }
        }
        if (overlay != null) {
            Text(overlay, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -- seek bar -----------------------------------------------------------------

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onPreview: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var dragValue by remember { mutableStateOf<Long?>(null) }
    val shown = dragValue ?: positionMs
    val max = maxOf(1L, durationMs)

    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            // `max` must be part of the key: otherwise the tap handler keeps
            // the duration captured on first composition (0 -> 1 ms) and every
            // tap seeks back to the start instead of the tapped position.
            .pointerInput(max, density) {
                detectTapGestures { offset ->
                    val m = with(density) { 8.dp.toPx() }
                    val trackW = (size.width - m * 2).coerceAtLeast(1f)
                    val rel = ((offset.x - m) / trackW).coerceIn(0f, 1f)
                    onSeek((rel * max).toLong())
                }
            }
            .pointerInput(max, density) {
                val m = with(density) { 8.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val trackW = (size.width - m * 2).coerceAtLeast(1f)
                        val rel = ((offset.x - m) / trackW).coerceIn(0f, 1f)
                        dragValue = (rel * max).toLong().also(onPreview)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val trackW = (size.width - m * 2).coerceAtLeast(1f)
                        val rel = ((change.position.x - m) / trackW).coerceIn(0f, 1f)
                        dragValue = (rel * max).toLong().also(onPreview)
                    },
                    onDragEnd = {
                        dragValue?.let(onSeek)
                        dragValue = null
                    },
                    onDragCancel = { dragValue = null },
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val m = 8.dp.toPx()
            val cy = this.size.height / 2f
            val trackH = 1.5.dp.toPx()
            val trackW = this.size.width - m * 2
            val ratio = (shown.toFloat() / max.toFloat()).coerceIn(0f, 1f)

            drawRoundRect(
                color = Palette.TrackRest,
                topLeft = Offset(m, cy - trackH / 2f),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(1f),
            )
            drawRoundRect(
                color = Palette.TrackFill,
                topLeft = Offset(m, cy - trackH / 2f),
                size = Size(trackW * ratio, trackH),
                cornerRadius = CornerRadius(1f),
            )
            val knob = if (dragValue != null) 5.5.dp.toPx() else 4.dp.toPx()
            drawCircle(Palette.Accent, radius = knob, center = Offset(m + trackW * ratio, cy))
        }
    }
}

// -- small bits ---------------------------------------------------------------

@Composable
fun Badge(text: String, color: Color = Palette.HrGold) {
    Box(
        Modifier
            .height(16.dp)
            .border(1.dp, color, RoundedCornerShape(2.5.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ElideText(
    text: String,
    fontSize: Int,
    color: Color = Palette.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = weight,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun CoverArt(track: Track?, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, track?.path) {
        value = if (track == null) null else withContext(Dispatchers.IO) {
            loadArtworkBitmap(track.path)
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1F24), Color(0xFF0C0F12))))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.fillMaxSize(0.34f)) {
                drawGlyph("note", Color(0x2EFFFFFF), 1.8f)
            }
        }
    }
}

private fun loadArtworkBitmap(path: String): ImageBitmap? {
    return try {
        val mmr = android.media.MediaMetadataRetriever()
        val bytes = try {
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        } ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

// -- status bar ---------------------------------------------------------------

@Composable
fun StatusBarRow(
    playing: Boolean,
    index: Int,
    total: Int,
    loopMode: String,
    shuffle: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
    ) {
        // left: play / pause glyph
        Canvas(
            Modifier
                .align(Alignment.CenterStart)
                .size(18.dp)
        ) {
            drawGlyph(if (playing) "play" else "pause", Palette.TextSecondary, 1.6f)
        }
        // centre: track counter
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (total > 0) String.format(java.util.Locale.US, "%03d", index) else "---",
                color = Palette.TextSecondary,
                fontSize = 10.sp,
            )
            if (total > 0) {
                Text(
                    text = String.format(java.util.Locale.US, "/%03d", total),
                    color = Palette.TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
        // right: shuffle + loop + battery
        Row(
            Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (shuffle) {
                Canvas(Modifier.size(16.dp)) { drawGlyph("shuffle", Palette.Accent, 1.4f) }
            }
            Canvas(Modifier.size(16.dp)) {
                drawGlyph(
                    if (loopMode == "one") "repeat_one" else "repeat",
                    if (loopMode == "off") Palette.TextMuted else Palette.Accent,
                    1.4f,
                )
            }
        }
    }
}
