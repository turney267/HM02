package dev.hm02.mail.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class MailAuthType {
  APP_PASSWORD,
  OAUTH2_MICROSOFT,
}

/**
 * Built-in server presets for common email providers that support IMAP/SMTP with App Passwords or OAuth2.
 */
enum class MailProviderPreset(
  val label: String,
  val imapHost: String,
  val imapPort: Int = 993,
  val smtpHost: String,
  val smtpPort: Int = 465,
  val appPasswordUrl: String?,
  val setupHint: String,
  val authType: MailAuthType = MailAuthType.APP_PASSWORD,
) {
  GMAIL(
    label = "Gmail / Google Workspace",
    imapHost = "imap.gmail.com",
    imapPort = 993,
    smtpHost = "smtp.gmail.com",
    smtpPort = 465,
    appPasswordUrl = "https://myaccount.google.com/apppasswords",
    setupHint = "Requires 2-Step Verification enabled on your Google account. Generate a 16-character App Password and paste it below.",
  ),
  OUTLOOK(
    label = "Outlook / Hotmail",
    imapHost = "outlook.office365.com",
    imapPort = 993,
    smtpHost = "smtp.office365.com",
    smtpPort = 587,
    appPasswordUrl = null,
    setupHint = "Microsoft requires OAuth2 Modern Authentication for Outlook, Hotmail, Live, and Microsoft 365 accounts. Tap Sign in with Microsoft below.",
    authType = MailAuthType.OAUTH2_MICROSOFT,
  ),
  ICLOUD(
    label = "iCloud Mail",
    imapHost = "imap.mail.me.com",
    imapPort = 993,
    smtpHost = "smtp.mail.me.com",
    smtpPort = 587,
    appPasswordUrl = "https://appleid.apple.com/account/manage",
    setupHint = "Sign in to Apple Account -> Sign-In and Security -> App-Specific Passwords.",
  ),
  YAHOO(
    label = "Yahoo Mail",
    imapHost = "imap.mail.yahoo.com",
    imapPort = 993,
    smtpHost = "smtp.mail.yahoo.com",
    smtpPort = 465,
    appPasswordUrl = "https://login.yahoo.com/account/security",
    setupHint = "Go to Yahoo Account Security -> Generate and manage app passwords.",
  ),
  FASTMAIL(
    label = "Fastmail",
    imapHost = "imap.fastmail.com",
    imapPort = 993,
    smtpHost = "smtp.fastmail.com",
    smtpPort = 465,
    appPasswordUrl = "https://app.fastmail.com/settings/security/devicekeys",
    setupHint = "Go to Settings -> Privacy & Security -> Integrations -> New App Password.",
  ),
  CUSTOM(
    label = "Other (Custom IMAP / SMTP)",
    imapHost = "",
    imapPort = 993,
    smtpHost = "",
    smtpPort = 465,
    appPasswordUrl = null,
    setupHint = "Enter your email provider's IMAP and SMTP server details below.",
  ),
}

typealias ProviderPreset = MailProviderPreset

/**
 * Strips CR, LF, NUL, and Unicode bidirectional override characters from header/address text.
 */
internal fun sanitizeHeaderControlChars(value: String): String =
  buildString(value.length) {
    for (ch in value) {
      if (ch == '\r' || ch == '\n' || ch == '\t') {
        if (isNotEmpty() && last() != ' ') append(' ')
        continue
      }
      if (ch.code < 0x20 || ch.code == 0x7F) continue
      if (ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069') continue
      append(ch)
    }
  }.trim()

/**
 * Represents a configured user email account stored encrypted on-device.
 */
@Serializable
data class EmailAccount(
  val id: String = UUID.randomUUID().toString(),
  val displayName: String,
  val emailAddress: String,
  val imapHost: String,
  val imapPort: Int = 993,
  val smtpHost: String,
  val smtpPort: Int = 465,
  val appPassword: String = "",
  val useSsl: Boolean = true,
  val preset: MailProviderPreset? = null,
  val authType: MailAuthType = MailAuthType.APP_PASSWORD,
  val oauthRefreshToken: String? = null,
  val oauthAccessToken: String? = null,
  val oauthTokenExpiresAtMillis: Long = 0L,
) {
  val isGmail: Boolean
    get() = imapHost.contains("gmail.com", ignoreCase = true) ||
      imapHost.contains("googlemail.com", ignoreCase = true)

  val effectivePreset: MailProviderPreset
    get() = preset ?: MailProviderPreset.entries.firstOrNull { candidate ->
      candidate != MailProviderPreset.CUSTOM &&
        candidate.imapHost.equals(imapHost.trim(), ignoreCase = true) &&
        candidate.smtpHost.equals(smtpHost.trim(), ignoreCase = true)
    } ?: MailProviderPreset.CUSTOM

  val isOAuth2: Boolean
    get() = authType == MailAuthType.OAUTH2_MICROSOFT || !oauthRefreshToken.isNullOrBlank()

  override fun toString(): String =
    "EmailAccount(id=$id, displayName=$displayName, emailAddress=$emailAddress, " +
      "imapHost=$imapHost, imapPort=$imapPort, smtpHost=$smtpHost, smtpPort=$smtpPort, " +
      "password=***, authType=$authType, useSsl=$useSsl, preset=${effectivePreset.name})"
}

/**
 * Lightweight email header representation for the feed and search results.
 * No bodies are downloaded until the user taps an email to read it.
 */
@Serializable
data class EmailSummary(
  val uid: Long,
  val accountId: String,
  val accountEmail: String,
  val accountDisplayName: String,
  val messageIdHeader: String? = null,
  val subject: String,
  val fromName: String,
  val fromAddress: String,
  val receivedAtEpochMillis: Long,
  val isUnread: Boolean,
  val isFlagged: Boolean = false,
  val replyToAddress: String? = null,
) {
  val compositeKey: String
    get() = "$accountId:$uid"
}

/**
 * Full on-demand email detail fetched when reading a specific message.
 */
data class EmailDetail(
  val summary: EmailSummary,
  val toAddresses: List<String>,
  val ccAddresses: List<String>,
  val replyToAddress: String?,
  val inReplyToHeader: String?,
  val referencesHeader: String?,
  val plainTextBody: String?,
  val htmlBody: String?,
)

enum class ComposeMode {
  NEW,
  REPLY,
  FORWARD,
}

/**
 * Draft payload for creating a new email, replying, or forwarding.
 */
data class OutgoingEmailDraft(
  val fromAccount: EmailAccount,
  val to: String,
  val cc: String = "",
  val subject: String,
  val body: String,
  val mode: ComposeMode = ComposeMode.NEW,
  val inReplyToMessageId: String? = null,
  val referencesHeader: String? = null,
)

/**
 * User-configurable settings for Contacts, Remember Senders, and Pin to Feed.
 */
@Serializable
data class UserSettings(
  val accessDeviceContacts: Boolean = false,
  val rememberSenders: Boolean = false,
  val enablePinToFeed: Boolean = false,
)

/**
 * Contact entry used for recipient autocomplete in Compose / Reply / Forward.
 */
@Serializable
data class ContactSuggestion(
  val name: String,
  val emailAddress: String,
  val source: String = "Saved",
) {
  val formatted: String
    get() {
      val cleanName = sanitizeHeaderControlChars(name)
      val cleanEmail = sanitizeHeaderControlChars(emailAddress).replace(" ", "")
      return if (cleanName.isNotBlank() && !cleanName.equals(cleanEmail, ignoreCase = true)) {
        val escapedName = cleanName
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
        "\"$escapedName\" <$cleanEmail>"
      } else {
        cleanEmail
      }
    }
}
