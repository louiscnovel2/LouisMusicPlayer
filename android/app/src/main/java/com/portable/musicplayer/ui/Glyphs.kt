package com.portable.musicplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector glyphs ported from the desktop `ui/icons.py` so the Android build
 * shares the exact same iconography (no bitmap assets).
 */
fun DrawScope.drawGlyph(name: String, color: Color, lw: Float = 1.8f) {
    when (name) {
        "play" -> gPlay(color)
        "pause" -> gPause(color)
        "prev" -> gPrev(color)
        "next" -> gNext(color)
        "repeat" -> gRepeat(color, lw)
        "repeat_one" -> gRepeat(color, lw)
        "shuffle" -> gShuffle(color, lw)
        "back" -> gBack(color, lw)
        "list" -> gList(color, lw)
        "folder" -> gFolder(color, lw)
        "eq" -> gEq(color, lw)
        "toolbox" -> gToolbox(color, lw)
        "gear" -> gGear(color, lw)
        "volume" -> gVolume(color, lw)
        "note" -> gNote(color, lw)
    }
}

private fun DrawScope.stroke(lw: Float, cap: StrokeCap = StrokeCap.Round) =
    Stroke(width = lw, cap = cap, join = StrokeJoin.Round)

private fun DrawScope.gPlay(c: Color) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val w = s * 0.34f; val h = s * 0.42f
    val p = Path().apply {
        moveTo(cx - w * 0.55f, cy - h)
        lineTo(cx - w * 0.55f, cy + h)
        lineTo(cx + w * 0.95f, cy)
        close()
    }
    drawPath(p, c)
}

private fun DrawScope.gPause(c: Color) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val bw = s * 0.11f; val bh = s * 0.44f; val gap = s * 0.10f
    drawRoundRect(c, Offset(cx - gap - bw, cy - bh), Size(bw, bh * 2f),
        androidx.compose.ui.geometry.CornerRadius(bw * 0.35f))
    drawRoundRect(c, Offset(cx + gap, cy - bh), Size(bw, bh * 2f),
        androidx.compose.ui.geometry.CornerRadius(bw * 0.35f))
}

private fun DrawScope.gPrev(c: Color) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val w = s * 0.30f; val h = s * 0.32f; val bar = s * 0.07f
    val p = Path().apply {
        moveTo(cx + w, cy - h); lineTo(cx + w, cy + h); lineTo(cx - w * 0.5f, cy); close()
    }
    drawPath(p, c)
    drawRect(c, Offset(cx - w * 0.5f - bar, cy - h), Size(bar, h * 2f))
}

private fun DrawScope.gNext(c: Color) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val w = s * 0.30f; val h = s * 0.32f; val bar = s * 0.07f
    val p = Path().apply {
        moveTo(cx - w, cy - h); lineTo(cx - w, cy + h); lineTo(cx + w * 0.5f, cy); close()
    }
    drawPath(p, c)
    drawRect(c, Offset(cx + w * 0.5f, cy - h), Size(bar, h * 2f))
}

private fun DrawScope.gRepeat(c: Color, lw: Float) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val d = s * 0.86f
    drawArc(
        color = c,
        startAngle = -60f,
        sweepAngle = -300f,
        useCenter = false,
        topLeft = Offset(cx - d / 2f, cy - d / 2f),
        size = Size(d, d),
        style = stroke(lw),
    )
    // arrow head at the top-right end of the gap
    val ang = Math.toRadians(-60.0)
    val ex = (cx + d / 2f * cos(ang)).toFloat()
    val ey = (cy + d / 2f * sin(ang)).toFloat()
    val head = s * 0.22f
    val tri = Path().apply {
        moveTo(ex + head * 0.1f, ey - head * 0.55f)
        lineTo(ex + head * 0.55f, ey + head * 0.35f)
        lineTo(ex - head * 0.45f, ey + head * 0.1f)
        close()
    }
    drawPath(tri, c)
}

