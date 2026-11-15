package dev.hm02.mail

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hm02.mail.policy.MailPolicyConfig
import dev.hm02.mail.policy.MailPolicyProvider
import dev.hm02.mail.ui.main.HM02AppScreen
import dev.hm02.mail.ui.main.HM02ViewModel
import dev.hm02.mail.ui.theme.HM02Theme
import dev.hm02.mail.ui.theme.LocalSurfColors

/**
 * Reusable minimalist email Activity provided by `:hm02-core`.
 *
 * Host applications (such as "Fly") can either register [HM02MailActivity] directly after calling
 * [MailPolicyProvider.configure] in `Application.onCreate()`, or subclass [HM02MailActivity] and
 * override [resolvePolicyConfig].
 */
open class HM02MailActivity : ComponentActivity() {

  protected lateinit var hm02ViewModel: HM02ViewModel

  protected open fun resolvePolicyConfig(): MailPolicyConfig = MailPolicyProvider.getConfig()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val initialPolicyConfig = resolvePolicyConfig()
    MailPolicyProvider.configure(initialPolicyConfig)
    hm02ViewModel = ViewModelProvider(
      this,
      HM02ViewModel.provideFactory(
        context = applicationContext,
        policyConfigProvider = ::resolvePolicyConfig,
      ),
    )[HM02ViewModel::class.java]
    hm02ViewModel.updatePolicyConfig(initialPolicyConfig)
    hm02ViewModel.handleOAuthCallbackUri(intent?.dataString)

    enableEdgeToEdge()
    setContent {
      val uiState by hm02ViewModel.uiState.collectAsStateWithLifecycle()
      val systemDark = isSystemInDarkTheme()
      val isDark = uiState.settings.darkThemeOverride ?: systemDark

      LaunchedEffect(isDark) {
        val barStyle = if (isDark) {
          SystemBarStyle.dark(Color.parseColor("#191A23"))
        } else {
          SystemBarStyle.light(Color.WHITE, Color.WHITE)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
      }

      HM02Theme(
        accentPresetId = uiState.settings.accentPresetId,
        darkThemeOverride = uiState.settings.darkThemeOverride,
      ) {
        val surfColors = LocalSurfColors.current
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = surfColors.pageBg,
        ) {
          HM02AppScreen(viewModel = hm02ViewModel)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (::hm02ViewModel.isInitialized) {
      hm02ViewModel.handleOAuthCallbackUri(intent.dataString)
    }
  }

  override fun onResume() {
    super.onResume()
    refreshManagedPolicyConfig()
  }

  protected fun refreshManagedPolicyConfig() {
    val updatedConfig = resolvePolicyConfig()
    MailPolicyProvider.configure(updatedConfig)
    if (::hm02ViewModel.isInitialized) {
      hm02ViewModel.updatePolicyConfig(updatedConfig)
    }
  }
}
