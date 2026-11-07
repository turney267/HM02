package dev.hm02.mail.data

import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.policy.MailPolicyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.Authenticator
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SubjectTerm

/**
 * Abstraction for the bare-bones HM02 email operations:
 * 1. Fetch feed across multiple accounts according to [MailPolicyConfig]
 * 2. Server-side search across multiple accounts
 * 3. Read full email message by UID
 * 4. Send email (new, reply, forward)
 */
interface MailClient {
  suspend fun verifyAccount(account: EmailAccount): Result<Unit>
  suspend fun fetchFeed(accounts: List<EmailAccount>, config: MailPolicyConfig): Result<List<EmailSummary>>
  suspend fun searchMessages(
    accounts: List<EmailAccount>,
    query: String,
    maxPerAccount: Int = 30,
  ): Result<List<EmailSummary>>
  suspend fun fetchMessageDetail(account: EmailAccount, uid: Long): Result<EmailDetail>
  suspend fun sendEmail(draft: OutgoingEmailDraft): Result<Unit>
}

/**
 * Pure IMAP + SMTP implementation of [MailClient] using Jakarta Mail / JavaMail on Android.
 * Performs foreground-only network requests with zero background services or push notifications.
 */
class ImapSmtpMailClient : MailClient {

  override suspend fun verifyAccount(account: EmailAccount): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        openImapStore(account).use { store ->
          val inbox = store.getFolder(INBOX_FOLDER)
          inbox.open(Folder.READ_ONLY)
          inbox.close(false)
        }
      }
    }

  override suspend fun fetchFeed(
    accounts: List<EmailAccount>,
    config: MailPolicyConfig,
  ): Result<List<EmailSummary>> = withContext(Dispatchers.IO) {
    if (accounts.isEmpty()) return@withContext Result.success(emptyList())

    runCatching {
      val refreshTimeMillis = System.currentTimeMillis()
      val perAccountResults = coroutineScope {
        accounts.map { account ->
          async {
            runCatching {
              fetchAccountFeed(account, config, refreshTimeMillis)
            }.getOrDefault(emptyList())
          }
        }.awaitAll()
      }
      perAccountResults
        .flatten()
        .sortedByDescending { it.receivedAtEpochMillis }
    }
  }

  override suspend fun searchMessages(
    accounts: List<EmailAccount>,
    query: String,
    maxPerAccount: Int,
  ): Result<List<EmailSummary>> = withContext(Dispatchers.IO) {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty() || accounts.isEmpty()) {
      return@withContext Result.success(emptyList())
    }

    runCatching {
      val perAccountResults = coroutineScope {
        accounts.map { account ->
          async {
            runCatching {
              searchAccountMessages(account, trimmedQuery, maxPerAccount)
            }.getOrDefault(emptyList())
          }
        }.awaitAll()
      }
      perAccountResults
        .flatten()
        .sortedByDescending { it.receivedAtEpochMillis }
    }
  }

  override suspend fun fetchMessageDetail(
    account: EmailAccount,
    uid: Long,
  ): Result<EmailDetail> = withContext(Dispatchers.IO) {
    runCatching {
      openImapStore(account).use { store ->
        val folder = store.getFolder(INBOX_FOLDER)
        val uidFolder = folder as? UIDFolder
          ?: error("IMAP server for ${account.emailAddress} does not support UIDFolder")

        try {
          folder.open(Folder.READ_WRITE)
        } catch (_: Exception) {
          folder.open(Folder.READ_ONLY)
        }

        try {
          val message = uidFolder.getMessageByUID(uid)
            ?: error("Message UID $uid not found in ${account.emailAddress}")

          val extractedBody = ExtractedBody()
          extractMimeContent(message, extractedBody)

          if (folder.mode == Folder.READ_WRITE) {
            runCatching { message.setFlag(Flags.Flag.SEEN, true) }
          }

          val summary = message.toEmailSummary(account, uid)
          val toList = message.getRecipients(Message.RecipientType.TO)
            ?.map { formatAddress(it) }
            .orEmpty()
          val ccList = message.getRecipients(Message.RecipientType.CC)
            ?.map { formatAddress(it) }
            .orEmpty()
          val replyTo = message.replyTo?.firstOrNull()?.let { extractEmailAddress(it) }
            ?: summary.fromAddress

          EmailDetail(
            summary = summary.copy(isUnread = false),
            toAddresses = toList,
            ccAddresses = ccList,
            replyToAddress = replyTo,
            inReplyToHeader = message.getHeader("In-Reply-To")?.firstOrNull(),
            referencesHeader = message.getHeader("References")?.firstOrNull(),
            plainTextBody = extractedBody.plainText?.trim(),
            htmlBody = extractedBody.htmlText?.trim(),
          )
        } finally {
          runCatching { folder.close(false) }
        }
      }
    }
  }

  override suspend fun sendEmail(draft: OutgoingEmailDraft): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        val account = draft.fromAccount
        val cleanPassword = account.appPassword.replace(" ", "")
        val props = Properties().apply {
          put("mail.smtp.auth", "true")
          put("mail.smtp.host", account.smtpHost.trim())
          put("mail.smtp.port", account.smtpPort.toString())
          put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS)
          put("mail.smtp.timeout", TIMEOUT_MILLIS)
          put("mail.smtp.writetimeout", TIMEOUT_MILLIS)
          if (account.smtpPort == 465) {
            put("mail.smtp.ssl.enable", "true")
          } else {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
          }
        }

        val session = Session.getInstance(
          props,
          object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
              PasswordAuthentication(account.emailAddress.trim(), cleanPassword)
          },
        )

        val mimeMessage = MimeMessage(session).apply {
          setFrom(
            InternetAddress(
              account.emailAddress.trim(),
              account.displayName.ifBlank { account.emailAddress.trim() },
              "UTF-8",
            ),
          )
          setRecipients(
            Message.RecipientType.TO,
            InternetAddress.parse(draft.to.trim(), false),
          )
          if (draft.cc.isNotBlank()) {
            setRecipients(
              Message.RecipientType.CC,
              InternetAddress.parse(draft.cc.trim(), false),
            )
          }
          setSubject(draft.subject, "UTF-8")
          sentDate = Date()

          if (!draft.inReplyToMessageId.isNullOrBlank()) {
            setHeader("In-Reply-To", draft.inReplyToMessageId)
          }
          if (!draft.referencesHeader.isNullOrBlank()) {
            setHeader("References", draft.referencesHeader)
          }

          setText(draft.body, "UTF-8")
        }

        Transport.send(mimeMessage)
      }
    }

  private fun fetchAccountFeed(
    account: EmailAccount,
    config: MailPolicyConfig,
    refreshTimeMillis: Long,
  ): List<EmailSummary> {
    val policy = config.feedPolicy
    openImapStore(account).use { store ->
      val folder = store.getFolder(INBOX_FOLDER)
      val uidFolder = folder as? UIDFolder ?: return emptyList()
      folder.open(Folder.READ_ONLY)
      try {
        val totalCount = folder.messageCount
        if (totalCount <= 0) return emptyList()

        // IMAP SINCE searches at day resolution; we narrow by calendar day on the server
        // and apply the exact FeedPolicy timestamp & custom filter in Kotlin.
        val cutoffMillis = refreshTimeMillis - policy.lookbackWindow.inWholeMilliseconds
        val sinceCalendar = Calendar.getInstance().apply {
          timeInMillis = cutoffMillis
          add(Calendar.DAY_OF_YEAR, -1)
        }
        val candidateMessages = folder.search(
          ReceivedDateTerm(ComparisonTerm.GE, sinceCalendar.time),
        ).takeLast(policy.maxMessagesPerAccount).toTypedArray()

        if (candidateMessages.isEmpty()) return emptyList()

        prefetchHeaders(folder, candidateMessages)

        return candidateMessages
          .mapNotNull { msg ->
            runCatching {
              val uid = uidFolder.getUID(msg)
              msg.toEmailSummary(account, uid)
            }.getOrNull()
          }
          .filter { summary -> config.evaluateFeedInclusion(summary, refreshTimeMillis) }
          .sortedByDescending { it.receivedAtEpochMillis }
      } finally {
        runCatching { folder.close(false) }
      }
    }
  }

  private fun searchAccountMessages(
    account: EmailAccount,
    query: String,
    maxPerAccount: Int,
  ): List<EmailSummary> {
    openImapStore(account).use { store ->
      val folder = store.getFolder(INBOX_FOLDER)
      val uidFolder = folder as? UIDFolder ?: return emptyList()
      folder.open(Folder.READ_ONLY)
      try {
        val searchTerm = OrTerm(
          arrayOf(
            SubjectTerm(query),
            FromStringTerm(query),
            BodyTerm(query),
          ),
        )
        val matchedMessages = folder.search(searchTerm)
          .takeLast(maxPerAccount)
          .toTypedArray()

        if (matchedMessages.isEmpty()) return emptyList()

        prefetchHeaders(folder, matchedMessages)

        return matchedMessages
          .mapNotNull { msg ->
            runCatching {
              val uid = uidFolder.getUID(msg)
              msg.toEmailSummary(account, uid)
            }.getOrNull()
          }
          .sortedByDescending { it.receivedAtEpochMillis }
      } finally {
        runCatching { folder.close(false) }
      }
    }
  }

  private fun prefetchHeaders(folder: Folder, messages: Array<Message>) {
    val profile = FetchProfile().apply {
      add(FetchProfile.Item.ENVELOPE)
      add(FetchProfile.Item.FLAGS)
      add(UIDFolder.FetchProfileItem.UID)
      add("Message-ID")
    }
    folder.fetch(messages, profile)
  }

  private fun openImapStore(account: EmailAccount): Store {
    val props = Properties().apply {
      put("mail.store.protocol", "imaps")
      put("mail.imaps.host", account.imapHost.trim())
      put("mail.imaps.port", account.imapPort.toString())
      put("mail.imaps.ssl.enable", account.useSsl.toString())
      put("mail.imaps.connectiontimeout", TIMEOUT_MILLIS)
      put("mail.imaps.timeout", TIMEOUT_MILLIS)
    }
    val session = Session.getInstance(props)
    val store = session.getStore("imaps")
    val cleanPassword = account.appPassword.replace(" ", "")
    store.connect(
      account.imapHost.trim(),
      account.imapPort,
      account.emailAddress.trim(),
      cleanPassword,
    )
    return store
  }

  private fun Message.toEmailSummary(account: EmailAccount, uid: Long): EmailSummary {
    val sender = from?.firstOrNull() as? InternetAddress
    val decodedFromName = sender?.personal?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
      ?.takeIf { it.isNotBlank() }
      ?: sender?.address
      ?: "Unknown Sender"
    val fromEmail = sender?.address ?: from?.firstOrNull()?.toString().orEmpty()
    val decodedSubject = subject?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
      ?.takeIf { it.isNotBlank() }
      ?: "(No Subject)"
    val timestamp = (receivedDate ?: sentDate)?.time ?: 0L
    val unread = !flags.contains(Flags.Flag.SEEN)

    return EmailSummary(
      uid = uid,
      accountId = account.id,
      accountEmail = account.emailAddress,
      accountDisplayName = account.displayName.ifBlank { account.emailAddress },
      messageIdHeader = getHeader("Message-ID")?.firstOrNull(),
      subject = decodedSubject,
      fromName = decodedFromName,
      fromAddress = fromEmail,
      receivedAtEpochMillis = timestamp,
      isUnread = unread,
    )
  }

  private class ExtractedBody(
    var plainText: String? = null,
    var htmlText: String? = null,
  )

  private fun extractMimeContent(part: Part, out: ExtractedBody) {
    val disposition = runCatching { part.disposition }.getOrNull()
    if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) {
      return
    }
    when {
      part.isMimeType("text/plain") && out.plainText == null -> {
        out.plainText = runCatching { part.content as? String }.getOrNull()
      }
      part.isMimeType("text/html") && out.htmlText == null -> {
        out.htmlText = runCatching { part.content as? String }.getOrNull()
      }
      part.isMimeType("multipart/*") -> {
        val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
        for (i in 0 until multipart.count) {
          extractMimeContent(multipart.getBodyPart(i), out)
        }
      }
    }
  }

  private fun formatAddress(address: Address): String {
    val internetAddress = address as? InternetAddress ?: return address.toString()
    val personal = internetAddress.personal
    return if (!personal.isNullOrBlank()) {
      "$personal <${internetAddress.address}>"
    } else {
      internetAddress.address
    }
  }

  private fun extractEmailAddress(address: Address): String =
    (address as? InternetAddress)?.address ?: address.toString()

  companion object {
    private const val INBOX_FOLDER = "INBOX"
    private const val TIMEOUT_MILLIS = "15000"
  }
}