private fun DrawScope.gShuffle(c: Color, lw: Float) {
    val w = size.width * 0.5f; val h = size.height * 0.32f
    val cx = size.width / 2f; val cy = size.height / 2f
    drawLine(c, Offset(cx - w, cy - h), Offset(cx + w, cy + h), strokeWidth = lw, cap = StrokeCap.Round)
    drawLine(c, Offset(cx - w, cy + h), Offset(cx + w, cy - h), strokeWidth = lw, cap = StrokeCap.Round)
    val head = w * 0.4f
    drawPath(Path().apply {
        moveTo(cx + w, cy + h)
        lineTo(cx + w - head, cy + h - head)
        lineTo(cx + w - head * 0.2f, cy + h + head * 0.4f)
        close()
    }, c)
    drawPath(Path().apply {
        moveTo(cx + w, cy - h)
        lineTo(cx + w - head, cy - h + head)
        lineTo(cx + w - head * 0.2f, cy - h - head * 0.4f)
        close()
    }, c)
}

private fun DrawScope.gBack(c: Color, lw: Float) {
    val w = size.width * 0.30f; val h = size.height * 0.26f
    val cx = size.width / 2f; val cy = size.height / 2f
    val p = Path().apply {
        moveTo(cx + w, cy - h * 0.9f)
        lineTo(cx + w, cy + h * 0.3f)
        quadraticBezierTo(cx + w, cy + h, cx + w * 0.2f, cy + h)
        lineTo(cx - w * 0.2f, cy + h)
    }
    drawPath(p, c, style = stroke(lw))
    drawPath(Path().apply {
        moveTo(cx - w, cy + h)
        lineTo(cx - w * 0.15f, cy + h - h * 0.6f)
        lineTo(cx - w * 0.15f, cy + h + h * 0.6f)
        close()
    }, c)
}

private fun DrawScope.gList(c: Color, lw: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val w = size.width * 0.34f
    val gap = size.height * 0.24f
    for (dy in listOf(-gap, 0f, gap)) {
        drawLine(c, Offset(cx - w, cy + dy), Offset(cx + w, cy + dy), strokeWidth = lw, cap = StrokeCap.Round)
    }
}

private fun DrawScope.gFolder(c: Color, lw: Float) {
    val w = size.width * 0.70f; val hh = size.height * 0.52f
    val cx = size.width / 2f; val cy = size.height / 2f
    val left = cx - w / 2f
    val top = cy - hh / 2f + hh * 0.10f
    val right = left + w
    val bottom = top + hh
    val tabw = w * 0.44f
    val tabh = hh * 0.24f
    val p = Path().apply {
        moveTo(left, top + tabh)
        lineTo(left, top)
        lineTo(left + tabw, top)
        lineTo(left + tabw + tabh * 0.9f, top + tabh)
        lineTo(right, top + tabh)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }
    drawPath(p, c, style = stroke(lw))
}

private fun DrawScope.gEq(c: Color, lw: Float) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val step = size.width * 0.20f
    val hs = listOf(0.30f, 0.45f, 0.22f, 0.40f, 0.28f)
    hs.forEachIndexed { i, f ->
        val x = cx + (i - 2) * step
        drawLine(c, Offset(x, cy - size.height * f), Offset(x, cy + size.height * f),
            strokeWidth = lw, cap = StrokeCap.Round)
    }
}

private fun DrawScope.gToolbox(c: Color, lw: Float) {
    val w = size.width * 0.68f; val hh = size.height * 0.52f
    val cx = size.width / 2f; val cy = size.height / 2f
    val left = cx - w / 2f
    val top = cy - hh / 2f + hh * 0.10f
    val right = left + w
    val bottom = top + hh
    val boxCenterY = (top + bottom) / 2f
    drawRoundRect(
        color = c,
        topLeft = Offset(left, top),
        size = Size(w, hh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(hh * 0.12f),
        style = stroke(lw),
    )
    drawLine(c, Offset(left, boxCenterY), Offset(right, boxCenterY), strokeWidth = lw)
    val hw = w * 0.44f
    val handleTop = top - hh * 0.42f / 2f
    drawRoundRect(
        color = c,
        topLeft = Offset(cx - hw / 2f, handleTop),
        size = Size(hw, hh * 0.42f * 0.7f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(hh * 0.1f),
        style = stroke(lw),
    )
    drawRect(c, Offset(cx - w * 0.05f, boxCenterY - hh * 0.14f), Size(w * 0.10f, hh * 0.28f))
}

private fun DrawScope.gGear(c: Color, lw: Float) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val d = s * 0.62f
    drawCircle(c, radius = d / 2f, center = Offset(cx, cy), style = stroke(lw))
    drawCircle(c, radius = d / 2f - d * 0.32f, center = Offset(cx, cy), style = stroke(lw))
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val x1 = (cx + cos(a) * d / 2f).toFloat()
        val y1 = (cy + sin(a) * d / 2f).toFloat()
        val x2 = (cx + cos(a) * d * 0.72f).toFloat()
        val y2 = (cy + sin(a) * d * 0.72f).toFloat()
        drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = lw, cap = StrokeCap.Butt)
    }
}

