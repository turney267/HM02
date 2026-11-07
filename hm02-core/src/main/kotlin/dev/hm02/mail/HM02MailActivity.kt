package dev.hm02.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.data.SettingsAndContactsStore
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

  protected open fun resolvePolicyConfig(): MailPolicyConfig = MailPolicyProvider.getConfig()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val accountStore = AccountStore(applicationContext)
    val settingsAndContactsStore = SettingsAndContactsStore(applicationContext)
    val policyConfig = resolvePolicyConfig()

    enableEdgeToEdge()
    setContent {
      HM02Theme {
        val surfColors = LocalSurfColors.current
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = surfColors.pageBg,
        ) {
          val hm02ViewModel: HM02ViewModel = viewModel(
            factory = HM02ViewModel.provideFactory(
              accountStore = accountStore,
              settingsAndContactsStore = settingsAndContactsStore,
              policyConfig = policyConfig,
            ),
          )
          HM02AppScreen(viewModel = hm02ViewModel)
        }
      }
    }
  }
}
