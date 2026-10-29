package io.github.arnavdugad.arnavisland

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's look, after Apple's: its system colours, labels in three strengths over glass, and a type scale from the
 * large title down to the footnote. The accent comes from what plays on your PC (its cover's most vivid colour) and eases
 * from one song's colour to the next; with nothing playing it is Apple's blue.
 */
@Immutable
data class Tokens(
    val dark: Boolean, val text: Color, val muted: Color, val faint: Color, val accent: Color, val accent2: Color, val deep: Color,
    val onAccent: Color, val track: Color, val hairline: Color, val danger: Color, val good: Color, val warn: Color,
    /** 1.8: the rest of Apple's system colours, for icon tiles, graphs and states. */
    val blue: Color, val indigo: Color, val purple: Color, val pink: Color, val teal: Color, val yellow: Color, val gray: Color,
    /** A quiet fill for wells, tracks and unselected controls. */
    val fill: Color,
)
val SystemBlue = Color(0xFF0A84FF)
val SystemIndigo = Color(0xFF5E5CE6)
/** The island's own mint, still its brand colour (the app icon and the welcome). */
val Mint = Color(0xFFA5D8C5)
val Periwinkle = Color(0xFF8FA8FF)
fun tokens(dark: Boolean, accent: Color = SystemBlue, accent2: Color = SystemIndigo, deep: Color = Color(0xFF0B1220)): Tokens {
    // A cover's colour on a light page is deepened so it reads; text on it is dark or light by its own lightness.
    val a = if (dark) accent else lerp(accent, Color.Black, (accent.luminance() - .25f).coerceIn(0f, .4f))
    val on = if (a.luminance() > .5f) Color(0xFF111114) else Color.White
    return if (dark) Tokens(true, Color(0xFFF5F5F7), Color(0xA8EBEBF5), Color(0x5EEBEBF5), a, accent2, deep, on, Color(0x3D787880), Color(0x24FFFFFF),
        Color(0xFFFF453A), Color(0xFF30D158), Color(0xFFFF9F0A),
        Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFF64D2FF), Color(0xFFFFD60A), Color(0xFF8E8E93), Color(0x29787880))
    else Tokens(false, Color(0xFF1D1D1F), Color(0x993C3C43), Color(0x523C3C43), a, lerp(accent2, Color.Black, .25f), lerp(deep, Color.White, .9f), on, Color(0x29787880), Color(0x1F000000),
        Color(0xFFFF3B30), Color(0xFF34C759), Color(0xFFFF9500),
        Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF32ADE6), Color(0xFFFFCC00), Color(0xFF8E8E93), Color(0x14787880))
}
val LocalTokens = staticCompositionLocalOf { tokens(true) }
/** Reduced motion (Android's "Remove animations"): springs settle at once and nothing drifts. */
val LocalReduced = staticCompositionLocalOf { false }
/** How the phone is tilted (-1..1 on each axis, smoothed): the light on the glass and the cover's parallax follow it. */
val LocalTilt = staticCompositionLocalOf<() -> Offset> { { Offset.Zero } }

/**
 * Apple's type scale, for this screen size. Every style keeps its digits the same width (tabular figures), so times,
 * percentages and rates don't jitter sideways as they tick.
 */
object Type {
    private const val TNUM = "tnum"
    val hero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, lineHeight = 40.sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 33.sp, fontFeatureSettings = TNUM)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 27.sp, fontFeatureSettings = TNUM)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp, lineHeight = 22.sp, fontFeatureSettings = TNUM)
    val body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp, lineHeight = 21.sp, fontFeatureSettings = TNUM)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val callout = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.15).sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    val caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.05).sp, lineHeight = 17.sp, fontFeatureSettings = TNUM)
    val micro = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, lineHeight = 13.sp, fontFeatureSettings = TNUM)
    val digits = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp, fontFeatureSettings = TNUM)
    /** A big number in a gauge or a tile. */
    val number = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 28.sp, fontFeatureSettings = TNUM)
}
