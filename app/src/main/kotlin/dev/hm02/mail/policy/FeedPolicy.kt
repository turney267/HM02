package dev.hm02.mail.policy

import dev.hm02.mail.model.EmailSummary
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Extension point for customizing how HM02 populates and filters the main email feed.
 *
 * The feed is refreshed whenever the user opens/resumes the app or triggers a manual refresh.
 * Derivative apps (such as "Fly") can provide their own [FeedPolicy] implementation to enforce
 * custom time windows (e.g., last 10 minutes only), sender allowlists, or other minimalist rules,
 * while all older emails remain accessible via the server-side search bar.
 */
interface FeedPolicy {
  /** Human-readable name of the active feed policy. */
  val name: String

  /** Short description shown in the empty state when no emails match the feed policy. */
  val emptyStateDescription: String

  /**
   * How far back from the moment of refresh the feed should include messages.
   * For example, `24.hours` in the base template, or `10.minutes` in a minimalist spin-off.
   */
  val lookbackWindow: Duration

  /** Maximum number of recent headers to inspect per account during a feed refresh. */
  val maxMessagesPerAccount: Int
    get() = 50

  /**
   * Optional custom filter hook evaluated for each candidate message within [lookbackWindow].
   * Override this in your app to add sender filters, domain restrictions, or subject rules.
   */
  fun shouldIncludeInFeed(
    message: EmailSummary,
    refreshTimestampMillis: Long,
  ): Boolean {
    val cutoffMillis = refreshTimestampMillis - lookbackWindow.inWholeMilliseconds
    return message.receivedAtEpochMillis >= cutoffMillis
  }
}

/**
 * Default unopinionated policy for the open-source HM02 template.
 * Shows emails received in the last 24 hours; all older emails are found via search.
 */
object DefaultFeedPolicy : FeedPolicy {
  override val name: String = "Recent (24h)"
  override val emptyStateDescription: String =
    "No emails received in the last 24 hours.\nUse the search bar above to find older messages."
  override val lookbackWindow: Duration = 24.hours
}

/**
 * Configurable time-window policy (e.g. `TimeWindowFeedPolicy(window = 10.minutes)`).
 * Ideal for minimalist spin-offs that only display emails received in the last N minutes
 * whenever the user opens the app.
 */
class TimeWindowFeedPolicy(
  val window: Duration = 10.minutes,
  override val name: String = "Last ${window.inWholeMinutes}m Feed",
  override val emptyStateDescription: String =
    "No emails received in the last ${window.inWholeMinutes} minutes.\nUse the search bar above to find any older email.",
  private val additionalFilter: (EmailSummary) -> Boolean = { true },
) : FeedPolicy {
  override val lookbackWindow: Duration = window

  override fun shouldIncludeInFeed(
    message: EmailSummary,
    refreshTimestampMillis: Long,
  ): Boolean {
    val cutoffMillis = refreshTimestampMillis - lookbackWindow.inWholeMilliseconds
    return message.receivedAtEpochMillis >= cutoffMillis && additionalFilter(message)
  }
}
