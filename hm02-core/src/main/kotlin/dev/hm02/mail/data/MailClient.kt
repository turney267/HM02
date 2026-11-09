package dev.hm02.mail.data

import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.model.sanitizeHeaderControlChars
import dev.hm02.mail.policy.MailPolicyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
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
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SubjectTerm

data class AccountSyncError(
  val accountEmail: String,
  val message: String,
)

data class FeedSyncResult(
  val items: List<EmailSummary>,
  val failedAccounts: List<AccountSyncError> = emptyList(),
)

/**
 * Parses quick day-range search shortcuts:
 * - `"a"` or `"a1"` -> `1` (all emails from today)
 * - `"a2"` -> `2` (all emails from yesterday and today)
 * Returns `null` for any query exceeding [maxDays] (defaults to `2`).
 */
fun parseQuickDaySearchDays(query: String, maxDays: Int = 2): Int? {
  if (maxDays <= 0) return null
  val trimmed = query.trim().lowercase(Locale.US)
  if (trimmed == "a") return 1.takeIf { it <= maxDays }
  val match = QUICK_DAY_SEARCH_REGEX.matchEntire(trimmed) ?: return null
  val parsed = match.groupValues[1].toIntOrNull() ?: return null
  return parsed.takeIf { it in 1..maxDays }
}

/**
 * Computes the local start-of-day (`00:00:00.000`) epoch timestamp for a [days]-day window:
 * - `days = 1` ("a"): today at `00:00:00.000`
 * - `days = 2` ("a2"): yesterday at `00:00:00.000` (yesterday + today)
 * - `days = N` ("aN"): `(N - 1)` days before today at `00:00:00.000`
 */
fun computeDayWindowStartMillis(
  days: Int,
  nowMillis: Long = System.currentTimeMillis(),
): Long {
  val calendar = Calendar.getInstance().apply {
    timeInMillis = nowMillis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    if (days > 1) {
      add(Calendar.DAY_OF_YEAR, -(days - 1))
    }
  }
  return calendar.timeInMillis
}

private val QUICK_DAY_SEARCH_REGEX = Regex("^a(\\d+)$", RegexOption.IGNORE_CASE)
private val DNS_LABEL_REGEX = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
private val SAFE_MESSAGE_ID_TOKEN_REGEX = Regex("^<[A-Za-z0-9._%+@-]+>$")
private val UNFOLDED_INJECTION_SPLIT_REGEX = Regex("\\n(?![ \\t])")
private val FOLDED_WSP_REGEX = Regex("\\n+[ \\t]+")
private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

internal const val MAX_MIME_DEPTH = 10
internal const val MAX_MIME_PARTS = 64
internal const val MAX_TEXT_BODY_BYTES = 1_048_576
private const val TIMEOUT_MILLIS = "15000"

/**
 * Validates that [rawHost] and [port] represent a safe external mail server endpoint,
 * blocking out-of-range ports, URI/control characters, mDNS/internal suffixes, and
 * loopback/private/link-local IPv4 or IPv6 literals.
 */
