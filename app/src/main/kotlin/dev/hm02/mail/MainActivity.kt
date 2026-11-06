package dev.hm02.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.policy.DefaultFeedPolicy
import dev.hm02.mail.ui.main.HM02AppScreen
import dev.hm02.mail.ui.main.HM02ViewModel
import dev.hm02.mail.ui.theme.HM02Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val accountStore = AccountStore(applicationContext)

    // Open-source template uses DefaultFeedPolicy (24h feed window).
    // Derivative apps (e.g., "Fly") can replace this with:
    //   TimeWindowFeedPolicy(window = 10.minutes)
    // or a custom FeedPolicy implementation with sender/keyword restrictions.
    val activeFeedPolicy = DefaultFeedPolicy

    enableEdgeToEdge()
    setContent {
      HM02Theme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          val hm02ViewModel: HM02ViewModel = viewModel(
            factory = HM02ViewModel.provideFactory(
              accountStore = accountStore,
              feedPolicy = activeFeedPolicy,
            ),
          )
          HM02AppScreen(viewModel = hm02ViewModel)
        }
      }
    }
  }
}