private fun DrawScope.gVolume(c: Color, lw: Float) {
    val w = size.width * 0.28f; val hh = size.height * 0.24f
    val cx = size.width / 2f; val cy = size.height / 2f
    drawRect(c, Offset(cx - w * 0.9f, cy - hh * 0.5f), Size(w * 0.9f, hh), style = stroke(lw))
    drawPath(Path().apply {
        moveTo(cx - w * 0.05f, cy - hh * 0.5f)
        lineTo(cx + w * 0.35f, cy - hh)
        lineTo(cx + w * 0.35f, cy + hh)
        lineTo(cx - w * 0.05f, cy + hh * 0.5f)
        close()
    }, c)
    drawArc(c, -60f, 120f, false, Offset(cx + w * 0.2f, cy - hh * 1.1f), Size(w * 0.7f, hh * 2.2f), style = stroke(lw))
    drawArc(c, -55f, 110f, false, Offset(cx + w * 0.2f, cy - hh * 1.6f), Size(w * 1.3f, hh * 3.2f), style = stroke(lw))
}

private fun DrawScope.gNote(c: Color, lw: Float) {
    val s = min(size.width, size.height)
    val cx = size.width / 2f; val cy = size.height / 2f
    val head = s * 0.15f
    for (dx in listOf(-s * 0.16f, s * 0.16f)) {
        drawOval(c, Offset(cx + dx - head, cy + s * 0.14f), Size(head * 2f, head * 1.5f))
    }
    val sw = maxOf(1.4f, s * 0.05f)
    drawLine(c, Offset(cx - s * 0.16f + head, cy + s * 0.20f), Offset(cx - s * 0.16f + head, cy - s * 0.26f),
        strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(c, Offset(cx + s * 0.16f + head, cy + s * 0.20f), Offset(cx + s * 0.16f + head, cy - s * 0.26f),
        strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(c, Offset(cx - s * 0.16f + head, cy - s * 0.26f), Offset(cx + s * 0.16f + head, cy - s * 0.26f),
        strokeWidth = sw, cap = StrokeCap.Round)
}

/** Battery gauge used by the status bar. */
fun DrawScope.drawBattery(color: Color, level: Float = 1f) {
    val w = size.width * 0.92f; val hh = size.height * 0.44f
    val cx = size.width / 2f; val cy = size.height / 2f
    val left = cx - w / 2f; val top = cy - hh / 2f
    drawRoundRect(color, Offset(left, top), Size(w, hh),
        androidx.compose.ui.geometry.CornerRadius(hh * 0.22f), style = stroke(1.2f))
    drawRoundRect(color, Offset(left + w + 1f, cy - hh * 0.16f), Size(hh * 0.22f, hh * 0.32f),
        androidx.compose.ui.geometry.CornerRadius(1f))
    val inset = 2.2f
    val innerLeft = left + inset
    val innerTop = top + inset
    val innerW = w - inset * 2f
    val innerH = hh - inset * 2f
    val seg = innerW / 3f
    for (i in 0 until 3) {
        if ((i + 1) / 3f <= level + 1e-6f) {
            drawRoundRect(color, Offset(innerLeft + i * seg + 0.6f, innerTop), Size(seg - 1.2f, innerH),
                androidx.compose.ui.geometry.CornerRadius(0.8f))
        }
    }
}
