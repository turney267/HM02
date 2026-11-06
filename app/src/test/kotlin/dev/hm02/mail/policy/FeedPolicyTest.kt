package dev.hm02.mail.policy

import dev.hm02.mail.model.EmailSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class FeedPolicyTest {

  private fun sampleEmail(
    receivedAtEpochMillis: Long,
    fromAddress: String = "alice@example.com",
  ) = EmailSummary(
    uid = 42L,
    accountId = "acc-1",
    accountEmail = "me@example.com",
    accountDisplayName = "Personal",
    messageIdHeader = "<msg-42@example.com>",
    subject = "Hello HM02",
    fromName = "Alice",
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
  fun `TimeWindowFeedPolicy applies custom additionalFilter`() {
    val policy = TimeWindowFeedPolicy(
      window = 10.minutes,
      additionalFilter = { !it.fromAddress.endsWith("@spam.example") },
    )
    val nowMillis = 1_700_000_000_000L

    val allowedEmail = sampleEmail(
      receivedAtEpochMillis = nowMillis - 2.minutes.inWholeMilliseconds,
      fromAddress = "friend@example.com",
    )
    val blockedEmail = sampleEmail(
      receivedAtEpochMillis = nowMillis - 2.minutes.inWholeMilliseconds,
      fromAddress = "promo@spam.example",
    )

    assertTrue(policy.shouldIncludeInFeed(allowedEmail, nowMillis))
    assertFalse(policy.shouldIncludeInFeed(blockedEmail, nowMillis))
  }
}
