package dev.hm02.mail.policy

import dev.hm02.mail.model.EmailSummary
import java.util.Locale

/**
 * Host application policy configuration for HM02 (`:hm02-core`), mirroring `BrowserPolicyConfig` in HM03.
 *
 * Allows derivative apps (such as "Fly") to customize:
 * - [brandTitle]: Header brand title rendered in bold monospace (e.g. `"Fly"`).
 * - [feedPolicy]: Time window and custom filtering rules for the main feed (e.g. 10-minute lookback).
 * - [blockRemoteMediaInEmails]: Strips `<video>`, `<audio>`, `<iframe>`, and `<object>` elements when rendering HTML emails.
 * - [hideBulkAutomatedSendersInFeed]: Filters out automated `noreply@` / `newsletter@` / `marketing@` blasts from the main feed (while keeping them searchable via the search bar).
 * - [blockedSenderDomains]: Sender domains excluded from the main feed.
 * - [blockedSenderKeywords]: Sender address/name keywords excluded from the main feed.
 * - [blockedSubjectKeywords]: Subject keywords excluded from the main feed.
 * - [allowedSenderDomainsOverride]: Domains that always bypass sender/keyword blocks in the feed.
 */
data class MailPolicyConfig(
  val brandTitle: String = "HM02",
  val feedPolicy: FeedPolicy = DefaultFeedPolicy,
  val blockRemoteMediaInEmails: Boolean = true,
  val blockExternalImagesByDefault: Boolean = false,
  val hideBulkAutomatedSendersInFeed: Boolean = false,
  val blockedSenderDomains: Set<String> = emptySet(),
  val blockedSenderKeywords: Set<String> = emptySet(),
  val blockedSubjectKeywords: Set<String> = emptySet(),
  val allowedSenderDomainsOverride: Set<String> = emptySet(),
) {
  /**
   * Evaluates whether [message] should appear in the main feed at [refreshTimestampMillis],
   * combining [feedPolicy]'s time window with the configured sender/subject rules.
   */
  fun evaluateFeedInclusion(
    message: EmailSummary,
    refreshTimestampMillis: Long,
  ): Boolean {
    if (!feedPolicy.shouldIncludeInFeed(message, refreshTimestampMillis)) {
      return false
    }

    val senderAddressLower = message.fromAddress.trim().lowercase(Locale.US)
    val senderDomain = senderAddressLower.substringAfter('@', "")
    val senderLocalPart = senderAddressLower.substringBefore('@', "")
    val senderNameLower = message.fromName.lowercase(Locale.US)
    val subjectLower = message.subject.lowercase(Locale.US)

    if (senderDomain.isNotEmpty() && allowedSenderDomainsOverride.any { domainMatches(senderDomain, it) }) {
      return true
    }

    if (hideBulkAutomatedSendersInFeed) {
      if (BULK_AUTOMATED_LOCAL_PREFIXES.any { prefix ->
          senderLocalPart == prefix ||
            senderLocalPart.startsWith("$prefix-") ||
            senderLocalPart.startsWith("${prefix}_") ||
            senderLocalPart.startsWith("$prefix.")
        }
      ) {
        return false
      }
    }

    if (senderDomain.isNotEmpty() && blockedSenderDomains.any { domainMatches(senderDomain, it) }) {
      return false
    }

    if (blockedSenderKeywords.any { kw ->
        val cleanKw = kw.lowercase(Locale.US)
        cleanKw.isNotEmpty() && (senderAddressLower.contains(cleanKw) || senderNameLower.contains(cleanKw))
      }
    ) {
      return false
    }

    if (blockedSubjectKeywords.any { kw ->
        val cleanKw = kw.lowercase(Locale.US)
        cleanKw.isNotEmpty() && subjectLower.contains(cleanKw)
      }
    ) {
      return false
    }

    return true
  }

  private fun domainMatches(candidateDomain: String, ruleDomain: String): Boolean {
    val cleanRule = ruleDomain.trim().lowercase(Locale.US).trimStart('.')
    if (cleanRule.isEmpty()) return false
    return candidateDomain == cleanRule || candidateDomain.endsWith(".$cleanRule")
  }

  companion object {
    private val BULK_AUTOMATED_LOCAL_PREFIXES = setOf(
      "noreply",
      "no-reply",
      "donotreply",
      "do-not-reply",
      "newsletter",
      "newsletters",
      "marketing",
      "promotions",
      "promo",
      "offers",
      "deals",
      "digest",
      "updates",
      "notifications",
      "mailer-daemon",
      "bounce",
    )
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
