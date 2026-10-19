package com.portable.musicplayer.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.R

// Champagne-gold body + near-black screen palette (matches the desktop theme).
object Palette {
    val GoldTop = Color(0xFFF5EFE2)
    val GoldMid = Color(0xFFE4D8BF)
    val GoldBottom = Color(0xFFC9B896)
    val GoldEdge = Color(0xFF9C8968)

    val ScreenBgTop = Color(0xFF0B0F14)
    val ScreenBgBottom = Color(0xFF04060A)

    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFFB9BEC4)
    val TextMuted = Color(0xFF7E858D)
    val Accent = Color(0xFFFFFFFF)
    val HrGold = Color(0xFFCBAD77)

    val TrackFill = Color(0xFFEDEDED)
    val TrackRest = Color(0xFF4A4F55)

    val RowDivider = Color(0x14FFFFFF)
    val RowHover = Color(0x0EFFFFFF)

    // dialogs / menus: dark surfaces so the light text stays readable
    val DialogSurface = Color(0xFF12171E)
    val DialogOutline = Color(0x33FFFFFF)
}

/** SST-JP-like stack: bundled Noto Sans JP. */
val AppFont = FontFamily(
    Font(R.font.noto_sans_jp, FontWeight.Normal),
    Font(R.font.noto_sans_jp, FontWeight.Medium),
    Font(R.font.noto_sans_jp, FontWeight.Bold),
)

val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = AppFont, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = AppFont, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = AppFont, fontSize = 12.sp),
)
