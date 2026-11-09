package dev.hm02.mail.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.data.AccountSyncError
import dev.hm02.mail.data.ImapSmtpMailClient
import dev.hm02.mail.data.MailClient
import dev.hm02.mail.data.MicrosoftOAuthHelper
import dev.hm02.mail.data.SettingsAndContactsStore
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.MailAuthType
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.model.UserSettings
import dev.hm02.mail.model.sanitizeHeaderControlChars
import dev.hm02.mail.policy.MailPolicyConfig
import dev.hm02.mail.policy.MailPolicyProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

internal data class MailtoDraftFields(
  val to: String = "",
  val cc: String = "",
  val subject: String = "",
  val body: String = "",
)

internal fun parseMailtoUri(rawUri: String): MailtoDraftFields? {
  val trimmed = rawUri.trim()
  if (!trimmed.startsWith("mailto:", ignoreCase = true)) return null
  val withoutScheme = trimmed.substring("mailto:".length).substringBefore('#')
  val questionIdx = withoutScheme.indexOf('?')
  val rawPathRecipients = if (questionIdx >= 0) withoutScheme.substring(0, questionIdx) else withoutScheme
  val rawQuery = if (questionIdx >= 0 && questionIdx < withoutScheme.lastIndex) {
    withoutScheme.substring(questionIdx + 1)
  } else {
    ""
  }

  fun decodePercent(component: String, decodePlusAsSpace: Boolean): String {
    val prepared = if (decodePlusAsSpace) component else component.replace("+", "%2B")
    return runCatching { URLDecoder.decode(prepared, "UTF-8") }.getOrDefault(component)
  }

  val toParts = mutableListOf<String>()
  val decodedPathTo = sanitizeHeaderControlChars(decodePercent(rawPathRecipients, decodePlusAsSpace = false))
  if (decodedPathTo.isNotBlank()) {
    toParts.add(decodedPathTo)
  }

  val ccParts = mutableListOf<String>()
  var subject = ""
  var body = ""

  if (rawQuery.isNotBlank()) {
    for (pair in rawQuery.split('&')) {
      if (pair.isBlank()) continue
      val eqIdx = pair.indexOf('=')
      val key = (if (eqIdx >= 0) pair.substring(0, eqIdx) else pair).lowercase(Locale.US)
      val rawVal = if (eqIdx >= 0 && eqIdx < pair.lastIndex) pair.substring(eqIdx + 1) else ""
      val decodedVal = decodePercent(rawVal, decodePlusAsSpace = false)
      when (key) {
        "to" -> {
          val cleaned = sanitizeHeaderControlChars(decodedVal)
          if (cleaned.isNotBlank()) toParts.add(cleaned)
        }
        "cc" -> {
          val cleaned = sanitizeHeaderControlChars(decodedVal)
          if (cleaned.isNotBlank()) ccParts.add(cleaned)
        }
        "subject" -> subject = sanitizeHeaderControlChars(decodedVal)
        "body" -> body = decodedVal.replace("\u0000", "")
      }
    }
  }

  return MailtoDraftFields(
    to = toParts.joinToString(", "),
    cc = ccParts.joinToString(", "),
    subject = subject,
    body = body,
  )
}

sealed interface ActivePane {
  data object Feed : ActivePane
  data class SearchResults(val query: String) : ActivePane
  data class Reader(
    val summary: EmailSummary,
    val detail: EmailDetail?,
    val isLoading: Boolean,
    val error: String?,
    val returnSearchQuery: String? = null,
  ) : ActivePane
  data class Composer(val initialDraft: OutgoingEmailDraft) : ActivePane
  data object Accounts : ActivePane
  data object Settings : ActivePane
}

data class HM02UiState(
  val brandTitle: String = "HM02",
  val policyName: String = "Recent (24h)",
  val policyEmptyDescription: String = "",
  val blockRemoteMediaInEmails: Boolean = true,
  val blockExternalImagesByDefault: Boolean = true,
  val forceBlockExternalImages: Boolean = false,
  val allowExternalLinks: Boolean = true,
  val maxSearchDays: Int = 2,
  val allowCustomServers: Boolean = true,
  val settings: UserSettings = UserSettings(),
  val hasContactsPermission: Boolean = false,
  val savedContactsCount: Int = 0,
  val flaggedEmailsCount: Int = 0,
  val contactSuggestions: List<ContactSuggestion> = emptyList(),
  val searchSuggestions: List<ContactSuggestion> = emptyList(),
  val accounts: List<EmailAccount> = emptyList(),
  val feedItems: List<EmailSummary> = emptyList(),
  val failedAccountSyncs: List<AccountSyncError> = emptyList(),
  val searchResults: List<EmailSummary> = emptyList(),
  val searchQuery: String = "",
  val isRefreshingFeed: Boolean = false,
  val isSearching: Boolean = false,
  val isSendingEmail: Boolean = false,
  val isVerifyingAccount: Boolean = false,
  val lastRefreshedAtMillis: Long? = null,
  val statusMessage: String? = null,
  val activePane: ActivePane = ActivePane.Feed,
)