internal fun validateMailServerEndpoint(
  rawHost: String,
  port: Int,
  protocolLabel: String = "Mail",
) {
  require(port in 1..65535) { "$protocolLabel port must be between 1 and 65535." }
  for (ch in rawHost) {
    if (ch.code < 0x20 || ch.code == 0x7F) {
      throw IllegalArgumentException("$protocolLabel host contains invalid whitespace or control characters.")
    }
  }
  val host = rawHost.trim { it == ' ' }
  require(host.isNotEmpty()) { "$protocolLabel host cannot be empty." }

  for (ch in host) {
    if (ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F) {
      throw IllegalArgumentException("$protocolLabel host contains invalid whitespace or control characters.")
    }
    if (ch in "/\\?#@%\"'<>") {
      throw IllegalArgumentException("$protocolLabel host contains disallowed URI characters.")
    }
  }

  val lowerRaw = host.lowercase(Locale.US)
  val unbracketed = lowerRaw.removePrefix("[").removeSuffix("]")
  if (
    unbracketed == "::1" ||
    unbracketed == "::" ||
    unbracketed.startsWith("fe80:") ||
    unbracketed.startsWith("fc") && unbracketed.contains(':') ||
    unbracketed.startsWith("fd") && unbracketed.contains(':') ||
    lowerRaw.contains(':') ||
    lowerRaw.startsWith("[") ||
    lowerRaw.endsWith("]")
  ) {
    throw IllegalArgumentException("$protocolLabel host cannot be a loopback/private/link-local or raw IPv6 literal.")
  }

  val lower = lowerRaw.trimEnd('.')
  require(lower.isNotEmpty() && lower.contains('.') && !lower.startsWith('.') && !lower.contains("..")) {
    "$protocolLabel host must be a valid domain name."
  }

  if (
    lower == "localhost" ||
    lower.endsWith(".localhost") ||
    lower.endsWith(".local") ||
    lower.endsWith(".internal") ||
    lower.endsWith(".home.arpa")
  ) {
    throw IllegalArgumentException("$protocolLabel host cannot target local or internal networks.")
  }

  val labels = lower.split('.')
  if (!labels.all { DNS_LABEL_REGEX.matches(it) }) {
    throw IllegalArgumentException("$protocolLabel host contains invalid domain labels.")
  }

  val allNumericLabels = labels.size == 4 && labels.all { label -> label.all { it.isDigit() } }
  if (allNumericLabels) {
    if (labels.any { it.length > 1 && it.startsWith('0') }) {
      throw IllegalArgumentException("$protocolLabel IPv4 literal cannot contain octal leading zeros.")
    }
    val octets = labels.map { label ->
      label.toIntOrNull()?.takeIf { it in 0..255 }
        ?: throw IllegalArgumentException("$protocolLabel IPv4 address octet out of range.")
    }
    val o0 = octets[0]
    val o1 = octets[1]
    val isDisallowedIpv4 =
      o0 == 127 ||
        o0 == 10 ||
        (o0 == 192 && o1 == 168) ||
        (o0 == 172 && o1 in 16..31) ||
        (o0 == 169 && o1 == 254) ||
        o0 == 0 ||
        (o0 == 100 && o1 in 64..127) ||
        o0 >= 224
    if (isDisallowedIpv4) {
      throw IllegalArgumentException("$protocolLabel host cannot target loopback, private, or link-local IPv4 ranges.")
    }
  } else if (labels.last().all { it.isDigit() } || labels.any { it.startsWith("0x") }) {
    throw IllegalArgumentException("$protocolLabel host has an invalid numeric or hex TLD.")
  }
}

/**
 * Strips spaces only for built-in App Password presets (such as Gmail's 4x4 grouped App Passwords),
 * while preserving intentional spaces in custom IMAP/SMTP passwords.
 */
internal fun normalizePassword(
  password: String,
  preset: MailProviderPreset,
): String {
  val cleaned = password
    .replace("\r", "")
    .replace("\n", "")
    .replace("\u0000", "")
  return if (preset != MailProviderPreset.CUSTOM) {
    cleaned.replace(" ", "")
  } else {
    cleaned
  }
}

internal fun sanitizeSingleMessageId(raw: String?): String? {
  if (raw.isNullOrBlank()) return null
  val normalized = raw.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n')
  val truncated = normalized.split(UNFOLDED_INJECTION_SPLIT_REGEX, limit = 2).first()
  val unfolded = truncated.replace(FOLDED_WSP_REGEX, " ").replace("\n", "").trim()
  return unfolded
    .split(WHITESPACE_SPLIT_REGEX)
    .firstOrNull { SAFE_MESSAGE_ID_TOKEN_REGEX.matches(it) }
}

internal fun sanitizeReferencesHeader(raw: String?): String? {
  if (raw.isNullOrBlank()) return null
  val normalized = raw.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n')
  val truncated = normalized.split(UNFOLDED_INJECTION_SPLIT_REGEX, limit = 2).first()
  val unfolded = truncated.replace(FOLDED_WSP_REGEX, " ").replace("\n", "").trim()
  return unfolded
    .split(WHITESPACE_SPLIT_REGEX)
    .filter { SAFE_MESSAGE_ID_TOKEN_REGEX.matches(it) }
    .distinct()
    .takeLast(20)
    .joinToString(" ")
    .ifBlank { null }
}

