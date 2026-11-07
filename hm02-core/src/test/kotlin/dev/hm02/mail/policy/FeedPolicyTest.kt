package dev.hm02.mail.policy

import dev.hm02.mail.model.EmailSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class FeedPolicyTest {

  private fun sampleEmail(
    receivedAtEpochMillis: Long,
    fromName: String = "Alice",
    fromAddress: String = "alice@example.com",
    subject: String = "Hello HM02",
  ) = EmailSummary(
    uid = 42L,
    accountId = "acc-1",
    accountEmail = "me@example.com",
    accountDisplayName = "Personal",
    messageIdHeader = "<msg-42@example.com>",
    subject = subject,
    fromName = fromName,
    fromAddress = fromAddress,
    receivedAtEpochMillis = receivedAtEpochMillis,
    isUnread = true,
  )

  @Test
  fun `TimeWindowFeedPolicy includes emails within 10 minutes and excludes older emails`() {
    val policy = TimeWindowFeedPolicy(window = 10.minutes)
    val nowMillis = 1_700_000_000_000L

    val recentEmail = sampleEmail(receivedAtEpochMillis = nowMillis - 5.minutes.inWholeMilliseconds)
    val boundaryEmail = sampleEmail(receivedAtEpochMillis = nowMillis - 10.minutes.inWholeMilliseconds)
    val olderEmail = sampleEmail(receivedAtEpochMillis = nowMillis - 11.minutes.inWholeMilliseconds)

    assertTrue(policy.shouldIncludeInFeed(recentEmail, nowMillis))
    assertTrue(policy.shouldIncludeInFeed(boundaryEmail, nowMillis))
    assertFalse(policy.shouldIncludeInFeed(olderEmail, nowMillis))
  }

  @Test
  fun `MailPolicyConfig enforces 10m window, bulk sender filter, blocked domains, and allowed overrides`() {
    val config = MailPolicyConfig(
      brandTitle = "Fly",
      feedPolicy = TimeWindowFeedPolicy(window = 10.minutes),
      hideBulkAutomatedSendersInFeed = true,
      blockedSenderDomains = setOf("linkedin.com", "substack.com"),
      blockedSubjectKeywords = setOf("unsubscribe", "flash sale"),
      allowedSenderDomainsOverride = setOf("important.gov"),
    )
    val nowMillis = 1_700_000_000_000L
    val twoMinsAgo = nowMillis - 2.minutes.inWholeMilliseconds

    val humanEmail = sampleEmail(receivedAtEpochMillis = twoMinsAgo, fromAddress = "colleague@company.com")
    val noreplyEmail = sampleEmail(receivedAtEpochMillis = twoMinsAgo, fromAddress = "noreply@service.com")
    val blockedDomainEmail = sampleEmail(receivedAtEpochMillis = twoMinsAgo, fromAddress = "updates@mail.linkedin.com")
    val blockedSubjectEmail = sampleEmail(
      receivedAtEpochMillis = twoMinsAgo,
      fromAddress = "shop@store.com",
      subject = "24-Hour Flash Sale Starts Now",
    )
    val overriddenGovEmail = sampleEmail(
      receivedAtEpochMillis = twoMinsAgo,
      fromAddress = "noreply@alerts.important.gov",
    )

    assertTrue(config.evaluateFeedInclusion(humanEmail, nowMillis))
    assertFalse(config.evaluateFeedInclusion(noreplyEmail, nowMillis))
    assertFalse(config.evaluateFeedInclusion(blockedDomainEmail, nowMillis))
    assertFalse(config.evaluateFeedInclusion(blockedSubjectEmail, nowMillis))
    assertTrue(config.evaluateFeedInclusion(overriddenGovEmail, nowMillis))
  }
}
