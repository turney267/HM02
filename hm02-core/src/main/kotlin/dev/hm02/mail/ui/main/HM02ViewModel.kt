package dev.hm02.mail.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.data.AccountSyncError
import dev.hm02.mail.data.ImapSmtpMailClient
import dev.hm02.mail.data.MailClient
import dev.hm02.mail.data.SettingsAndContactsStore
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.model.UserSettings
import dev.hm02.mail.policy.MailPolicyConfig
import dev.hm02.mail.policy.MailPolicyProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ActivePane {
  data object Feed : ActivePane
  data class SearchResults(val query: String) : ActivePane
  data class Reader(val summary: EmailSummary, val detail: EmailDetail?, val isLoading: Boolean, val error: String?) : ActivePane
  data class Composer(val initialDraft: OutgoingEmailDraft) : ActivePane
  data object Accounts : ActivePane
  data object Settings : ActivePane
}

data class HM02UiState(
  val brandTitle: String = "HM02",
  val policyName: String = "Recent (24h)",
  val policyEmptyDescription: String = "",
  val blockRemoteMediaInEmails: Boolean = true,
  val blockExternalImagesByDefault: Boolean = false,
  val settings: UserSettings = UserSettings(),
  val hasContactsPermission: Boolean = false,
  val savedContactsCount: Int = 0,
  val flaggedEmailsCount: Int = 0,
  val contactSuggestions: List<ContactSuggestion> = emptyList(),
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
  private val mailClient: MailClient = ImapSmtpMailClient(),
  private val policyConfig: MailPolicyConfig = MailPolicyProvider.getConfig(),
) : ViewModel() {

  private var latestSyncedFeedItems: List<EmailSummary> = emptyList()

  private val _uiState = MutableStateFlow(
    HM02UiState(
      brandTitle = policyConfig.brandTitle,
      policyName = policyConfig.feedPolicy.name,
      policyEmptyDescription = policyConfig.feedPolicy.emptyStateDescription,
      blockRemoteMediaInEmails = policyConfig.blockRemoteMediaInEmails,
      blockExternalImagesByDefault = policyConfig.blockExternalImagesByDefault,
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
              enableFlagForLater = updatedSettings.enableFlagForLater,
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
              enableFlagForLater = state.settings.enableFlagForLater,
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

    viewModelScope.launch {
      _uiState.update { it.copy(isRefreshingFeed = true, statusMessage = null) }
      val syncResult = mailClient.fetchFeed(accounts, policyConfig)
      latestSyncedFeedItems = syncResult.items
      val failures = syncResult.failedAccounts
      val errorNotice = when {
        failures.isEmpty() -> null
        failures.size == accounts.size ->
          "Sync failed for all accounts (${failures.first().accountEmail}: ${failures.first().message})"
        else ->
          "Partial sync error (${failures.joinToString { it.accountEmail }})"
      }

      _uiState.update { state ->
        state.copy(
          feedItems = mergeFeedWithFlagged(
            syncedItems = latestSyncedFeedItems,
            flaggedItems = settingsAndContactsStore.flaggedEmails.value,
            enableFlagForLater = state.settings.enableFlagForLater,
          ),
          failedAccountSyncs = failures,
          isRefreshingFeed = false,
          lastRefreshedAtMillis = System.currentTimeMillis(),
          statusMessage = errorNotice,
        )
      }
    }
  }

  fun updateSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun executeSearch() {
    val query = _uiState.value.searchQuery.trim()
    val accounts = accountStore.accounts.value
    if (query.isEmpty()) {
      showFeed()
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
          activePane = ActivePane.SearchResults(query),
          statusMessage = null,
        )
      }
      val result = mailClient.searchMessages(accounts, query)
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
        searchResults = emptyList(),
        activePane = ActivePane.Feed,
      )
    }
  }

  fun showFeed() {
    _uiState.update {
      it.copy(
        activePane = ActivePane.Feed,
        contactSuggestions = emptyList(),
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

  fun updateSaveContactsFromEmails(enabled: Boolean) {
    settingsAndContactsStore.updateSettings { it.copy(saveContactsFromEmails = enabled) }
  }

  fun updateEnableFlagForLater(enabled: Boolean) {
    settingsAndContactsStore.updateSettings { it.copy(enableFlagForLater = enabled) }
  }

  fun toggleFlagged(email: EmailSummary) {
    val nowFlagged = settingsAndContactsStore.toggleFlaggedEmail(email)
    latestSyncedFeedItems = latestSyncedFeedItems.map {
      if (it.compositeKey == email.compositeKey) it.copy(isFlagged = nowFlagged) else it
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
          enableFlagForLater = state.settings.enableFlagForLater,
        ),
        searchResults = state.searchResults.map {
          if (it.compositeKey == email.compositeKey) it.copy(isFlagged = nowFlagged) else it
        },
        activePane = updatedPane,
        statusMessage = if (nowFlagged) "Flagged for later (saved to feed)" else "Removed flag",
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

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          activePane = ActivePane.Reader(
            summary = hydratedSummary,
            detail = null,
            isLoading = true,
            error = null,
          ),
        )
      }
      val result = mailClient.fetchMessageDetail(account, summary.uid)
      result.fold(
        onSuccess = { detail ->
          settingsAndContactsStore.harvestContactsFromEmail(detail)
          settingsAndContactsStore.markFlaggedEmailRead(summary.compositeKey)
          latestSyncedFeedItems = latestSyncedFeedItems.map {
            if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
          }
          val flaggedNow = detail.summary.isFlagged || settingsAndContactsStore.isEmailFlagged(summary.compositeKey)
          val updatedSummary = detail.summary.copy(isUnread = false, isFlagged = flaggedNow)
          val updatedDetail = detail.copy(summary = updatedSummary)

          _uiState.update { state ->
            state.copy(
              feedItems = mergeFeedWithFlagged(
                syncedItems = latestSyncedFeedItems,
                flaggedItems = settingsAndContactsStore.flaggedEmails.value,
                enableFlagForLater = state.settings.enableFlagForLater,
              ),
              searchResults = state.searchResults.map {
                if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
              },
              activePane = ActivePane.Reader(
                summary = updatedSummary,
                detail = updatedDetail,
                isLoading = false,
                error = null,
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

  fun verifyAndSaveAccount(account: EmailAccount, skipVerification: Boolean = false) {
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
      _uiState.update {
        it.copy(
          isVerifyingAccount = false,
          statusMessage = "Saved account ${account.emailAddress}",
        )
      }
      refreshFeed()
    }
  }

  fun removeAccount(accountId: String) {
    accountStore.removeAccount(accountId)
    settingsAndContactsStore.removeFlaggedForAccount(accountId)
    refreshFeed()
  }

  fun dismissStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  private fun mergeFeedWithFlagged(
    syncedItems: List<EmailSummary>,
    flaggedItems: List<EmailSummary>,
    enableFlagForLater: Boolean,
  ): List<EmailSummary> {
    if (!enableFlagForLater || flaggedItems.isEmpty()) {
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
          mailClient = ImapSmtpMailClient(),
          policyConfig = policyConfig,
        ) as T
      }
    }
  }
}