internal fun buildImapProperties(account: EmailAccount): Properties {
  validateMailServerEndpoint(account.imapHost, account.imapPort, "IMAP")
  val host = account.imapHost.trim { it == ' ' }
  return Properties().apply {
    put("mail.store.protocol", "imaps")
    put("mail.imaps.host", host)
    put("mail.imaps.port", account.imapPort.toString())
    put("mail.imaps.ssl.enable", "true")
    put("mail.imaps.ssl.checkserveridentity", "true")
    put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
    put("mail.imaps.connectiontimeout", TIMEOUT_MILLIS)
    put("mail.imaps.timeout", TIMEOUT_MILLIS)
    put("mail.imaps.writetimeout", TIMEOUT_MILLIS)
    if (account.isOAuth2) {
      put("mail.imaps.auth.mechanisms", "XOAUTH2")
      put("mail.imaps.sasl.enable", "true")
      put("mail.imaps.sasl.mechanisms", "XOAUTH2")
      put("mail.imaps.auth.login.disable", "true")
      put("mail.imaps.auth.plain.disable", "true")
    }
  }
}

internal fun buildSmtpProperties(account: EmailAccount): Properties {
  validateMailServerEndpoint(account.smtpHost, account.smtpPort, "SMTP")
  val host = account.smtpHost.trim { it == ' ' }
  return Properties().apply {
    put("mail.smtp.auth", "true")
    put("mail.smtp.host", host)
    put("mail.smtp.port", account.smtpPort.toString())
    put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS)
    put("mail.smtp.timeout", TIMEOUT_MILLIS)
    put("mail.smtp.writetimeout", TIMEOUT_MILLIS)
    put("mail.smtp.ssl.checkserveridentity", "true")
    put("mail.smtps.ssl.checkserveridentity", "true")
    put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
    put("mail.smtps.ssl.protocols", "TLSv1.2 TLSv1.3")
    put("mail.smtp.localhost", "localhost")
    put("mail.smtps.localhost", "localhost")
    if (account.smtpPort == 465) {
      put("mail.smtp.ssl.enable", "true")
    } else {
      put("mail.smtp.starttls.enable", "true")
      put("mail.smtp.starttls.required", "true")
    }
    if (account.isOAuth2) {
      put("mail.smtp.auth.mechanisms", "XOAUTH2")
      put("mail.smtps.auth.mechanisms", "XOAUTH2")
      put("mail.smtp.sasl.enable", "true")
      put("mail.smtp.sasl.mechanisms", "XOAUTH2")
      put("mail.smtp.auth.login.disable", "true")
      put("mail.smtp.auth.plain.disable", "true")
    }
  }
}

internal class ExtractedBody(
  var plainText: String? = null,
  var htmlText: String? = null,
  var partsVisited: Int = 0,
)

internal fun extractMimeContent(
  part: Part,
  out: ExtractedBody,
  depth: Int = 0,
) {
  if (depth > MAX_MIME_DEPTH || out.partsVisited >= MAX_MIME_PARTS) return
  if (out.plainText != null && out.htmlText != null) return
  out.partsVisited++

  val disposition = runCatching { part.disposition }.getOrNull()
  if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) {
    return
  }

  when {
    part.isMimeType("text/plain") && out.plainText == null -> {
      out.plainText = readBoundedPartText(part, MAX_TEXT_BODY_BYTES)
    }
    part.isMimeType("text/html") && out.htmlText == null -> {
      out.htmlText = readBoundedPartText(part, MAX_TEXT_BODY_BYTES)
    }
    part.isMimeType("multipart/*") -> {
      if (depth >= MAX_MIME_DEPTH) return
      val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
      val count = runCatching { multipart.count }.getOrDefault(0)
      for (i in 0 until count) {
        if (out.partsVisited >= MAX_MIME_PARTS || (out.plainText != null && out.htmlText != null)) {
          break
        }
        val bodyPart = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
        extractMimeContent(bodyPart, out, depth + 1)
      }
    }
  }
}