class HM02ViewModel(
  private val accountStore: AccountStore,
  private val settingsAndContactsStore: SettingsAndContactsStore,
  private val mailClient: MailClient = ImapSmtpMailClient(
    onAccountTokenUpdated = accountStore::addOrUpdateAccount,
  ),
  initialPolicyConfig: MailPolicyConfig = MailPolicyProvider.getConfig(),
) : ViewModel() {

  @Volatile
  private var policyConfig: MailPolicyConfig = initialPolicyConfig

  private var latestSyncedFeedItems: List<EmailSummary> = emptyList()

  private val messageDetailCache: MutableMap<String, EmailDetail> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, EmailDetail>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EmailDetail>?): Boolean =
          size > MAX_CACHED_EMAIL_DETAILS
      },
    )

  private val _uiState = MutableStateFlow(
    HM02UiState(
      brandTitle = policyConfig.brandTitle,
      policyName = policyConfig.feedPolicy.name,
      policyEmptyDescription = policyConfig.feedPolicy.emptyStateDescription,
      blockRemoteMediaInEmails = policyConfig.blockRemoteMediaInEmails,
      blockExternalImagesByDefault = policyConfig.blockExternalImagesByDefault,
      forceBlockExternalImages = policyConfig.forceBlockExternalImages,
      allowExternalLinks = policyConfig.allowExternalLinks,
      maxSearchDays = policyConfig.maxSearchDays,
      allowCustomServers = policyConfig.allowCustomServers,
      settings = settingsAndContactsStore.settings.value,
      hasContactsPermission = settingsAndContactsStore.hasContactsPermission(),
      savedContactsCount = settingsAndContactsStore.savedContacts.value.size,
      flaggedEmailsCount = settingsAndContactsStore.flaggedEmails.value.size,
      accounts = accountStore.accounts.value,
    ),
  )
  val uiState: StateFlow<HM02UiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      accountStore.accounts.collect { updatedAccounts ->
        _uiState.update { state ->
          state.copy(accounts = updatedAccounts)
        }
      }
    }
    viewModelScope.launch {
      settingsAndContactsStore.settings.collect { updatedSettings ->
        _uiState.update { state ->
          state.copy(
            settings = updatedSettings,
            feedItems = mergeFeedWithFlagged(
              syncedItems = latestSyncedFeedItems,
              flaggedItems = settingsAndContactsStore.flaggedEmails.value,
              enablePinToFeed = updatedSettings.enablePinToFeed,
            ),
          )
        }
      }
    }
    viewModelScope.launch {
      settingsAndContactsStore.flaggedEmails.collect { flaggedList ->
        _uiState.update { state ->
          state.copy(
            flaggedEmailsCount = flaggedList.size,
            feedItems = mergeFeedWithFlagged(
              syncedItems = latestSyncedFeedItems,
              flaggedItems = flaggedList,
              enablePinToFeed = state.settings.enablePinToFeed,
            ),
          )
        }
      }
    }
    viewModelScope.launch {
      settingsAndContactsStore.savedContacts.collect { contacts ->
        _uiState.update { state ->
          state.copy(savedContactsCount = contacts.size)
        }
      }
    }
  }

  fun updatePolicyConfig(newConfig: MailPolicyConfig) {
    val previous = policyConfig
    policyConfig = newConfig
    _uiState.update { state ->
      state.copy(
        brandTitle = newConfig.brandTitle,
        policyName = newConfig.feedPolicy.name,
        policyEmptyDescription = newConfig.feedPolicy.emptyStateDescription,
        blockRemoteMediaInEmails = newConfig.blockRemoteMediaInEmails,
        blockExternalImagesByDefault = newConfig.blockExternalImagesByDefault,
        forceBlockExternalImages = newConfig.forceBlockExternalImages,
        allowExternalLinks = newConfig.allowExternalLinks,
        maxSearchDays = newConfig.maxSearchDays,
        allowCustomServers = newConfig.allowCustomServers,
      )
    }
    if (previous != newConfig && accountStore.accounts.value.isNotEmpty()) {
      refreshFeed()
    }
  }

  /**
   * Called automatically every time the user opens/resumes the app (ON_START)
   * or taps the manual refresh icon.
   */
  fun refreshFeed() {
    refreshContactsPermissionState()
    val accounts = accountStore.accounts.value
    if (accounts.isEmpty()) {
      latestSyncedFeedItems = emptyList()
      _uiState.update {
        it.copy(
          feedItems = emptyList(),
          failedAccountSyncs = emptyList(),
          isRefreshingFeed = false,
          lastRefreshedAtMillis = System.currentTimeMillis(),
        )
      }
      return
    }

    val activeConfig = policyConfig
    viewModelScope.launch {
      _uiState.update { it.copy(isRefreshingFeed = true, statusMessage = null) }
      val syncResult = mailClient.fetchFeed(accounts, activeConfig)
      latestSyncedFeedItems = syncResult.items
      val failures = syncResult.failedAccounts
      val errorNotice = when {
        failures.isEmpty() -> null
        failures.size == accounts.size ->
          "Sync failed for all accounts (${failures.first().accountEmail}: ${failures.first().message})"
        else ->
          "Partial sync error (${failures.joinToString { it.accountEmail }})"
      }

      val mergedFeed = mergeFeedWithFlagged(
        syncedItems = latestSyncedFeedItems,
        flaggedItems = settingsAndContactsStore.flaggedEmails.value,
        enablePinToFeed = _uiState.value.settings.enablePinToFeed,
      )

      _uiState.update { state ->
        state.copy(
          feedItems = mergeFeedWithFlagged(
            syncedItems = latestSyncedFeedItems,
            flaggedItems = settingsAndContactsStore.flaggedEmails.value,
            enablePinToFeed = state.settings.enablePinToFeed,
          ),
          failedAccountSyncs = failures,
          isRefreshingFeed = false,
          lastRefreshedAtMillis = System.currentTimeMillis(),
          statusMessage = errorNotice,
        )
      }

      prefetchSummariesInBackground(mergedFeed)
    }
  }

  fun updateSearchQuery(query: String) {
    val suggestions = settingsAndContactsStore.querySenderSearchSuggestions(
      rawQuery = query,
      maxSearchDays = policyConfig.maxSearchDays,
    )
    _uiState.update {
      it.copy(
        searchQuery = query,
        searchSuggestions = suggestions,
      )
    }
  }

  fun selectSearchSuggestion(suggestion: ContactSuggestion) {
    _uiState.update {
      it.copy(
        searchQuery = suggestion.emailAddress,
        searchSuggestions = emptyList(),
      )
    }
    executeSearch()
  }

  fun executeSearch() {
    val query = _uiState.value.searchQuery.trim()
    val accounts = accountStore.accounts.value
    if (query.isEmpty()) {
      clearSearchAndReturnToFeed()
      return
    }
    if (accounts.isEmpty()) {
      _uiState.update { it.copy(statusMessage = "Add an email account first to search.") }
      return
    }

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isSearching = true,
          searchResults = emptyList(),
          searchSuggestions = emptyList(),
          activePane = ActivePane.SearchResults(query),
          statusMessage = null,
        )
      }
      val result = mailClient.searchMessages(
        accounts = accounts,
        query = query,
        maxSearchDays = policyConfig.maxSearchDays,
      )
      result.fold(
        onSuccess = { items ->
          val flaggedKeys = settingsAndContactsStore.flaggedEmails.value
            .map { it.compositeKey }
            .toSet()
          val decoratedItems = items.map { item ->
            item.copy(isFlagged = item.isFlagged || item.compositeKey in flaggedKeys)
          }
          _uiState.update {
            it.copy(
              searchResults = decoratedItems,
              isSearching = false,
            )
          }
          prefetchSummariesInBackground(decoratedItems.take(MAX_SEARCH_PREFETCH_ITEMS))
        },
        onFailure = { error ->
          _uiState.update {
            it.copy(
              isSearching = false,
              statusMessage = "Search failed: ${error.localizedMessage ?: "Unknown error"}",
            )
          }
        },
      )
    }
  }

  fun clearSearchAndReturnToFeed() {
    _uiState.update {
      it.copy(
        searchQuery = "",
        searchSuggestions = emptyList(),
        searchResults = emptyList(),
        activePane = ActivePane.Feed,
      )
    }
  }

  fun closeReader() {
    val currentReader = _uiState.value.activePane as? ActivePane.Reader
    val returnQuery = currentReader?.returnSearchQuery
    if (!returnQuery.isNullOrBlank()) {
      _uiState.update {
        it.copy(
          activePane = ActivePane.SearchResults(returnQuery),
          contactSuggestions = emptyList(),
          searchSuggestions = emptyList(),
        )
      }
    } else {
      showFeed()
    }
  }

  fun showFeed() {
    _uiState.update {
      it.copy(
        activePane = ActivePane.Feed,
        contactSuggestions = emptyList(),
        searchSuggestions = emptyList(),
      )
    }
  }

  fun showAccounts() {
    _uiState.update { it.copy(activePane = ActivePane.Accounts, statusMessage = null) }
  }

  fun showSettings() {
    refreshContactsPermissionState()
    _uiState.update { it.copy(activePane = ActivePane.Settings, statusMessage = null) }
  }

  fun refreshContactsPermissionState() {
    val granted = settingsAndContactsStore.hasContactsPermission()
    _uiState.update { it.copy(hasContactsPermission = granted) }
  }

  fun updateAccessDeviceContacts(enabled: Boolean) {
    settingsAndContactsStore.updateSettings { it.copy(accessDeviceContacts = enabled) }
    refreshContactsPermissionState()
  }

  fun updateRememberSenders(enabled: Boolean) {
    settingsAndContactsStore.updateSettings { it.copy(rememberSenders = enabled) }
    if (!enabled) {
      _uiState.update { it.copy(searchSuggestions = emptyList()) }
    }
  }

  fun updateEnablePinToFeed(enabled: Boolean) {
    settingsAndContactsStore.updateSettings { it.copy(enablePinToFeed = enabled) }
  }

  fun toggleFlagged(email: EmailSummary) {
    val nowFlagged = settingsAndContactsStore.toggleFlaggedEmail(email)
    latestSyncedFeedItems = latestSyncedFeedItems.map {
      if (it.compositeKey == email.compositeKey) it.copy(isFlagged = nowFlagged) else it
    }
    messageDetailCache[email.compositeKey]?.let { cached ->
      messageDetailCache[email.compositeKey] = cached.copy(
        summary = cached.summary.copy(isFlagged = nowFlagged),
      )
    }
    _uiState.update { state ->
      val updatedPane = when (val pane = state.activePane) {
        is ActivePane.Reader -> {
          if (pane.summary.compositeKey == email.compositeKey) {
            val updatedSummary = pane.summary.copy(isFlagged = nowFlagged)
            pane.copy(
              summary = updatedSummary,
              detail = pane.detail?.copy(summary = updatedSummary),
            )
          } else {
            pane
          }
        }
        else -> pane
      }
      state.copy(
        feedItems = mergeFeedWithFlagged(
          syncedItems = latestSyncedFeedItems,
          flaggedItems = settingsAndContactsStore.flaggedEmails.value,
          enablePinToFeed = state.settings.enablePinToFeed,
        ),
        searchResults = state.searchResults.map {
          if (it.compositeKey == email.compositeKey) it.copy(isFlagged = nowFlagged) else it
        },
        activePane = updatedPane,
        statusMessage = if (nowFlagged) "Pinned to feed" else "Unpinned from feed",
      )
    }

    val account = accountStore.accounts.value.find { it.id == email.accountId } ?: return
    viewModelScope.launch {
      mailClient.setMessageFlagged(account, email.uid, nowFlagged)
    }
  }

  fun saveSenderContact(summary: EmailSummary) {
    settingsAndContactsStore.addSavedContacts(
      listOf(
        ContactSuggestion(
          name = summary.fromName.takeIf { !it.equals(summary.fromAddress, ignoreCase = true) }.orEmpty(),
          emailAddress = summary.fromAddress,
          source = "Email",
        ),
      ),
    )
    _uiState.update {
      it.copy(statusMessage = "Saved ${summary.fromName} <${summary.fromAddress}> to contacts")
    }
  }

  fun updateContactAutocompleteQuery(rawInput: String) {
    viewModelScope.launch {
      val suggestions = settingsAndContactsStore.queryContactSuggestions(rawInput)
      _uiState.update { it.copy(contactSuggestions = suggestions) }
    }
  }

  fun clearContactSuggestions() {
    _uiState.update { it.copy(contactSuggestions = emptyList()) }
  }

  fun openMessage(summary: EmailSummary) {
    val account = accountStore.accounts.value.find { it.id == summary.accountId } ?: return
    val isCurrentlyFlagged = summary.isFlagged || settingsAndContactsStore.isEmailFlagged(summary.compositeKey)
    val hydratedSummary = summary.copy(isFlagged = isCurrentlyFlagged)
    val returnSearchQuery = when (val currentPane = _uiState.value.activePane) {
      is ActivePane.SearchResults -> currentPane.query
      is ActivePane.Reader -> currentPane.returnSearchQuery
      else -> null
    }

    val cachedDetail = messageDetailCache[summary.compositeKey]
    if (cachedDetail != null) {
      settingsAndContactsStore.markFlaggedEmailRead(summary.compositeKey)
      latestSyncedFeedItems = latestSyncedFeedItems.map {
        if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
      }
      val flaggedNow = cachedDetail.summary.isFlagged || isCurrentlyFlagged
      val updatedSummary = cachedDetail.summary.copy(isUnread = false, isFlagged = flaggedNow)
      val updatedDetail = cachedDetail.copy(summary = updatedSummary)
      messageDetailCache[summary.compositeKey] = updatedDetail

      _uiState.update { state ->
        state.copy(
          feedItems = mergeFeedWithFlagged(
            syncedItems = latestSyncedFeedItems,
            flaggedItems = settingsAndContactsStore.flaggedEmails.value,
            enablePinToFeed = state.settings.enablePinToFeed,
          ),
          searchResults = state.searchResults.map {
            if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
          },
          activePane = ActivePane.Reader(
            summary = updatedSummary,
            detail = updatedDetail,
            isLoading = false,
            error = null,
            returnSearchQuery = returnSearchQuery,
          ),
        )
      }

      if (summary.isUnread) {
        viewModelScope.launch {
          mailClient.markMessageRead(account, summary.uid)
        }
      }
      return
    }

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          activePane = ActivePane.Reader(
            summary = hydratedSummary,
            detail = null,
            isLoading = true,
            error = null,
            returnSearchQuery = returnSearchQuery,
          ),
        )
      }
      val result = mailClient.fetchMessageDetail(account, summary.uid)
      result.fold(
        onSuccess = { detail ->
          settingsAndContactsStore.markFlaggedEmailRead(summary.compositeKey)
          latestSyncedFeedItems = latestSyncedFeedItems.map {
            if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
          }
          val flaggedNow = detail.summary.isFlagged || settingsAndContactsStore.isEmailFlagged(summary.compositeKey)
          val updatedSummary = detail.summary.copy(isUnread = false, isFlagged = flaggedNow)
          val updatedDetail = detail.copy(summary = updatedSummary)
          messageDetailCache[summary.compositeKey] = updatedDetail

          _uiState.update { state ->
            state.copy(
              feedItems = mergeFeedWithFlagged(
                syncedItems = latestSyncedFeedItems,
                flaggedItems = settingsAndContactsStore.flaggedEmails.value,
                enablePinToFeed = state.settings.enablePinToFeed,
              ),
              searchResults = state.searchResults.map {
                if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
              },
              activePane = ActivePane.Reader(
                summary = updatedSummary,
                detail = updatedDetail,
                isLoading = false,
                error = null,
                returnSearchQuery = returnSearchQuery,
              ),
            )
          }
        },
        onFailure = { error ->
          _uiState.update {
            it.copy(
              activePane = ActivePane.Reader(
                summary = hydratedSummary,
                detail = null,
                isLoading = false,
                error = error.localizedMessage ?: "Failed to load email body.",
                returnSearchQuery = returnSearchQuery,
              ),
            )
          }
        },
      )
    }
  }

  fun startNewEmail() {
    val defaultAccount = accountStore.accounts.value.firstOrNull()
    if (defaultAccount == null) {
      _uiState.update {
        it.copy(
          activePane = ActivePane.Accounts,
          statusMessage = "Add an email account before composing.",
        )
      }
      return
    }
    _uiState.update {
      it.copy(
        contactSuggestions = emptyList(),
        activePane = ActivePane.Composer(
          OutgoingEmailDraft(
            fromAccount = defaultAccount,
            to = "",
            subject = "",
            body = "",
            mode = ComposeMode.NEW,
          ),
        ),
      )
    }
  }

  fun startComposeFromMailto(
    to: String,
    cc: String = "",
    subject: String = "",
    body: String = "",
  ) {
    val readerAccountId = (_uiState.value.activePane as? ActivePane.Reader)?.summary?.accountId
    val selectedAccount = accountStore.accounts.value.find { it.id == readerAccountId }
      ?: accountStore.accounts.value.firstOrNull()
    if (selectedAccount == null) {
      _uiState.update {
        it.copy(
          activePane = ActivePane.Accounts,
          statusMessage = "Add an email account before composing.",
        )
      }
      return
    }
    _uiState.update {
      it.copy(
        contactSuggestions = emptyList(),
        activePane = ActivePane.Composer(
          OutgoingEmailDraft(
            fromAccount = selectedAccount,
            to = sanitizeHeaderControlChars(to),
            cc = sanitizeHeaderControlChars(cc),
            subject = sanitizeHeaderControlChars(subject),
            body = body,
            mode = ComposeMode.NEW,
          ),
        ),
      )
    }
  }

  fun startReply(detail: EmailDetail) {
    val account = accountStore.accounts.value.find { it.id == detail.summary.accountId }
      ?: accountStore.accounts.value.firstOrNull()
      ?: return

    val cleanSubject = detail.summary.subject
    val replySubject = if (cleanSubject.startsWith("Re:", ignoreCase = true)) {
      cleanSubject
    } else {
      "Re: $cleanSubject"
    }
    val originalMessageId = detail.summary.messageIdHeader
    val combinedReferences = listOfNotNull(detail.referencesHeader, originalMessageId)
      .joinToString(" ")
      .ifBlank { null }

    val quotedText = buildQuotedBody(detail, prefixLines = true)
    // Two full blank lines of clean breathing room above the quoted email
    _uiState.update {
      it.copy(
        contactSuggestions = emptyList(),
        activePane = ActivePane.Composer(
          OutgoingEmailDraft(
            fromAccount = account,
            to = detail.replyToAddress ?: detail.summary.fromAddress,
            subject = replySubject,
            body = "\n\n\n$quotedText",
            mode = ComposeMode.REPLY,
            inReplyToMessageId = originalMessageId,
            referencesHeader = combinedReferences,
          ),
        ),
      )
    }
  }

  fun startForward(detail: EmailDetail) {
    val account = accountStore.accounts.value.find { it.id == detail.summary.accountId }
      ?: accountStore.accounts.value.firstOrNull()
      ?: return

    val cleanSubject = detail.summary.subject
    val forwardSubject = if (cleanSubject.startsWith("Fwd:", ignoreCase = true)) {
      cleanSubject
    } else {
      "Fwd: $cleanSubject"
    }
    val forwardedBody = buildQuotedBody(detail, prefixLines = false)
    // Two full blank lines of clean breathing room above the forwarded email
    _uiState.update {
      it.copy(
        contactSuggestions = emptyList(),
        activePane = ActivePane.Composer(
          OutgoingEmailDraft(
            fromAccount = account,
            to = "",
            subject = forwardSubject,
            body = "\n\n\n$forwardedBody",
            mode = ComposeMode.FORWARD,
            referencesHeader = detail.summary.messageIdHeader,
          ),
        ),
      )
    }
  }

  fun sendDraft(draft: OutgoingEmailDraft) {
    if (draft.to.isBlank()) {
      _uiState.update { it.copy(statusMessage = "Recipient (To) cannot be empty.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSendingEmail = true, statusMessage = null) }
      val result = mailClient.sendEmail(draft)
      result.fold(
        onSuccess = {
          settingsAndContactsStore.harvestContactsFromRawAddresses("${draft.to},${draft.cc}")
          _uiState.update {
            it.copy(
              isSendingEmail = false,
              contactSuggestions = emptyList(),
              activePane = ActivePane.Feed,
              statusMessage = "Email sent from ${draft.fromAccount.emailAddress}",
            )
          }
        },
        onFailure = { error ->
          _uiState.update {
            it.copy(
              isSendingEmail = false,
              statusMessage = "Send failed: ${error.localizedMessage ?: "SMTP error"}",
            )
          }
        },
      )
    }
  }

  fun verifyAndSaveAccount(
    account: EmailAccount,
    skipVerification: Boolean = false,
    onSuccess: () -> Unit = {},
  ) {
    val activePolicy = policyConfig
    if (!activePolicy.allowCustomServers) {
      val matchedPreset = MailProviderPreset.entries.firstOrNull { preset ->
        preset != MailProviderPreset.CUSTOM &&
          preset.imapHost.equals(account.imapHost.trim(), ignoreCase = true) &&
          preset.imapPort == account.imapPort &&
          preset.smtpHost.equals(account.smtpHost.trim(), ignoreCase = true) &&
          preset.smtpPort == account.smtpPort
      }
      if (matchedPreset == null) {
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            statusMessage = "Custom IMAP/SMTP servers are disabled by device policy.",
          )
        }
        return
      }
    }

    viewModelScope.launch {
      if (!skipVerification) {
        _uiState.update { it.copy(isVerifyingAccount = true, statusMessage = "Testing IMAP connection...") }
        val verifyResult = mailClient.verifyAccount(account)
        if (verifyResult.isFailure) {
          val reason = verifyResult.exceptionOrNull()?.localizedMessage ?: "Check host, port, or App Password"
          _uiState.update {
            it.copy(
              isVerifyingAccount = false,
              statusMessage = "Connection failed: $reason",
            )
          }
          return@launch
        }
      }
      accountStore.addOrUpdateAccount(account)
      onSuccess()
      _uiState.update {
        it.copy(
          isVerifyingAccount = false,
          statusMessage = "Saved account ${account.emailAddress}",
        )
      }
      refreshFeed()
    }
  }

  fun completeMicrosoftOAuthSignIn(
    authorizationCode: String,
    codeVerifier: String,
    fallbackEmail: String,
    fallbackDisplayName: String,
    onSuccess: () -> Unit = {},
  ) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isVerifyingAccount = true,
          statusMessage = "Completing Microsoft sign-in...",
        )
      }
      val tokenResult = MicrosoftOAuthHelper.exchangeAuthorizationCode(
        code = authorizationCode,
        codeVerifier = codeVerifier,
      )
      if (tokenResult.isFailure) {
        val reason = tokenResult.exceptionOrNull()?.localizedMessage ?: "Failed to exchange Microsoft OAuth code"
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            statusMessage = "Microsoft sign-in failed: $reason",
          )
        }
        return@launch
      }

      val token = tokenResult.getOrThrow()
      val resolvedEmail = token.emailAddress?.trim()?.takeIf { it.isNotBlank() }
        ?: fallbackEmail.trim()
      if (resolvedEmail.isBlank()) {
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            statusMessage = "Please enter your Outlook email address before signing in.",
          )
        }
        return@launch
      }
      val resolvedName = fallbackDisplayName.trim()
        .ifBlank { token.displayName?.trim().orEmpty() }
        .ifBlank { resolvedEmail }

      val newAccount = EmailAccount(
        displayName = resolvedName,
        emailAddress = resolvedEmail,
        imapHost = MailProviderPreset.OUTLOOK.imapHost,
        imapPort = MailProviderPreset.OUTLOOK.imapPort,
        smtpHost = MailProviderPreset.OUTLOOK.smtpHost,
        smtpPort = MailProviderPreset.OUTLOOK.smtpPort,
        appPassword = "",
        preset = MailProviderPreset.OUTLOOK,
        authType = MailAuthType.OAUTH2_MICROSOFT,
        oauthRefreshToken = token.refreshToken,
        oauthAccessToken = token.accessToken,
        oauthTokenExpiresAtMillis = token.expiresAtEpochMillis,
      )

      _uiState.update {
        it.copy(
          isVerifyingAccount = true,
          statusMessage = "Testing Outlook IMAP connection...",
        )
      }
      val verifyResult = mailClient.verifyAccount(newAccount)
      if (verifyResult.isFailure) {
        val reason = verifyResult.exceptionOrNull()?.localizedMessage ?: "IMAP XOAUTH2 verification failed"
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            statusMessage = "Outlook connection failed: $reason",
          )
        }
        return@launch
      }

      accountStore.addOrUpdateAccount(newAccount)
      onSuccess()
      _uiState.update {
        it.copy(
          isVerifyingAccount = false,
          statusMessage = "Saved Outlook account $resolvedEmail",
        )
      }
      refreshFeed()
    }
  }

  fun removeAccount(accountId: String) {
    accountStore.removeAccount(accountId)
    settingsAndContactsStore.removeFlaggedForAccount(accountId)
    synchronized(messageDetailCache) {
      messageDetailCache.keys.removeAll { it.startsWith("$accountId:") }
    }
    _uiState.update { state ->
      state.copy(
        searchResults = state.searchResults.filterNot { it.accountId == accountId },
      )
    }
    refreshFeed()
  }

  fun dismissStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  private fun prefetchSummariesInBackground(items: List<EmailSummary>) {
    val uncached = items.filter { !messageDetailCache.containsKey(it.compositeKey) }
    if (uncached.isEmpty()) return

    val accountsById = accountStore.accounts.value.associateBy { it.id }
    val groupedByAccount = uncached.groupBy { it.accountId }

    viewModelScope.launch {
      coroutineScope {
        groupedByAccount.mapNotNull { (accountId, accountSummaries) ->
          val account = accountsById[accountId] ?: return@mapNotNull null
          val uids = accountSummaries.map { it.uid }.distinct().take(MAX_PREFETCH_PER_ACCOUNT)
          async {
            val prefetched = mailClient.prefetchMessages(account, uids)
            for ((uid, detail) in prefetched) {
              val key = "$accountId:$uid"
              val isFlagged = detail.summary.isFlagged || settingsAndContactsStore.isEmailFlagged(key)
              val hydratedDetail = detail.copy(
                summary = detail.summary.copy(isFlagged = isFlagged),
              )
              messageDetailCache[key] = hydratedDetail
            }
          }
        }.awaitAll()
      }
    }
  }

  private fun mergeFeedWithFlagged(
    syncedItems: List<EmailSummary>,
    flaggedItems: List<EmailSummary>,
    enablePinToFeed: Boolean,
  ): List<EmailSummary> {
    if (!enablePinToFeed || flaggedItems.isEmpty()) {
      return syncedItems.map { it.copy(isFlagged = false) }
    }
    val flaggedByKey = flaggedItems.associateBy { it.compositeKey }
    val merged = LinkedHashMap<String, EmailSummary>()

    for (item in syncedItems) {
      val isPinned = flaggedByKey.containsKey(item.compositeKey)
      merged[item.compositeKey] = item.copy(isFlagged = isPinned)
    }
    for (flagged in flaggedItems) {
      if (!merged.containsKey(flagged.compositeKey)) {
        merged[flagged.compositeKey] = flagged.copy(isFlagged = true)
      }
    }
    return merged.values.sortedWith(
      compareByDescending<EmailSummary> { it.isFlagged }
        .thenByDescending { it.receivedAtEpochMillis },
    )
  }

  private fun buildQuotedBody(detail: EmailDetail, prefixLines: Boolean): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(detail.summary.receivedAtEpochMillis))
    val rawText = detail.plainTextBody
      ?: detail.htmlBody?.replace(Regex("<[^>]*>"), " ")?.trim()
      ?: ""
    return if (prefixLines) {
      val quotedLines = rawText.lines().joinToString("\n") { "> $it" }
      "On $dateStr, ${detail.summary.fromName} <${detail.summary.fromAddress}> wrote:\n$quotedLines"
    } else {
      "---------- Forwarded message ---------\n" +
        "From: ${detail.summary.fromName} <${detail.summary.fromAddress}>\n" +
        "Date: $dateStr\n" +
        "Subject: ${detail.summary.subject}\n\n" +
        rawText
    }
  }

  companion object {
    private const val MAX_CACHED_EMAIL_DETAILS = 100
    private const val MAX_PREFETCH_PER_ACCOUNT = 20
    private const val MAX_SEARCH_PREFETCH_ITEMS = 15

    fun provideFactory(
      context: Context,
      policyConfigProvider: () -> MailPolicyConfig = { MailPolicyProvider.getConfig() },
    ): ViewModelProvider.Factory {
      val appContext = context.applicationContext
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          val store = AccountStore(appContext)
          return HM02ViewModel(
            accountStore = store,
            settingsAndContactsStore = SettingsAndContactsStore(appContext),
            mailClient = ImapSmtpMailClient(onAccountTokenUpdated = store::addOrUpdateAccount),
            initialPolicyConfig = policyConfigProvider(),
          ) as T
        }
      }
    }

    fun provideFactory(
      accountStore: AccountStore,
      settingsAndContactsStore: SettingsAndContactsStore,
      policyConfig: MailPolicyConfig = MailPolicyProvider.getConfig(),
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HM02ViewModel(
          accountStore = accountStore,
          settingsAndContactsStore = settingsAndContactsStore,
          mailClient = ImapSmtpMailClient(onAccountTokenUpdated = accountStore::addOrUpdateAccount),
          initialPolicyConfig = policyConfig,
        ) as T
      }
    }
  }
}
