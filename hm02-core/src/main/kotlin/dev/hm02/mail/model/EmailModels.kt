package dev.hm02.mail.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Built-in server presets for common email providers that support IMAP/SMTP with App Passwords.
 */
enum class MailProviderPreset(
  val label: String,
  val imapHost: String,
  val imapPort: Int = 993,
  val smtpHost: String,
  val smtpPort: Int = 465,
  val appPasswordUrl: String?,
  val setupHint: String,
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
    appPasswordUrl = "https://account.live.com/proofs/manage/additional",
    setupHint = "Create an App Password under Microsoft Account -> Security -> Advanced security options.",
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
  val appPassword: String,
  val useSsl: Boolean = true,
) {
  val isGmail: Boolean
    get() = imapHost.contains("gmail.com", ignoreCase = true) ||
      imapHost.contains("googlemail.com", ignoreCase = true)
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
 * User-configurable settings for Contacts (autocomplete + saving contacts) and Pin to Feed.
 */
@Serializable
data class UserSettings(
  val accessDeviceContacts: Boolean = false,
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
    get() = if (name.isNotBlank() && !name.equals(emailAddress, ignoreCase = true)) {
      "$name <$emailAddress>"
    } else {
      emailAddress
    }
}
