package dev.hm02.mail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
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

    enableEdgeToEdge()
    setContent {
      HM02Theme {
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
