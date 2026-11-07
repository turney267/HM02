package dev.hm02.mail

import dev.hm02.mail.policy.DefaultFeedPolicy
import dev.hm02.mail.policy.MailPolicyConfig

/**
 * Standalone open-source HM02 template Activity (`:app`), powered by `:hm02-core`.
 *
 * Uses the unopinionated 24-hour [DefaultFeedPolicy] out of the box.
 * Host apps (such as "Fly") can depend on `:hm02-core` and configure [MailPolicyConfig]
 * with a 10-minute feed window and custom sender/subject rules.
 */
class MainActivity : HM02MailActivity() {
  override fun resolvePolicyConfig(): MailPolicyConfig =
    MailPolicyConfig(
      brandTitle = "HM02",
      feedPolicy = DefaultFeedPolicy,
    )
}
