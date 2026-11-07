package dev.hm02.mail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = Color(0xFFFAFAFA),
  onPrimary = Color(0xFF09090B),
  primaryContainer = Color(0xFF27272A),
  onPrimaryContainer = Color(0xFFFAFAFA),
  secondary = Color(0xFFA1A1AA),
  onSecondary = Color(0xFF09090B),
  secondaryContainer = Color(0xFF18181B),
  onSecondaryContainer = Color(0xFFA1A1AA),
  background = Color(0xFF09090B),
  onBackground = Color(0xFFFAFAFA),
  surface = Color(0xFF18181B),
  onSurface = Color(0xFFFAFAFA),
  surfaceVariant = Color(0xFF27272A),
  onSurfaceVariant = Color(0xFFA1A1AA),
  outline = Color(0xFF3F3F46),
  error = Color(0xFFDC2626),
)

private val LightColorScheme = lightColorScheme(
  primary = Color(0xFF09090B),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFF4F4F5),
  onPrimaryContainer = Color(0xFF09090B),
  secondary = Color(0xFF52525B),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFF4F4F5),
  onSecondaryContainer = Color(0xFF52525B),
  background = Color(0xFFF8FAFC),
  onBackground = Color(0xFF09090B),
  surface = Color(0xFFFFFFFF),
  onSurface = Color(0xFF09090B),
  surfaceVariant = Color(0xFFF4F4F5),
  onSurfaceVariant = Color(0xFF52525B),
  outline = Color(0xFFD4D4D8),
  error = Color(0xFFDC2626),
)

@Composable
fun HM02Theme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val surfColors = if (darkTheme) DarkSurfColors else LightSurfColors

  CompositionLocalProvider(LocalSurfColors provides surfColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content,
    )
  }
}
