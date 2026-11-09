package dev.hm02.mail.security

import dev.hm02.mail.data.ExtractedBody
import dev.hm02.mail.data.MAX_MIME_PARTS
import dev.hm02.mail.data.MAX_TEXT_BODY_BYTES
import dev.hm02.mail.data.MicrosoftOAuthHelper
import dev.hm02.mail.data.buildImapProperties
import dev.hm02.mail.data.buildSmtpProperties
import dev.hm02.mail.data.extractMimeContent
import dev.hm02.mail.data.normalizePassword
import dev.hm02.mail.data.parseQuickDaySearchDays
import dev.hm02.mail.data.sanitizeReferencesHeader
import dev.hm02.mail.data.sanitizeSingleMessageId
import dev.hm02.mail.data.validateMailServerEndpoint
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.MailAuthType
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.sanitizeHeaderControlChars
import dev.hm02.mail.ui.main.containsRemoteImages
import dev.hm02.mail.ui.main.parseMailtoUri
import dev.hm02.mail.ui.main.sanitizeAndWrapEmailHtml
import dev.hm02.mail.ui.main.sanitizeEmailHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class MailSecurityTest {

  private fun sampleAccount(
    imapHost: String = "imap.gmail.com",
    imapPort: Int = 993,
    smtpHost: String = "smtp.gmail.com",
    smtpPort: Int = 465,
    appPassword: String = "abcd efgh ijkl mnop",
    preset: MailProviderPreset? = MailProviderPreset.GMAIL,
  ) = EmailAccount(
    displayName = "Alice",
    emailAddress = "alice@example.com",
    imapHost = imapHost,
    imapPort = imapPort,
    smtpHost = smtpHost,
    smtpPort = smtpPort,
    appPassword = appPassword,
    useSsl = false, // Should still be forced to SSL true in buildImapProperties
    preset = preset,
  )

  @Test
  fun `buildImapProperties enforces TLS, server identity check, TLSv1_2_1_3, and writetimeout`() {
    val props = buildImapProperties(sampleAccount())
    assertEquals("imaps", props.getProperty("mail.store.protocol"))
    assertEquals("true", props.getProperty("mail.imaps.ssl.enable"))
    assertEquals("true", props.getProperty("mail.imaps.ssl.checkserveridentity"))
    assertEquals("TLSv1.2 TLSv1.3", props.getProperty("mail.imaps.ssl.protocols"))
    assertEquals("15000", props.getProperty("mail.imaps.writetimeout"))
  }

  @Test
  fun `buildSmtpProperties enforces server identity check, protocols, and anonymized localhost on 465 and 587`() {
    val sslProps = buildSmtpProperties(sampleAccount(smtpPort = 465))
    assertEquals("true", sslProps.getProperty("mail.smtp.ssl.enable"))
    assertEquals("true", sslProps.getProperty("mail.smtp.ssl.checkserveridentity"))
    assertEquals("true", sslProps.getProperty("mail.smtps.ssl.checkserveridentity"))
    assertEquals("TLSv1.2 TLSv1.3", sslProps.getProperty("mail.smtp.ssl.protocols"))
    assertEquals("TLSv1.2 TLSv1.3", sslProps.getProperty("mail.smtps.ssl.protocols"))
    assertEquals("localhost", sslProps.getProperty("mail.smtp.localhost"))
    assertEquals("localhost", sslProps.getProperty("mail.smtps.localhost"))

    val startTlsProps = buildSmtpProperties(sampleAccount(smtpHost = "smtp.office365.com", smtpPort = 587))
    assertEquals("true", startTlsProps.getProperty("mail.smtp.starttls.enable"))
    assertEquals("true", startTlsProps.getProperty("mail.smtp.starttls.required"))
    assertEquals("true", startTlsProps.getProperty("mail.smtp.ssl.checkserveridentity"))
  }

  @Test
  fun `validateMailServerEndpoint accepts valid hosts and rejects loopback, private, link-local, internal, and bad ports`() {
    validateMailServerEndpoint("imap.gmail.com", 993, "IMAP")
    validateMailServerEndpoint("smtp.office365.com", 587, "SMTP")
    validateMailServerEndpoint("mail.my-domain.co.uk", 465, "SMTP")

    val invalidEndpoints = listOf(
      "" to 993,
      "   " to 993,
      "imap.gmail.com" to 0,
      "imap.gmail.com" to -1,
      "imap.gmail.com" to 65536,
      "localhost" to 993,
      "sub.localhost" to 993,
      "printer.local" to 993,
      "printer.local." to 993,
      "metadata.internal" to 993,
      "singlelabel" to 993,
      "127.0.0.1" to 993,
      "10.0.0.5" to 993,
      "192.168.1.10" to 993,
      "172.16.0.1" to 993,
      "172.31.255.255" to 993,
      "169.254.169.254" to 993,
      "0.0.0.0" to 993,
      "::1" to 993,
      "[::1]" to 993,
      "fe80::1" to 993,
      "fc00::1" to 993,
      "fd12:3456::1" to 993,
      "imap.example.com/path" to 993,
      "imap.example.com:143" to 993,
      "user@imap.example.com" to 993,
      "imap.example.com\r\n" to 993,
    )

    for ((host, port) in invalidEndpoints) {
      assertThrows("Expected rejection for $host:$port", IllegalArgumentException::class.java) {
        validateMailServerEndpoint(host, port, "IMAP")
      }
    }
  }

  @Test
  fun `normalizePassword strips spaces for presets and preserves spaces for CUSTOM`() {
    assertEquals(
      "abcdefghijklmnop",
      normalizePassword("abcd efgh ijkl mnop", MailProviderPreset.GMAIL),
    )
    assertEquals(
      "abcdefghijklmnop",
      normalizePassword("abcd efgh ijkl mnop", MailProviderPreset.OUTLOOK),
    )
    assertEquals(
      "correct horse battery staple",
      normalizePassword("correct horse battery staple\r\n", MailProviderPreset.CUSTOM),
    )
  }

  @Test
  fun `EmailAccount toString redacts appPassword`() {
    val account = sampleAccount(appPassword = "super-secret-password-1234")
    val str = account.toString()
    assertFalse(str.contains("super-secret-password-1234"))
    assertTrue(str.contains("password=***"))
  }

  @Test
  fun `MIME header sanitizers strip CRLF injection and enforce safe Message-ID syntax`() {
    assertEquals(
      "Re: Quarterly Report Bcc: attacker@example.org",
      sanitizeHeaderControlChars("Re: Quarterly Report\r\nBcc: attacker@example.org\u0000"),
    )

    assertEquals(
      "<msg-123.abc_def@mail.example.com>",
      sanitizeSingleMessageId("<msg-123.abc_def@mail.example.com>\r\nBcc: <spy@attacker.example>"),
    )
    assertNull(sanitizeSingleMessageId("not-a-message-id\r\nBcc: spy@attacker.example"))

    // Legitimate RFC 5322 folded References header unfolds cleanly, while non-folded CRLF injection is truncated
    assertEquals(
      "<first@a.example> <second@b.example>",
      sanitizeReferencesHeader("<first@a.example>\r\n\t<second@b.example>\r\nBcc: <spy@attacker.example>"),
    )
  }

  @Test
  fun `ContactSuggestion formatted RFC-quotes display names and prevents recipient injection`() {
    val injectedContact = ContactSuggestion(
      name = "spy@attacker.example, \"CEO\" \\ Admin\r\nBcc: hidden@attacker.example",
      emailAddress = "ceo@company.example\r\n",
    )
    val formatted = injectedContact.formatted
    assertFalse(formatted.contains("\r"))
    assertFalse(formatted.contains("\n"))

    val parsed = InternetAddress.parse(formatted, false)
    assertEquals("Must parse as strictly a single recipient", 1, parsed.size)
    assertEquals("ceo@company.example", parsed[0].address)
  }

  @Test
  fun `sanitizeEmailHtml fixed-point loop strips nested split tags, meta refresh, base, form, and script URIs`() {
    val adversarialHtml = """
      <meta http-equiv="refresh" content="0;url=https://tracker.example/auto">
      <base href="https://phish.example/">
      <if<iframe/>rame src="https://evil.example/frame">inner</if<iframe/>rame>
      <scr<script>evil()</script>ipt>alert(1)</script>
      <video src="https://tracker.example/v.mp4" autoplay></video>
      <form action="https://phish.example/steal"><button>Submit</button></form>
      <a href="jav&#x09;ascript:alert(1)" onclick="steal()">Click</a>
      <a href="java	script:alert(2)">Click2</a>
      <a href="data:text/html,<script>alert(3)</script>">Click3</a>
      <p>Safe paragraph</p>
      <img src="https://cdn.example.com/banner.png" />
    """.trimIndent()

    val sanitized = sanitizeEmailHtml(adversarialHtml, stripMediaElements = true)
    val lower = sanitized.lowercase()
    assertFalse(lower.contains("<meta"))
    assertFalse(lower.contains("<base"))
    assertFalse(lower.contains("<iframe"))
    assertFalse(lower.contains("<script"))
    assertFalse(lower.contains("<video"))
    assertFalse(lower.contains("<form"))
    assertFalse(lower.contains("onclick="))
    assertFalse(lower.contains("javascript:"))
    assertFalse(lower.contains("jav&#x09;ascript"))
    assertFalse(lower.contains("data:text/html"))
    assertTrue(sanitized.contains("<p>Safe paragraph</p>"))
    assertTrue(containsRemoteImages(adversarialHtml))

    val wrappedBlocked = sanitizeAndWrapEmailHtml(adversarialHtml, stripMediaElements = true, allowExternalImages = false)
    assertTrue(wrappedBlocked.contains("img-src data: cid:;"))
    assertFalse(wrappedBlocked.contains("img-src data: cid: https:;"))

    val wrappedAllowed = sanitizeAndWrapEmailHtml(adversarialHtml, stripMediaElements = true, allowExternalImages = true)
    assertTrue(wrappedAllowed.contains("img-src data: cid: https:;"))
  }

  @Test
  fun `extractMimeContent bounds recursion depth, total part count, and 1MB body stream size`() {
    val session = Session.getInstance(Properties())

    // 1. Deeply nested multipart (25 levels > MAX_MIME_DEPTH = 10) does not overflow or read beyond depth 10
    val rootMessage = MimeMessage(session)
    var currentMultipart = MimeMultipart()
    rootMessage.setContent(currentMultipart)
    for (level in 1..25) {
      val nestedPart = MimeBodyPart()
      val nextMultipart = MimeMultipart()
      nestedPart.setContent(nextMultipart)
      currentMultipart.addBodyPart(nestedPart)
      currentMultipart = nextMultipart
    }
    val tooDeepLeaf = MimeBodyPart().apply { setText("unreachable-deep-text", "utf-8") }
    currentMultipart.addBodyPart(tooDeepLeaf)
    rootMessage.saveChanges()

    val deepOut = ExtractedBody()
    extractMimeContent(rootMessage, deepOut)
    assertNull(deepOut.plainText)
    assertTrue(deepOut.partsVisited <= MAX_MIME_PARTS)

    // 2. Wide multipart (75 sibling parts > MAX_MIME_PARTS = 64) stops visiting after MAX_MIME_PARTS
    val wideMessage = MimeMessage(session)
    val wideMultipart = MimeMultipart()
    for (idx in 1..75) {
      val attachmentLike = MimeBodyPart().apply {
        disposition = javax.mail.Part.ATTACHMENT
        setText("attachment-$idx", "utf-8")
      }
      wideMultipart.addBodyPart(attachmentLike)
    }
    val unreachableTailText = MimeBodyPart().apply { setText("tail-after-cap", "utf-8") }
    wideMultipart.addBodyPart(unreachableTailText)
    wideMessage.setContent(wideMultipart)
    wideMessage.saveChanges()

    val wideOut = ExtractedBody()
    extractMimeContent(wideMessage, wideOut)
    assertNull(wideOut.plainText)
    assertEquals(MAX_MIME_PARTS, wideOut.partsVisited)

    // 3. Oversized 1.4 MB text/plain stream is capped cleanly at MAX_TEXT_BODY_BYTES (1 MB)
    val rawHeaders =
      "From: a@example.com\r\n" +
        "To: b@example.com\r\n" +
        "Subject: Oversized\r\n" +
        "MIME-Version: 1.0\r\n" +
        "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
    val rawBytes = (rawHeaders + "A".repeat(MAX_TEXT_BODY_BYTES + 400_000)).encodeToByteArray()
    val largeMessage = MimeMessage(session, java.io.ByteArrayInputStream(rawBytes))

    val largeOut = ExtractedBody()
    extractMimeContent(largeMessage, largeOut)
    assertNotNull(largeOut.plainText)
    assertEquals(MAX_TEXT_BODY_BYTES, largeOut.plainText!!.length)
  }

  @Test
  fun `parseMailtoUri extracts recipients, subject, and body while stripping CRLF injection`() {
    val parsed = parseMailtoUri(
      "mailto:bob@example.com?cc=carol@example.com&subject=Hello%0D%0ABcc%3A%20spy%40evil.com&body=Line1%0ALine2",
    )
    assertNotNull(parsed)
    assertEquals("bob@example.com", parsed!!.to)
    assertEquals("carol@example.com", parsed.cc)
    assertFalse(parsed.subject.contains("\r"))
    assertFalse(parsed.subject.contains("\n"))
    assertEquals("Line1\nLine2", parsed.body)

    assertNull(parseMailtoUri("https://example.com"))
  }

  @Test
  fun `parseQuickDaySearchDays respects configurable maxDays policy`() {
    assertEquals(5, parseQuickDaySearchDays("a5", maxDays = 7))
    assertNull(parseQuickDaySearchDays("a8", maxDays = 7))
    assertNull(parseQuickDaySearchDays("a", maxDays = 0))
  }

  @Test
  fun `OAuth2 Outlook account configures XOAUTH2 SASL properties and PKCE URL`() {
    val oauthAccount = sampleAccount(
      imapHost = MailProviderPreset.OUTLOOK.imapHost,
      imapPort = MailProviderPreset.OUTLOOK.imapPort,
      smtpHost = MailProviderPreset.OUTLOOK.smtpHost,
      smtpPort = MailProviderPreset.OUTLOOK.smtpPort,
      preset = MailProviderPreset.OUTLOOK,
    ).copy(
      authType = MailAuthType.OAUTH2_MICROSOFT,
      oauthRefreshToken = "refresh-token-xyz",
    )

    assertTrue(oauthAccount.isOAuth2)
    val imapProps = buildImapProperties(oauthAccount)
    assertEquals("XOAUTH2", imapProps.getProperty("mail.imaps.auth.mechanisms"))
    assertEquals("true", imapProps.getProperty("mail.imaps.sasl.enable"))
    assertEquals("XOAUTH2", imapProps.getProperty("mail.imaps.sasl.mechanisms"))
    assertEquals("true", imapProps.getProperty("mail.imaps.auth.login.disable"))
    assertEquals("true", imapProps.getProperty("mail.imaps.auth.plain.disable"))

    val smtpProps = buildSmtpProperties(oauthAccount)
    assertEquals("XOAUTH2", smtpProps.getProperty("mail.smtp.auth.mechanisms"))
    assertEquals("true", smtpProps.getProperty("mail.smtp.sasl.enable"))
    assertEquals("XOAUTH2", smtpProps.getProperty("mail.smtp.sasl.mechanisms"))

    val pkce = MicrosoftOAuthHelper.generatePkceChallenge()
    assertTrue(pkce.codeVerifier.length >= 43)
    assertTrue(pkce.codeChallenge.isNotBlank())
    val authUrl = MicrosoftOAuthHelper.buildAuthorizationUrl(pkce, "user@outlook.com")
    assertTrue(authUrl.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"))
    assertTrue(authUrl.contains("code_challenge_method=S256"))
    assertTrue(authUrl.contains("IMAP.AccessAsUser.All"))
    assertTrue(authUrl.contains("SMTP.Send"))
    assertTrue(MicrosoftOAuthHelper.isRedirectUri("https://login.microsoftonline.com/common/oauth2/nativeclient?code=123"))
  }
}
