package dev.hm02.mail.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Official Juubun / Fly accent color preset matching `STYLE_GUIDE.md`.
 */
@Immutable
data class FlyAccentPreset(
  val id: String,
  val label: String,
  val cyan: Color,
  val cyanBright: Color,
  val cyanSoft: Color,
  val cyanDeep: Color,
  val hexLabel: String,
)

val FLY_ACCENT_PRESETS: List<FlyAccentPreset> = listOf(
  FlyAccentPreset("cyan", "Cyan", Color(0xFF67E8F9), Color(0xFF38BDF8), Color(0xFFE0F7FA), Color(0xFF0284C7), "#67E8F9"),
  FlyAccentPreset("steel-blue", "Steel Blue", Color(0xFF7DA2C8), Color(0xFF648DB8), Color(0xFFE6EEF6), Color(0xFF3B5F85), "#7DA2C8"),
  FlyAccentPreset("yellow", "Yellow", Color(0xFFFDE047), Color(0xFFFACC15), Color(0xFFFEF9C3), Color(0xFFCA8A04), "#FDE047"),
  FlyAccentPreset("red", "Red", Color(0xFFF87171), Color(0xFFEF4444), Color(0xFFFEE2E2), Color(0xFFB91C1C), "#F87171"),
  FlyAccentPreset("pink", "Pink", Color(0xFFF9A8D4), Color(0xFFF472B6), Color(0xFFFCE7F3), Color(0xFFBE185D), "#F9A8D4"),
  FlyAccentPreset("coral", "Coral", Color(0xFFFDA4AF), Color(0xFFFB7185), Color(0xFFFFE4E6), Color(0xFFE11D48), "#FDA4AF"),
  FlyAccentPreset("lavender", "Lavender", Color(0xFFC4B5FD), Color(0xFFA78BFA), Color(0xFFEDE9FE), Color(0xFF6D28D9), "#C4B5FD"),
  FlyAccentPreset("mint", "Mint", Color(0xFF6EE7B7), Color(0xFF34D399), Color(0xFFD1FAE5), Color(0xFF047857), "#6EE7B7"),
  FlyAccentPreset("lime", "Lime", Color(0xFFB9FF66), Color(0xFFA3E635), Color(0xFFECFCCB), Color(0xFF4D7C0F), "#B9FF66"),
  FlyAccentPreset("light-grey", "Light Grey", Color(0xFFD4D6E0), Color(0xFFB8BCCB), Color(0xFFEFEFF4), Color(0xFF4B4E63), "#D4D6E0"),
  FlyAccentPreset("white", "White", Color(0xFFFFFFFF), Color(0xFFF3F3F3), Color(0xFFF3F3F3), Color(0xFF191A23), "#FFFFFF"),
)

fun findFlyAccentPreset(presetId: String?): FlyAccentPreset =
  FLY_ACCENT_PRESETS.firstOrNull { it.id == presetId } ?: FLY_ACCENT_PRESETS.first()

/**
 * Juubun Minimalist Mobile App color tokens supporting Light/Dark modes and all 11 accent presets.
 * Retains [SurfThemeColors] naming for seamless compatibility across `:hm02-core`.
 */
@Immutable
data class SurfThemeColors(
  val pageBg: Color,
  val headerBg: Color,
  val cardBg: Color,
  val greySubtle: Color,
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
  val cyan: Color,
  val cyanBright: Color,
  val cyanSoft: Color,
  val cyanDeep: Color,
  val darkInk: Color = Color(0xFF191A23),
  val verifiedGreen: Color = Color(0xFF15803D),
  val alertRed: Color = Color(0xFFDC2626),
  val progressBlue: Color,
  val progressTrack: Color,
  val isDark: Boolean = false,
  val preset: FlyAccentPreset = FLY_ACCENT_PRESETS.first(),
  val isWhitePreset: Boolean = false,
)

fun resolveFlyThemeColors(
  presetId: String = "cyan",
  darkTheme: Boolean = false,
): SurfThemeColors {
  val preset = findFlyAccentPreset(presetId)
  val isWhite = preset.id == "white"
  val bg = if (darkTheme) Color(0xFF191A23) else Color(0xFFFFFFFF)
  val cardBg = if (darkTheme) Color(0xFF1E202B) else Color(0xFFFFFFFF)
  val subtle = if (darkTheme) Color(0xFF1E202B) else Color(0xFFF8F9FA)
  val soft = if (darkTheme) Color(0xFF232533) else Color(0xFFF3F3F3)
  val hairline = if (darkTheme) Color(0xFF2E3142) else Color(0xFFE6E8F0)
  val primaryText = if (darkTheme) Color(0xFFF5F7FA) else Color(0xFF191A23)
  val secondaryText = if (darkTheme) Color(0xFFA0A5B8) else Color(0xFF5A5E72)
  val mutedText = if (darkTheme) Color(0xFF6E7387) else Color(0xFF8C90A4)
  val cyanSoft = if (darkTheme) {
    if (isWhite) Color(0xFF2A2D3D) else lerp(bg, preset.cyan, 0.22f)
  } else {
    preset.cyanSoft
  }
  val cyanDeep = if (darkTheme) {
    if (isWhite) primaryText else preset.cyan
  } else {
    preset.cyanDeep
  }

  return SurfThemeColors(
    pageBg = bg,
    headerBg = bg,
    cardBg = cardBg,
    greySubtle = subtle,
    cardStroke = hairline,
    primaryText = primaryText,
    secondaryText = secondaryText,
    mutedText = mutedText,
    inputBg = soft,
    inputStroke = soft,
    pillBg = soft,
    pillStroke = hairline,
    activePillBg = cyanSoft,
    activePillText = cyanDeep,
    cyan = preset.cyan,
    cyanBright = preset.cyanBright,
    cyanSoft = cyanSoft,
    cyanDeep = cyanDeep,
    verifiedGreen = if (darkTheme) Color(0xFF4ADE80) else Color(0xFF15803D),
    alertRed = if (darkTheme) Color(0xFFF87171) else Color(0xFFDC2626),
    progressBlue = cyanDeep,
    progressTrack = soft,
    isDark = darkTheme,
    preset = preset,
    isWhitePreset = isWhite,
  )
}

val LightSurfColors = resolveFlyThemeColors(presetId = "cyan", darkTheme = false)
val DarkSurfColors = resolveFlyThemeColors(presetId = "cyan", darkTheme = true)

val LocalSurfColors = staticCompositionLocalOf { LightSurfColors }
val LocalFlyColors = LocalSurfColors
