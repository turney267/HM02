package dev.hm02.mail.policy

import dev.hm02.mail.data.computeDayWindowStartMillis
import dev.hm02.mail.data.parseQuickDaySearchDays
import dev.hm02.mail.model.EmailSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
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
  fun `MailPolicyConfig enforces 10m window without sender blocklists`() {
    val config = MailPolicyConfig(
      brandTitle = "Fly",
      feedPolicy = TimeWindowFeedPolicy(window = 10.minutes),
    )
    val nowMillis = 1_700_000_000_000L
    val twoMinsAgo = nowMillis - 2.minutes.inWholeMilliseconds
    val fifteenMinsAgo = nowMillis - 15.minutes.inWholeMilliseconds

    val recentEmail = sampleEmail(receivedAtEpochMillis = twoMinsAgo, fromAddress = "anyone@company.com")
    val olderEmail = sampleEmail(receivedAtEpochMillis = fifteenMinsAgo, fromAddress = "anyone@company.com")

    assertTrue(config.evaluateFeedInclusion(recentEmail, nowMillis))
    assertFalse(config.evaluateFeedInclusion(olderEmail, nowMillis))
  }

  @Test
  fun `parseQuickDaySearchDays parses a, a2, a7 and ignores normal keywords`() {
    assertEquals(1, parseQuickDaySearchDays("a"))
    assertEquals(1, parseQuickDaySearchDays("A"))
    assertEquals(1, parseQuickDaySearchDays(" a1 "))
    assertEquals(2, parseQuickDaySearchDays("a2"))
    assertEquals(7, parseQuickDaySearchDays("a7"))
    assertNull(parseQuickDaySearchDays("amazon"))
    assertNull(parseQuickDaySearchDays("a0"))
    assertNull(parseQuickDaySearchDays("invoice"))
  }

  @Test
  fun `computeDayWindowStartMillis returns start of today for a and yesterday for a2`() {
    val now = 1_700_050_000_000L
    val startToday = computeDayWindowStartMillis(days = 1, nowMillis = now)
    val startYesterday = computeDayWindowStartMillis(days = 2, nowMillis = now)

    assertTrue(startToday <= now)
    assertEquals(1.days.inWholeMilliseconds, startToday - startYesterday)
  }
}
