package io.github.arnavdugad.arnavisland

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's look. The accent comes from what plays on your PC (its cover's most vivid colour), as the island's does,
 * and eases from one song's colour to the next; with nothing playing it is the island's own mint.
 */
@Immutable
data class Tokens(
    val dark: Boolean, val text: Color, val muted: Color, val faint: Color, val accent: Color, val accent2: Color, val deep: Color,
    val onAccent: Color, val track: Color, val hairline: Color, val danger: Color, val good: Color, val warn: Color,
)
val Mint = Color(0xFFA5D8C5)
val Periwinkle = Color(0xFF8FA8FF)
fun tokens(dark: Boolean, accent: Color = Mint, accent2: Color = Periwinkle, deep: Color = Color(0xFF0B1220)): Tokens =
    if (dark) Tokens(true, Color(0xFFF5F7FA), Color(0xFFB3BAC6), Color(0xFF7F8796), accent, accent2, deep, Color(0xFF071612), Color(0x29FFFFFF), Color(0x1FFFFFFF),
        Color(0xFFFF6B61), Color(0xFF5FD98A), Color(0xFFFFC04D))
    else Tokens(false, Color(0xFF10131A), Color(0xFF545C6A), Color(0xFF878F9C), lerp(accent, Color.Black, .38f), lerp(accent2, Color.Black, .3f), lerp(deep, Color.White, .9f),
        Color.White, Color(0x1F0B0D12), Color(0x1A0B0D12), Color(0xFFD93A30), Color(0xFF1F9D55), Color(0xFFB7791F))
val LocalTokens = staticCompositionLocalOf { tokens(true) }
/** Reduced motion (Android's "Remove animations"): springs settle at once and nothing drifts. */
val LocalReduced = staticCompositionLocalOf { false }
/** How the phone is tilted (-1..1 on each axis, smoothed): the light on the glass and the cover's parallax follow it. */
val LocalTilt = staticCompositionLocalOf<() -> Offset> { { Offset.Zero } }

object Type {
    val hero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp, lineHeight = 38.sp)
    val title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 30.sp)
    val headline = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp, lineHeight = 25.sp)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.1).sp, lineHeight = 20.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp, lineHeight = 16.sp)
    val micro = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, lineHeight = 14.sp)
    val digits = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
}