private fun readBoundedPartText(part: Part, maxBytes: Int): String? {
  return runCatching {
    val charset = resolvePartCharset(part)
    part.inputStream.use { input ->
      val out = ByteArrayOutputStream(minOf(maxBytes, 8_192))
      val buffer = ByteArray(8_192)
      var total = 0
      while (total < maxBytes) {
        val toRead = minOf(buffer.size, maxBytes - total)
        val read = input.read(buffer, 0, toRead)
        if (read < 0) break
        if (read > 0) {
          out.write(buffer, 0, read)
          total += read
        }
      }
      String(out.toByteArray(), charset)
    }
  }.recoverCatching {
    (part.content as? String)?.let { str ->
      if (str.length > maxBytes) str.substring(0, maxBytes) else str
    }
  }.getOrNull()
}

private fun resolvePartCharset(part: Part): Charset {
  val rawContentType = runCatching { part.contentType }.getOrNull() ?: return Charsets.UTF_8
  val mimeCharset = runCatching { ContentType(rawContentType).getParameter("charset") }.getOrNull()
    ?: return Charsets.UTF_8
  val javaCharsetName = runCatching { MimeUtility.javaCharset(mimeCharset) }.getOrDefault(mimeCharset)
  return runCatching { Charset.forName(javaCharsetName) }.getOrDefault(Charsets.UTF_8)
}

/**
 * Abstraction for the bare-bones HM02 email operations:
 * 1. Fetch feed across multiple accounts according to [MailPolicyConfig], reporting partial or full errors
 * 2. Server-side search across multiple accounts (including `"a"` / `"a2"` day shortcuts)
 * 3. Read full email message by UID
 * 4. Send email (new, reply, forward)
 * 5. Flag/unflag message on IMAP server
 */
interface MailClient {
  suspend fun verifyAccount(account: EmailAccount): Result<Unit>
  suspend fun fetchFeed(accounts: List<EmailAccount>, config: MailPolicyConfig): FeedSyncResult
  suspend fun searchMessages(
    accounts: List<EmailAccount>,
    query: String,
    maxPerAccount: Int = 50,
    maxSearchDays: Int = 2,
  ): Result<List<EmailSummary>>
  suspend fun fetchMessageDetail(account: EmailAccount, uid: Long): Result<EmailDetail>
  suspend fun prefetchMessages(account: EmailAccount, uids: List<Long>): Map<Long, EmailDetail> = emptyMap()
  suspend fun markMessageRead(account: EmailAccount, uid: Long): Result<Unit> = Result.success(Unit)
  suspend fun sendEmail(draft: OutgoingEmailDraft): Result<Unit>
  suspend fun setMessageFlagged(account: EmailAccount, uid: Long, flagged: Boolean): Result<Unit>
}

/**
 * Pure IMAP + SMTP implementation of [MailClient] using Jakarta Mail / JavaMail on Android.
 * Performs foreground-only network requests with zero background services or push notifications.
 */
