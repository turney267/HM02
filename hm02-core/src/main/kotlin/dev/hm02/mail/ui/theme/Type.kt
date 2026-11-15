package dev.hm02.mail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.hm02.mail.core.R

/**
 * Official Juubun / Fly typography:
 * - **Space Grotesk** (`R.font.space_grotesk`) for brand titles, headings, email subjects, UI labels, and body copy.
 * - **Finger Paint** (`R.font.finger_paint`) reserved strictly for playful logo/icon marks.
 */
@OptIn(ExperimentalTextApi::class)
val SpaceGroteskFontFamily = FontFamily(
  Font(
    resId = R.font.space_grotesk,
    weight = FontWeight.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(400)),
  ),
  Font(
    resId = R.font.space_grotesk,
    weight = FontWeight.Medium,
    variationSettings = FontVariation.Settings(FontVariation.weight(500)),
  ),
  Font(
    resId = R.font.space_grotesk,
    weight = FontWeight.SemiBold,
    variationSettings = FontVariation.Settings(FontVariation.weight(600)),
  ),
  Font(
    resId = R.font.space_grotesk,
    weight = FontWeight.Bold,
    variationSettings = FontVariation.Settings(FontVariation.weight(700)),
  ),
)

val FingerPaintFontFamily = FontFamily(
  Font(resId = R.font.finger_paint, weight = FontWeight.Normal),
  Font(resId = R.font.finger_paint, weight = FontWeight.Bold),
)

val Typography = Typography(
  titleLarge = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.3).sp,
  ),
  titleMedium = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 17.sp,
    lineHeight = 23.sp,
    letterSpacing = (-0.2).sp,
  ),
  titleSmall = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    lineHeight = 20.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.5.sp,
    lineHeight = 21.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp,
    lineHeight = 19.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.5.sp,
    lineHeight = 18.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.2.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
  ),
)
