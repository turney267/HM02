package dev.hm02.mail.policy

import dev.hm02.mail.model.EmailSummary

/**
 * Host application policy configuration for HM02 (`:hm02-core`), mirroring `BrowserPolicyConfig` in HM03.
 *
 * Allows derivative apps (such as "Fly") to customize:
 * - [brandTitle]: Header brand title rendered in bold monospace (e.g. `"Fly"`).
 * - [feedPolicy]: Time window for the main feed (e.g. 10-minute lookback).
 * - [blockRemoteMediaInEmails]: Strips `<video>`, `<audio>`, `<iframe>`, and `<object>` elements when rendering HTML emails.
 * - [blockExternalImagesByDefault]: Whether external images in HTML emails are blocked by default.
 */
data class MailPolicyConfig(
  val brandTitle: String = "HM02",
  val feedPolicy: FeedPolicy = DefaultFeedPolicy,
  val blockRemoteMediaInEmails: Boolean = true,
  val blockExternalImagesByDefault: Boolean = false,
) {
  /**
   * Evaluates whether [message] should appear in the main feed at [refreshTimestampMillis]
   * based on [feedPolicy]'s lookback window.
   */
  fun evaluateFeedInclusion(
    message: EmailSummary,
    refreshTimestampMillis: Long,
  ): Boolean {
    return feedPolicy.shouldIncludeInFeed(message, refreshTimestampMillis)
  }
}

/**
 * Global runtime policy registry for `:hm02-core` (mirrors `BrowserPolicyProvider` in `:hm03-core`).
 */
object MailPolicyProvider {
  @Volatile
  private var activeConfig: MailPolicyConfig = MailPolicyConfig()

  @JvmStatic
  fun configure(config: MailPolicyConfig) {
    activeConfig = config
  }

  @JvmStatic
  fun getConfig(): MailPolicyConfig = activeConfig
}
