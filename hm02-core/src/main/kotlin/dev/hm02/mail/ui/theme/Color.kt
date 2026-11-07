package dev.hm02.mail.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Exact Zinc & Slate minimalist color tokens matching Surf (`SurfThemeHelper`) and `HM03` (`IntentBrowserActivity`).
 */
@Immutable
data class SurfThemeColors(
  val pageBg: Color,
  val headerBg: Color,
  val cardBg: Color,
  val cardStroke: Color,
  val primaryText: Color,
  val secondaryText: Color,
  val mutedText: Color,
  val inputBg: Color,
  val inputStroke: Color,
  val pillBg: Color,
  val pillStroke: Color,
  val activePillBg: Color,
  val activePillText: Color,
  val verifiedGreen: Color = Color(0xFF16A34A),
  val alertRed: Color = Color(0xFFDC2626),
  val progressBlue: Color = Color(0xFF2563EB),
  val progressTrack: Color,
)

val LightSurfColors = SurfThemeColors(
  pageBg = Color(0xFFF8FAFC),
  headerBg = Color(0xFFF8FAFC),
  cardBg = Color(0xFFFFFFFF),
  cardStroke = Color(0xFFE4E4E7),
  primaryText = Color(0xFF09090B),
  secondaryText = Color(0xFF52525B),
  mutedText = Color(0xFF64748B),
  inputBg = Color(0xFFF4F4F5),
  inputStroke = Color(0xFFD4D4D8),
  pillBg = Color(0xFFF4F4F5),
  pillStroke = Color(0xFFD4D4D8),
  activePillBg = Color(0xFF09090B),
  activePillText = Color(0xFFFAFAFA),
  progressTrack = Color(0xFFE2E8F0),
)

val DarkSurfColors = SurfThemeColors(
  pageBg = Color(0xFF09090B),
  headerBg = Color(0xFF18181B),
  cardBg = Color(0xFF18181B),
  cardStroke = Color(0xFF27272A),
  primaryText = Color(0xFFFAFAFA),
  secondaryText = Color(0xFFA1A1AA),
  mutedText = Color(0xFF71717A),
  inputBg = Color(0xFF09090B),
  inputStroke = Color(0xFF3F3F46),
  pillBg = Color(0xFF27272A),
  pillStroke = Color(0xFF3F3F46),
  activePillBg = Color(0xFFFAFAFA),
  activePillText = Color(0xFF09090B),
  progressTrack = Color(0xFF27272A),
)

val LocalSurfColors = staticCompositionLocalOf { LightSurfColors }
