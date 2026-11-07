package dev.hm02.mail.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.data.AccountSyncError
import dev.hm02.mail.data.ImapSmtpMailClient
import dev.hm02.mail.data.MailClient
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.OutgoingEmailDraft
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
}

data class HM02UiState(
  val brandTitle: String = "HM02",
  val policyName: String = "Recent (24h)",
  val policyEmptyDescription: String = "",
  val blockRemoteMediaInEmails: Boolean = true,
  val blockExternalImagesByDefault: Boolean = false,
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
  private val mailClient: MailClient = ImapSmtpMailClient(),
  private val policyConfig: MailPolicyConfig = MailPolicyProvider.getConfig(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    HM02UiState(
      brandTitle = policyConfig.brandTitle,
      policyName = policyConfig.feedPolicy.name,
      policyEmptyDescription = policyConfig.feedPolicy.emptyStateDescription,
      blockRemoteMediaInEmails = policyConfig.blockRemoteMediaInEmails,
      blockExternalImagesByDefault = policyConfig.blockExternalImagesByDefault,
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
  }

  /**
   * Called automatically every time the user opens/resumes the app (ON_START)
   * or taps the manual refresh icon.
   */
  fun refreshFeed() {
    val accounts = accountStore.accounts.value
    if (accounts.isEmpty()) {
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
      val failures = syncResult.failedAccounts
      val errorNotice = when {
        failures.isEmpty() -> null
        failures.size == accounts.size ->
          "Sync failed for all accounts (${failures.first().accountEmail}: ${failures.first().message})"
        else ->
          "Partial sync error (${failures.joinToString { it.accountEmail }})"
      }

      _uiState.update {
        it.copy(
          feedItems = syncResult.items,
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
          _uiState.update {
            it.copy(
              searchResults = items,
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
    _uiState.update { it.copy(activePane = ActivePane.Feed) }
  }

  fun showAccounts() {
    _uiState.update { it.copy(activePane = ActivePane.Accounts, statusMessage = null) }
  }

  fun openMessage(summary: EmailSummary) {
    val account = accountStore.accounts.value.find { it.id == summary.accountId } ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          activePane = ActivePane.Reader(
            summary = summary,
            detail = null,
            isLoading = true,
            error = null,
          ),
        )
      }
      val result = mailClient.fetchMessageDetail(account, summary.uid)
      result.fold(
        onSuccess = { detail ->
          _uiState.update { state ->
            state.copy(
              feedItems = state.feedItems.map {
                if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
              },
              searchResults = state.searchResults.map {
                if (it.compositeKey == summary.compositeKey) it.copy(isUnread = false) else it
              },
              activePane = ActivePane.Reader(
                summary = detail.summary,
                detail = detail,
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
                summary = summary,
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
          _uiState.update {
            it.copy(
              isSendingEmail = false,
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
    refreshFeed()
  }

  fun dismissStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
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
      policyConfig: MailPolicyConfig = MailPolicyProvider.getConfig(),
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HM02ViewModel(
          accountStore = accountStore,
          mailClient = ImapSmtpMailClient(),
          policyConfig = policyConfig,
        ) as T
      }
    }
  }
}
