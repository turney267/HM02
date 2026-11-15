package dev.hm02.mail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun HM02Theme(
  accentPresetId: String = "cyan",
  darkThemeOverride: Boolean? = false,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val isDark = darkThemeOverride ?: systemDark
  val flyColors = resolveFlyThemeColors(presetId = accentPresetId, darkTheme = isDark)

  val colorScheme = if (isDark) {
    darkColorScheme(
      primary = flyColors.cyanDeep,
      onPrimary = Color(0xFF191A23),
      primaryContainer = flyColors.cyanSoft,
      onPrimaryContainer = flyColors.cyanDeep,
      secondary = flyColors.secondaryText,
      onSecondary = flyColors.pageBg,
      secondaryContainer = flyColors.greySubtle,
      onSecondaryContainer = flyColors.primaryText,
      background = flyColors.pageBg,
      onBackground = flyColors.primaryText,
      surface = flyColors.cardBg,
      onSurface = flyColors.primaryText,
      surfaceVariant = flyColors.pillBg,
      onSurfaceVariant = flyColors.secondaryText,
      outline = flyColors.cardStroke,
      error = flyColors.alertRed,
    )
  } else {
    lightColorScheme(
      primary = flyColors.cyanDeep,
      onPrimary = Color.White,
      primaryContainer = flyColors.cyanSoft,
      onPrimaryContainer = flyColors.cyanDeep,
      secondary = flyColors.secondaryText,
      onSecondary = Color.White,
      secondaryContainer = flyColors.greySubtle,
      onSecondaryContainer = flyColors.primaryText,
      background = flyColors.pageBg,
      onBackground = flyColors.primaryText,
      surface = flyColors.cardBg,
      onSurface = flyColors.primaryText,
      surfaceVariant = flyColors.pillBg,
      onSurfaceVariant = flyColors.secondaryText,
      outline = flyColors.cardStroke,
      error = flyColors.alertRed,
    )
  }

  CompositionLocalProvider(LocalSurfColors provides flyColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content,
    )
  }
}