class ImapSmtpMailClient(
  private val onAccountTokenUpdated: ((EmailAccount) -> Unit)? = null,
) : MailClient {

  private data class CachedOAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
  )

  private val oauthTokenCache = ConcurrentHashMap<String, CachedOAuthToken>()

  override suspend fun verifyAccount(account: EmailAccount): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        validateMailServerEndpoint(account.smtpHost, account.smtpPort, "SMTP")
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
  ): FeedSyncResult = withContext(Dispatchers.IO) {
    if (accounts.isEmpty()) return@withContext FeedSyncResult(emptyList())

    val refreshTimeMillis = System.currentTimeMillis()
    val perAccountOutcomes = coroutineScope {
      accounts.map { account ->
        async {
          account.emailAddress to runCatching {
            fetchAccountFeed(account, config, refreshTimeMillis)
          }
        }
      }.awaitAll()
    }

    val allItems = mutableListOf<EmailSummary>()
    val failures = mutableListOf<AccountSyncError>()

    for ((emailAddr, outcome) in perAccountOutcomes) {
      outcome.fold(
        onSuccess = { items -> allItems.addAll(items) },
        onFailure = { err ->
          failures.add(
            AccountSyncError(
              accountEmail = emailAddr,
              message = err.localizedMessage ?: "Connection failed",
            ),
          )
        },
      )
    }

    FeedSyncResult(
      items = allItems.sortedByDescending { it.receivedAtEpochMillis },
      failedAccounts = failures,
    )
  }

  override suspend fun searchMessages(
    accounts: List<EmailAccount>,
    query: String,
    maxPerAccount: Int,
    maxSearchDays: Int,
  ): Result<List<EmailSummary>> = withContext(Dispatchers.IO) {
    val trimmedQuery = sanitizeHeaderControlChars(query).take(256)
    if (trimmedQuery.isEmpty() || accounts.isEmpty()) {
      return@withContext Result.success(emptyList())
    }

    runCatching {
      val perAccountResults = coroutineScope {
        accounts.map { account ->
          async {
            runCatching {
              searchAccountMessages(account, trimmedQuery, maxPerAccount, maxSearchDays)
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

          prefetchDetailProfile(folder, arrayOf(message))

          val detail = message.toEmailDetail(account, uid, markSummaryRead = true)

          if (folder.mode == Folder.READ_WRITE) {
            runCatching { message.setFlag(Flags.Flag.SEEN, true) }
          }

          detail
        } finally {
          runCatching { folder.close(false) }
        }
      }
    }
  }

  override suspend fun prefetchMessages(
    account: EmailAccount,
    uids: List<Long>,
  ): Map<Long, EmailDetail> = withContext(Dispatchers.IO) {
    if (uids.isEmpty()) return@withContext emptyMap()
    runCatching {
      openImapStore(account).use { store ->
        val folder = store.getFolder(INBOX_FOLDER)
        val uidFolder = folder as? UIDFolder ?: return@runCatching emptyMap()
        folder.open(Folder.READ_ONLY)
        try {
          val messages = uidFolder.getMessagesByUID(uids.toLongArray())
            ?.filterNotNull()
            ?.toTypedArray()
            ?: return@runCatching emptyMap()
          if (messages.isEmpty()) return@runCatching emptyMap()

          prefetchDetailProfile(folder, messages)

          val result = LinkedHashMap<Long, EmailDetail>(messages.size)
          for (msg in messages) {
            runCatching {
              val uid = uidFolder.getUID(msg)
              if (uid > 0L) {
                result[uid] = msg.toEmailDetail(account, uid, markSummaryRead = false)
              }
            }
          }
          result
        } finally {
          runCatching { folder.close(false) }
        }
      }
    }.getOrDefault(emptyMap())
  }

  override suspend fun markMessageRead(
    account: EmailAccount,
    uid: Long,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      openImapStore(account).use { store ->
        val folder = store.getFolder(INBOX_FOLDER)
        val uidFolder = folder as? UIDFolder ?: return@runCatching
        folder.open(Folder.READ_WRITE)
        try {
          val message = uidFolder.getMessageByUID(uid) ?: return@runCatching
          message.setFlag(Flags.Flag.SEEN, true)
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
        val authSecret = resolveAuthSecret(account)
        val props = buildSmtpProperties(account)
        val senderEmail = sanitizeHeaderControlChars(account.emailAddress)
        val senderName = sanitizeHeaderControlChars(account.displayName).ifBlank { senderEmail }

        val session = Session.getInstance(
          props,
          object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
              PasswordAuthentication(senderEmail, authSecret)
          },
        )

        val mimeMessage = MimeMessage(session).apply {
          setFrom(
            InternetAddress(
              senderEmail,
              senderName,
              "UTF-8",
            ),
          )
          setRecipients(
            Message.RecipientType.TO,
            InternetAddress.parse(sanitizeHeaderControlChars(draft.to), false),
          )
          val cleanCc = sanitizeHeaderControlChars(draft.cc)
          if (cleanCc.isNotBlank()) {
            setRecipients(
              Message.RecipientType.CC,
              InternetAddress.parse(cleanCc, false),
            )
          }
          setSubject(sanitizeHeaderControlChars(draft.subject), "UTF-8")
          sentDate = Date()

          val safeInReplyTo = sanitizeSingleMessageId(draft.inReplyToMessageId)
          if (safeInReplyTo != null) {
            setHeader("In-Reply-To", safeInReplyTo)
          }
          val safeReferences = sanitizeReferencesHeader(draft.referencesHeader)
          if (safeReferences != null) {
            setHeader("References", safeReferences)
          }

          setText(draft.body, "UTF-8")
        }

        Transport.send(mimeMessage)
      }
    }

  override suspend fun setMessageFlagged(
    account: EmailAccount,
    uid: Long,
    flagged: Boolean,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      openImapStore(account).use { store ->
        val folder = store.getFolder(INBOX_FOLDER)
        val uidFolder = folder as? UIDFolder ?: return@runCatching
        folder.open(Folder.READ_WRITE)
        try {
          val message = uidFolder.getMessageByUID(uid) ?: return@runCatching
          message.setFlag(Flags.Flag.FLAGGED, flagged)
        } finally {
          runCatching { folder.close(false) }
        }
      }
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
    maxSearchDays: Int,
  ): List<EmailSummary> {
    openImapStore(account).use { store ->
      val folder = store.getFolder(INBOX_FOLDER)
      val uidFolder = folder as? UIDFolder ?: return emptyList()
      folder.open(Folder.READ_ONLY)
      try {
        val quickDays = parseQuickDaySearchDays(query, maxDays = maxSearchDays)
        if (quickDays != null) {
          val windowStartMillis = computeDayWindowStartMillis(quickDays)
          // Subtract 24h for the coarse IMAP SINCE search to guard against server timezone differences,
          // then apply exact local-time filtering on receivedAtEpochMillis.
          val imapSinceDate = Date(windowStartMillis - 86_400_000L)
          val candidateMessages = folder.search(
            ReceivedDateTerm(ComparisonTerm.GE, imapSinceDate),
          ).takeLast(maxOf(maxPerAccount, 100)).toTypedArray()

          if (candidateMessages.isEmpty()) return emptyList()

          prefetchHeaders(folder, candidateMessages)

          return candidateMessages
            .mapNotNull { msg ->
              runCatching {
                val uid = uidFolder.getUID(msg)
                msg.toEmailSummary(account, uid)
              }.getOrNull()
            }
            .filter { it.receivedAtEpochMillis >= windowStartMillis }
            .sortedByDescending { it.receivedAtEpochMillis }
        }

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

  private fun prefetchDetailProfile(folder: Folder, messages: Array<Message>) {
    val profile = FetchProfile().apply {
      add(FetchProfile.Item.ENVELOPE)
      add(FetchProfile.Item.FLAGS)
      add(FetchProfile.Item.CONTENT_INFO)
      add(UIDFolder.FetchProfileItem.UID)
      add("Message-ID")
      add("In-Reply-To")
      add("References")
    }
    runCatching { folder.fetch(messages, profile) }
  }

  private fun Message.toEmailDetail(
    account: EmailAccount,
    uid: Long,
    markSummaryRead: Boolean,
  ): EmailDetail {
    val extractedBody = ExtractedBody()
    extractMimeContent(this, extractedBody)

    val summary = toEmailSummary(account, uid)
    val toList = getRecipients(Message.RecipientType.TO)
      ?.map { formatAddress(it) }
      .orEmpty()
    val ccList = getRecipients(Message.RecipientType.CC)
      ?.map { formatAddress(it) }
      .orEmpty()
    val replyTo = summary.replyToAddress ?: summary.fromAddress

    return EmailDetail(
      summary = if (markSummaryRead) summary.copy(isUnread = false) else summary,
      toAddresses = toList,
      ccAddresses = ccList,
      replyToAddress = replyTo,
      inReplyToHeader = sanitizeSingleMessageId(getHeader("In-Reply-To")?.firstOrNull()),
      referencesHeader = sanitizeReferencesHeader(getHeader("References")?.firstOrNull()),
      plainTextBody = extractedBody.plainText?.trim(),
      htmlBody = extractedBody.htmlText?.trim(),
    )
  }

  private fun openImapStore(account: EmailAccount): Store {
    val props = buildImapProperties(account)
    val session = Session.getInstance(props)
    val store = session.getStore("imaps")
    val authSecret = resolveAuthSecret(account)
    store.connect(
      account.imapHost.trim(),
      account.imapPort,
      sanitizeHeaderControlChars(account.emailAddress),
      authSecret,
    )
    return store
  }

  private fun resolveAuthSecret(account: EmailAccount): String {
    if (!account.isOAuth2) {
      return normalizePassword(account.appPassword, account.effectivePreset)
    }
    val now = System.currentTimeMillis()
    val cached = oauthTokenCache[account.id]
    if (cached != null && cached.accessToken.isNotBlank() && cached.expiresAtEpochMillis > now + 60_000L) {
      return cached.accessToken
    }
    val storedAccess = account.oauthAccessToken
    if (!storedAccess.isNullOrBlank() && account.oauthTokenExpiresAtMillis > now + 60_000L) {
      oauthTokenCache[account.id] = CachedOAuthToken(
        accessToken = storedAccess,
        refreshToken = account.oauthRefreshToken.orEmpty(),
        expiresAtEpochMillis = account.oauthTokenExpiresAtMillis,
      )
      return storedAccess
    }
    val refreshToken = cached?.refreshToken?.takeIf { it.isNotBlank() }
      ?: account.oauthRefreshToken?.takeIf { it.isNotBlank() }
      ?: return storedAccess ?: normalizePassword(account.appPassword, account.effectivePreset)

    val refreshed = runBlocking {
      MicrosoftOAuthHelper.refreshAccessToken(refreshToken)
    }.getOrThrow()

    oauthTokenCache[account.id] = CachedOAuthToken(
      accessToken = refreshed.accessToken,
      refreshToken = refreshed.refreshToken,
      expiresAtEpochMillis = refreshed.expiresAtEpochMillis,
    )
    val updatedAccount = account.copy(
      oauthAccessToken = refreshed.accessToken,
      oauthRefreshToken = refreshed.refreshToken,
      oauthTokenExpiresAtMillis = refreshed.expiresAtEpochMillis,
    )
    runCatching { onAccountTokenUpdated?.invoke(updatedAccount) }
    return refreshed.accessToken
  }

  private fun Message.toEmailSummary(account: EmailAccount, uid: Long): EmailSummary {
    val sender = from?.firstOrNull() as? InternetAddress
    val decodedFromName = sender?.personal?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
      ?.let(::sanitizeHeaderControlChars)
      ?.takeIf { it.isNotBlank() }
      ?: sender?.address?.let(::sanitizeHeaderControlChars)
      ?: "Unknown Sender"
    val fromEmail = sanitizeHeaderControlChars(
      sender?.address ?: from?.firstOrNull()?.toString().orEmpty(),
    )
    val replyToEmail = runCatching {
      replyTo?.firstOrNull()?.let { extractEmailAddress(it) }
    }.getOrNull()
      ?.let(::sanitizeHeaderControlChars)
      ?.takeIf { it.isNotBlank() }
    val decodedSubject = subject?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
      ?.let(::sanitizeHeaderControlChars)
      ?.takeIf { it.isNotBlank() }
      ?: "(No Subject)"
    val timestamp = (receivedDate ?: sentDate)?.time ?: 0L
    val unread = !flags.contains(Flags.Flag.SEEN)
    val flagged = flags.contains(Flags.Flag.FLAGGED)

    return EmailSummary(
      uid = uid,
      accountId = account.id,
      accountEmail = account.emailAddress,
      accountDisplayName = account.displayName.ifBlank { account.emailAddress },
      messageIdHeader = sanitizeSingleMessageId(getHeader("Message-ID")?.firstOrNull()),
      subject = decodedSubject,
      fromName = decodedFromName,
      fromAddress = fromEmail,
      receivedAtEpochMillis = timestamp,
      isUnread = unread,
      isFlagged = flagged,
      replyToAddress = replyToEmail,
    )
  }

  private fun formatAddress(address: Address): String {
    val internetAddress = address as? InternetAddress ?: return sanitizeHeaderControlChars(address.toString())
    val personal = internetAddress.personal?.let(::sanitizeHeaderControlChars)
    val addr = sanitizeHeaderControlChars(internetAddress.address.orEmpty())
    return if (!personal.isNullOrBlank()) {
      "$personal <$addr>"
    } else {
      addr
    }
  }

  private fun extractEmailAddress(address: Address): String =
    sanitizeHeaderControlChars((address as? InternetAddress)?.address ?: address.toString())

  companion object {
    private const val INBOX_FOLDER = "INBOX"
  }
}
