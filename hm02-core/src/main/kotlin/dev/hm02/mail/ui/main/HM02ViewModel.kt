package dev.hm02.mail.ui.main

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hm02.mail.data.AccountStore
import dev.hm02.mail.data.AccountSyncError
import dev.hm02.mail.data.ImapSmtpMailClient
import dev.hm02.mail.data.MailClient
import dev.hm02.mail.data.MicrosoftLoopbackOAuthServer
import dev.hm02.mail.data.MicrosoftOAuthHelper
import dev.hm02.mail.data.PendingMicrosoftOAuthSession
import dev.hm02.mail.data.SettingsAndContactsStore
import dev.hm02.mail.data.computeDayWindowStartMillis
import dev.hm02.mail.data.parseQuickDaySearchDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailAttachment
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
import kotlinx.coroutines.withContext
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

internal fun mergeDayWindowSummaries(
  existing: List<EmailSummary>,
  incoming: List<EmailSummary>,
  flaggedKeys: Set<String> = emptySet(),
  attachmentOverrides: Map<String, Boolean> = emptyMap(),
  authoritativeWindowStartMillis: Long? = null,
  maxItems: Int = 200,
): List<EmailSummary> {
  val byKey = LinkedHashMap<String, EmailSummary>(existing.size + incoming.size)
  for (item in existing) {
    if (authoritativeWindowStartMillis == null || item.receivedAtEpochMillis < authoritativeWindowStartMillis) {
      byKey[item.compositeKey] = item
    }
  }
  val existingByKey = existing.associateBy { it.compositeKey }
  for (item in incoming) {
    val prev = existingByKey[item.compositeKey]
    val knownHasAttachment = attachmentOverrides[item.compositeKey]
      ?: (item.hasAttachments || (prev?.hasAttachments == true))
    byKey[item.compositeKey] = item.copy(
      isFlagged = item.isFlagged || item.compositeKey in flaggedKeys,
      hasAttachments = knownHasAttachment,
    )
  }
  return byKey.values
    .sortedByDescending { it.receivedAtEpochMillis }
    .take(maxItems)
}

internal fun buildLocalQuickDaySearchResults(
  quickDays: Int,
  dayWindowItems: List<EmailSummary>,
  feedItems: List<EmailSummary>,
  flaggedItems: List<EmailSummary>,
  attachmentOverrides: Map<String, Boolean> = emptyMap(),
  nowMillis: Long = System.currentTimeMillis(),
): List<EmailSummary> {
  val windowStartMillis = computeDayWindowStartMillis(quickDays, nowMillis = nowMillis)
  val flaggedKeys = flaggedItems.map { it.compositeKey }.toSet()
  val combined = LinkedHashMap<String, EmailSummary>()
  for (item in dayWindowItems) {
    combined[item.compositeKey] = item
  }
  for (item in feedItems) {
    val prev = combined[item.compositeKey]
    combined[item.compositeKey] = item.copy(
      hasAttachments = item.hasAttachments || (prev?.hasAttachments == true),
    )
  }
  for (item in flaggedItems) {
    val existing = combined[item.compositeKey]
    combined[item.compositeKey] = (existing ?: item).copy(isFlagged = true)
  }
  return combined.values
    .asSequence()
    .filter { it.receivedAtEpochMillis >= windowStartMillis }
    .map { item ->
      item.copy(
        isFlagged = item.isFlagged || item.compositeKey in flaggedKeys,
        hasAttachments = attachmentOverrides[item.compositeKey] ?: item.hasAttachments,
      )
    }
    .sortedByDescending { it.receivedAtEpochMillis }
    .toList()
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
  val downloadingAttachmentPartId: String? = null,
  val isVerifyingAccount: Boolean = false,
  val isAwaitingMicrosoftOAuth: Boolean = false,
  val accountSetupError: String? = null,
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

  @Volatile
  private var activeLoopbackServer: MicrosoftLoopbackOAuthServer? = null

  private val handledOAuthCodes: MutableSet<String> = Collections.synchronizedSet(HashSet())

  private data class CachedSearchEntry(
    val items: List<EmailSummary>,
    val cachedAtMillis: Long,
  )

  private var latestSyncedFeedItems: List<EmailSummary> = emptyList()

  @Volatile
  private var cachedDayWindowItems: List<EmailSummary> = emptyList()

  @Volatile
  private var cachedDayWindowSyncedAtMillis: Long = 0L

  @Volatile
  private var cachedDayWindowMaxDaysCovered: Int = 0

  @Volatile
  private var activeSearchJob: Job? = null

  @Volatile
  private var activeDayWindowWarmJob: Job? = null

  private val searchResultsCache: MutableMap<String, CachedSearchEntry> =
    Collections.synchronizedMap(HashMap())

  private val attachmentBytesCache: MutableMap<String, ByteArray> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
          size > MAX_CACHED_ATTACHMENT_BLOBS
      },
    )

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
    hydrateDiskCachesOnLaunch()
  }

  private fun hydrateDiskCachesOnLaunch() {
    viewModelScope.launch {
      val hydrated = withContext(Dispatchers.IO) {
        Triple(
          settingsAndContactsStore.loadCachedFeedSummaries(),
          settingsAndContactsStore.loadCachedDayWindowSummaries(),
          settingsAndContactsStore.loadCachedEmailDetails(),
        )
      }
      val (cachedFeed, cachedDayWindow, cachedDetails) = hydrated
      synchronized(messageDetailCache) {
        for (detail in cachedDetails) {
          val key = detail.summary.compositeKey
          if (!messageDetailCache.containsKey(key)) {
            messageDetailCache[key] = detail
          }
        }
      }
      if (cachedDayWindowItems.isEmpty() && cachedDayWindow.items.isNotEmpty()) {
        cachedDayWindowItems = applyKnownAttachmentStates(cachedDayWindow.items)
        cachedDayWindowSyncedAtMillis = cachedDayWindow.syncedAtEpochMillis
        cachedDayWindowMaxDaysCovered = cachedDayWindow.maxDaysCovered
      }
      if (latestSyncedFeedItems.isEmpty() && cachedFeed.isNotEmpty() && accountStore.accounts.value.isNotEmpty()) {
        latestSyncedFeedItems = applyKnownAttachmentStates(cachedFeed)
        _uiState.update { state ->
          state.copy(
            feedItems = mergeFeedWithFlagged(
              syncedItems = latestSyncedFeedItems,
              flaggedItems = settingsAndContactsStore.flaggedEmails.value,
              enablePinToFeed = state.settings.enablePinToFeed,
            ),
            lastRefreshedAtMillis = state.lastRefreshedAtMillis
              ?: cachedDayWindow.syncedAtEpochMillis.takeIf { it > 0L },
          )
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
      _uiState.update { it.copy(isRefreshingFeed = true) }
      val syncResult = mailClient.fetchFeed(accounts, activeConfig)
      val failures = syncResult.failedAccounts
      val allFailed = failures.size == accounts.size && syncResult.items.isEmpty()
      if (!allFailed) {
        latestSyncedFeedItems = applyKnownAttachmentStates(syncResult.items)
        val flaggedKeys = settingsAndContactsStore.flaggedEmails.value
          .map { it.compositeKey }
          .toSet()
        cachedDayWindowItems = mergeDayWindowSummaries(
          existing = cachedDayWindowItems,
          incoming = latestSyncedFeedItems,
          flaggedKeys = flaggedKeys,
          attachmentOverrides = knownAttachmentOverrides(),
          maxItems = MAX_CACHED_DAY_WINDOW_ITEMS,
        )
        val feedToPersist = latestSyncedFeedItems
        viewModelScope.launch(Dispatchers.IO) {
          runCatching { settingsAndContactsStore.saveCachedFeedSummaries(feedToPersist) }
        }
      }

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
          feedItems = mergedFeed,
          failedAccountSyncs = failures,
          isRefreshingFeed = false,
          lastRefreshedAtMillis = if (allFailed) state.lastRefreshedAtMillis else System.currentTimeMillis(),
          statusMessage = errorNotice ?: state.statusMessage,
        )
      }

      prefetchSummariesInBackground(mergedFeed)
      warmDayWindowCacheInBackground(accounts, activeConfig.maxSearchDays)
    }
  }

  private fun warmDayWindowCacheInBackground(
    accounts: List<EmailAccount>,
    maxSearchDays: Int,
  ) {
    if (maxSearchDays <= 0 || accounts.isEmpty()) return
    val now = System.currentTimeMillis()
    if (
      cachedDayWindowItems.isNotEmpty() &&
      cachedDayWindowMaxDaysCovered >= maxSearchDays &&
      (now - cachedDayWindowSyncedAtMillis) < DAY_WINDOW_WARM_INTERVAL_MS
    ) {
      return
    }
    if (activeDayWindowWarmJob?.isActive == true) return

    activeDayWindowWarmJob = viewModelScope.launch {
      val warmQuery = if (maxSearchDays >= 2) "a$maxSearchDays" else "a"
      val warmResult = mailClient.searchMessages(
        accounts = accounts,
        query = warmQuery,
        maxPerAccount = MAX_DAY_WINDOW_SEARCH_PER_ACCOUNT,
        maxSearchDays = maxSearchDays,
      )
      warmResult.onSuccess { items ->
        val syncedAt = System.currentTimeMillis()
        val flaggedItems = settingsAndContactsStore.flaggedEmails.value
        val flaggedKeys = flaggedItems.map { it.compositeKey }.toSet()
        val overrides = knownAttachmentOverrides()
        val windowStart = computeDayWindowStartMillis(maxSearchDays, nowMillis = syncedAt)
        val merged = mergeDayWindowSummaries(
          existing = cachedDayWindowItems,
          incoming = items,
          flaggedKeys = flaggedKeys,
          attachmentOverrides = overrides,
          authoritativeWindowStartMillis = windowStart,
          maxItems = MAX_CACHED_DAY_WINDOW_ITEMS,
        )
        cachedDayWindowItems = merged
        cachedDayWindowSyncedAtMillis = syncedAt
        cachedDayWindowMaxDaysCovered = maxSearchDays
        viewModelScope.launch(Dispatchers.IO) {
          runCatching {
            settingsAndContactsStore.saveCachedDayWindowSummaries(
              items = merged,
              syncedAtEpochMillis = syncedAt,
              maxDaysCovered = maxSearchDays,
            )
          }
        }
        val activeSearch = _uiState.value.activePane as? ActivePane.SearchResults
        val activeQuickDays = activeSearch?.let {
          parseQuickDaySearchDays(it.query, maxDays = maxSearchDays)
        }
        if (activeQuickDays != null) {
          val liveMatches = buildLocalQuickDaySearchResults(
            quickDays = activeQuickDays,
            dayWindowItems = merged,
            feedItems = latestSyncedFeedItems,
            flaggedItems = flaggedItems,
            attachmentOverrides = overrides,
            nowMillis = syncedAt,
          )
          _uiState.update { state ->
            val currentPane = state.activePane as? ActivePane.SearchResults
            if (currentPane?.query == activeSearch.query) {
              state.copy(searchResults = liveMatches)
            } else {
              state
            }
          }
        }
        prefetchSummariesInBackground(merged.take(MAX_SEARCH_PREFETCH_ITEMS))
      }
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

  fun refreshSearch() {
    executeSearch(forceRefresh = true)
  }

  fun executeSearch(forceRefresh: Boolean = false) {
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

    activeSearchJob?.cancel()
    val maxSearchDays = policyConfig.maxSearchDays
    val quickDays = parseQuickDaySearchDays(query, maxDays = maxSearchDays)
    val now = System.currentTimeMillis()
    val flaggedItems = settingsAndContactsStore.flaggedEmails.value
    val flaggedKeys = flaggedItems.map { it.compositeKey }.toSet()
    val attachmentOverrides = knownAttachmentOverrides()

    if (quickDays != null) {
      val instantMatches = buildLocalQuickDaySearchResults(
        quickDays = quickDays,
        dayWindowItems = cachedDayWindowItems,
        feedItems = latestSyncedFeedItems,
        flaggedItems = flaggedItems,
        attachmentOverrides = attachmentOverrides,
        nowMillis = now,
      )
      val hasFreshDayCache = !forceRefresh &&
        cachedDayWindowSyncedAtMillis > 0L &&
        cachedDayWindowMaxDaysCovered >= quickDays &&
        (now - cachedDayWindowSyncedAtMillis) < DAY_WINDOW_CACHE_FRESHNESS_MS

      _uiState.update {
        it.copy(
          isSearching = !hasFreshDayCache,
          searchResults = instantMatches,
          searchSuggestions = emptyList(),
          activePane = ActivePane.SearchResults(query),
          statusMessage = null,
        )
      }

      if (hasFreshDayCache) {
        prefetchSummariesInBackground(instantMatches.take(MAX_SEARCH_PREFETCH_ITEMS))
        return
      }

      activeSearchJob = viewModelScope.launch {
        val result = mailClient.searchMessages(
          accounts = accounts,
          query = query,
          maxPerAccount = MAX_DAY_WINDOW_SEARCH_PER_ACCOUNT,
          maxSearchDays = maxSearchDays,
        )
        result.fold(
          onSuccess = { items ->
            val syncedAt = System.currentTimeMillis()
            val windowStart = computeDayWindowStartMillis(quickDays, nowMillis = syncedAt)
            val currentFlaggedItems = settingsAndContactsStore.flaggedEmails.value
            val currentFlaggedKeys = currentFlaggedItems.map { it.compositeKey }.toSet()
            val currentOverrides = knownAttachmentOverrides()
            val merged = mergeDayWindowSummaries(
              existing = cachedDayWindowItems,
              incoming = items,
              flaggedKeys = currentFlaggedKeys,
              attachmentOverrides = currentOverrides,
              authoritativeWindowStartMillis = windowStart,
              maxItems = MAX_CACHED_DAY_WINDOW_ITEMS,
            )
            cachedDayWindowItems = merged
            cachedDayWindowSyncedAtMillis = syncedAt
            cachedDayWindowMaxDaysCovered = maxOf(cachedDayWindowMaxDaysCovered, quickDays)
            val coveredDays = cachedDayWindowMaxDaysCovered
            viewModelScope.launch(Dispatchers.IO) {
              runCatching {
                settingsAndContactsStore.saveCachedDayWindowSummaries(
                  items = merged,
                  syncedAtEpochMillis = syncedAt,
                  maxDaysCovered = coveredDays,
                )
              }
            }
            val updatedMatches = buildLocalQuickDaySearchResults(
              quickDays = quickDays,
              dayWindowItems = merged,
              feedItems = emptyList(),
              flaggedItems = currentFlaggedItems,
              attachmentOverrides = currentOverrides,
              nowMillis = syncedAt,
            )
            _uiState.update {
              it.copy(
                searchResults = updatedMatches,
                isSearching = false,
              )
            }
            prefetchSummariesInBackground(updatedMatches.take(MAX_SEARCH_PREFETCH_ITEMS))
          },
          onFailure = { error ->
            _uiState.update {
              it.copy(
                isSearching = false,
                statusMessage = if (instantMatches.isNotEmpty()) {
                  "Showing cached emails (refresh failed: ${error.localizedMessage ?: "Offline"})"
                } else {
                  "Search failed: ${error.localizedMessage ?: "Unknown error"}"
                },
              )
            }
          },
        )
      }
      return
    }

    val normalizedKey = query.lowercase(Locale.US)
    val cachedEntry = synchronized(searchResultsCache) { searchResultsCache[normalizedKey] }
    val cachedDecorated = cachedEntry?.items?.map { item ->
      item.copy(
        isFlagged = item.isFlagged || item.compositeKey in flaggedKeys,
        hasAttachments = attachmentOverrides[item.compositeKey] ?: item.hasAttachments,
      )
    }.orEmpty()
    val isGeneralCacheFresh = !forceRefresh &&
      cachedEntry != null &&
      (now - cachedEntry.cachedAtMillis) < GENERAL_SEARCH_CACHE_FRESHNESS_MS

    _uiState.update {
      it.copy(
        isSearching = !isGeneralCacheFresh,
        searchResults = cachedDecorated,
        searchSuggestions = emptyList(),
        activePane = ActivePane.SearchResults(query),
        statusMessage = null,
      )
    }

    if (isGeneralCacheFresh) {
      prefetchSummariesInBackground(cachedDecorated.take(MAX_SEARCH_PREFETCH_ITEMS))
      return
    }

    activeSearchJob = viewModelScope.launch {
      val result = mailClient.searchMessages(
        accounts = accounts,
        query = query,
        maxSearchDays = maxSearchDays,
      )
      result.fold(
        onSuccess = { items ->
          val currentFlaggedKeys = settingsAndContactsStore.flaggedEmails.value
            .map { it.compositeKey }
            .toSet()
          val currentOverrides = knownAttachmentOverrides()
          val decoratedItems = items.map { item ->
            item.copy(
              isFlagged = item.isFlagged || item.compositeKey in currentFlaggedKeys,
              hasAttachments = currentOverrides[item.compositeKey] ?: item.hasAttachments,
            )
          }
          synchronized(searchResultsCache) {
            searchResultsCache[normalizedKey] = CachedSearchEntry(
              items = decoratedItems,
              cachedAtMillis = System.currentTimeMillis(),
            )
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
              statusMessage = if (cachedDecorated.isNotEmpty()) {
                "Showing cached search results (refresh failed: ${error.localizedMessage ?: "Offline"})"
              } else {
                "Search failed: ${error.localizedMessage ?: "Unknown error"}"
              },
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

  fun updateAccentPreset(presetId: String) {
    val validId = dev.hm02.mail.ui.theme.findFlyAccentPreset(presetId).id
    settingsAndContactsStore.updateSettings { it.copy(accentPresetId = validId) }
  }

  fun updateDarkThemeOverride(darkThemeOverride: Boolean?) {
    settingsAndContactsStore.updateSettings { it.copy(darkThemeOverride = darkThemeOverride) }
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
      val cachedHasAttachments = cachedDetail.attachments.isNotEmpty()
      latestSyncedFeedItems = latestSyncedFeedItems.map {
        if (it.compositeKey == summary.compositeKey) {
          it.copy(isUnread = false, hasAttachments = cachedHasAttachments)
        } else {
          it
        }
      }
      cachedDayWindowItems = cachedDayWindowItems.map {
        if (it.compositeKey == summary.compositeKey) {
          it.copy(isUnread = false, hasAttachments = cachedHasAttachments)
        } else {
          it
        }
      }
      val flaggedNow = cachedDetail.summary.isFlagged || isCurrentlyFlagged
      val updatedSummary = cachedDetail.summary.copy(
        isUnread = false,
        isFlagged = flaggedNow,
        hasAttachments = cachedHasAttachments,
      )
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
            if (it.compositeKey == summary.compositeKey) {
              it.copy(isUnread = false, hasAttachments = cachedHasAttachments)
            } else {
              it
            }
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
          val hasAttachmentsNow = detail.attachments.isNotEmpty()
          latestSyncedFeedItems = latestSyncedFeedItems.map {
            if (it.compositeKey == summary.compositeKey) {
              it.copy(isUnread = false, hasAttachments = hasAttachmentsNow)
            } else {
              it
            }
          }
          cachedDayWindowItems = cachedDayWindowItems.map {
            if (it.compositeKey == summary.compositeKey) {
              it.copy(isUnread = false, hasAttachments = hasAttachmentsNow)
            } else {
              it
            }
          }
          val flaggedNow = detail.summary.isFlagged || settingsAndContactsStore.isEmailFlagged(summary.compositeKey)
          val updatedSummary = detail.summary.copy(
            isUnread = false,
            isFlagged = flaggedNow,
            hasAttachments = hasAttachmentsNow,
          )
          val updatedDetail = detail.copy(summary = updatedSummary)
          messageDetailCache[summary.compositeKey] = updatedDetail
          persistDetailCacheAsync()

          _uiState.update { state ->
            state.copy(
              feedItems = mergeFeedWithFlagged(
                syncedItems = latestSyncedFeedItems,
                flaggedItems = settingsAndContactsStore.flaggedEmails.value,
                enablePinToFeed = state.settings.enablePinToFeed,
              ),
              searchResults = state.searchResults.map {
                if (it.compositeKey == summary.compositeKey) {
                  it.copy(isUnread = false, hasAttachments = hasAttachmentsNow)
                } else {
                  it
                }
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
      _uiState.update { it.copy(accountSetupError = null) }
      if (!skipVerification) {
        _uiState.update { it.copy(isVerifyingAccount = true, statusMessage = "Testing IMAP connection...") }
        val verifyResult = mailClient.verifyAccount(account)
        if (verifyResult.isFailure) {
          val reason = verifyResult.exceptionOrNull()?.localizedMessage ?: "Check host, port, or App Password"
          _uiState.update {
            it.copy(
              isVerifyingAccount = false,
              accountSetupError = "Connection failed: $reason",
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
          accountSetupError = null,
          activePane = ActivePane.Feed,
          statusMessage = "Saved account ${account.emailAddress}",
        )
      }
      refreshFeed()
    }
  }

  fun startMicrosoftOAuthInBrowser(
    email: String,
    displayName: String,
    openBrowserUrl: (String) -> Boolean,
    onSuccess: () -> Unit = {},
  ) {
    activeLoopbackServer?.close()
    activeLoopbackServer = null

    val pkce = MicrosoftOAuthHelper.generatePkceChallenge()
    val server = try {
      MicrosoftLoopbackOAuthServer(
        expectedState = pkce.state,
        brandTitle = policyConfig.brandTitle,
        onResult = { result ->
          activeLoopbackServer = null
          result.fold(
            onSuccess = { code ->
              if (!handledOAuthCodes.add(code)) return@fold
              val redirectUri = activeLoopbackRedirectUri ?: MicrosoftOAuthHelper.REDIRECT_URI
              completeMicrosoftOAuthSignIn(
                authorizationCode = code,
                codeVerifier = pkce.codeVerifier,
                redirectUri = redirectUri,
                fallbackEmail = email.trim(),
                fallbackDisplayName = displayName.trim(),
                initialDelayMs = 450L,
                onSuccess = onSuccess,
              )
            },
            onFailure = { err ->
              accountStore.clearPendingMicrosoftOAuth()
              val msg = err.localizedMessage ?: "Microsoft sign-in was cancelled."
              _uiState.update {
                it.copy(
                  isAwaitingMicrosoftOAuth = false,
                  isVerifyingAccount = false,
                  accountSetupError = msg,
                  statusMessage = msg,
                )
              }
            },
          )
        },
      )
    } catch (e: Exception) {
      val msg = "Failed to start local OAuth listener: ${e.localizedMessage ?: "Unknown error"}"
      _uiState.update {
        it.copy(
          isAwaitingMicrosoftOAuth = false,
          accountSetupError = msg,
          statusMessage = msg,
        )
      }
      return
    }

    activeLoopbackServer = server
    activeLoopbackRedirectUri = server.redirectUri
    accountStore.savePendingMicrosoftOAuth(
      PendingMicrosoftOAuthSession(
        state = pkce.state,
        codeVerifier = pkce.codeVerifier,
        redirectUri = server.redirectUri,
        emailAddress = email.trim(),
        displayName = displayName.trim(),
      ),
    )

    val authorizeUrl = MicrosoftOAuthHelper.buildAuthorizationUrl(
      pkce = pkce,
      loginHint = email.trim(),
      redirectUri = server.redirectUri,
    )

    _uiState.update {
      it.copy(
        isAwaitingMicrosoftOAuth = true,
        accountSetupError = null,
        statusMessage = null,
      )
    }

    if (!openBrowserUrl(authorizeUrl)) {
      server.close()
      activeLoopbackServer = null
      accountStore.clearPendingMicrosoftOAuth()
      val msg = "Could not open Surf browser for Microsoft sign-in."
      _uiState.update {
        it.copy(
          isAwaitingMicrosoftOAuth = false,
          accountSetupError = msg,
          statusMessage = msg,
        )
      }
    }
  }

  @Volatile
  private var activeLoopbackRedirectUri: String? = null

  fun handleOAuthCallbackUri(rawUri: String?) {
    if (rawUri.isNullOrBlank()) return
    if (!rawUri.startsWith(MicrosoftOAuthHelper.APP_RETURN_URI, ignoreCase = true)) return

    val params = MicrosoftOAuthHelper.parseQueryParameters(rawUri)
    val code = params["code"]?.trim().orEmpty()
    val returnedState = params["state"]?.trim().orEmpty()
    val error = params["error"]?.trim().orEmpty()
    val errorDesc = params["error_description"]?.trim().orEmpty()

    if (code.isEmpty() && error.isEmpty() && errorDesc.isEmpty()) return

    val pending = accountStore.loadPendingMicrosoftOAuth()
    if (error.isNotEmpty() || errorDesc.isNotEmpty()) {
      activeLoopbackServer?.close()
      activeLoopbackServer = null
      accountStore.clearPendingMicrosoftOAuth()
      val msg = errorDesc.ifBlank { error }
      _uiState.update {
        it.copy(
          activePane = ActivePane.Accounts,
          isAwaitingMicrosoftOAuth = false,
          isVerifyingAccount = false,
          accountSetupError = msg,
          statusMessage = msg,
        )
      }
      return
    }

    if (pending == null) return
    if (pending.state.isNotBlank() && returnedState.isNotBlank() && pending.state != returnedState) {
      accountStore.clearPendingMicrosoftOAuth()
      val msg = "OAuth state mismatch. Please try signing in again."
      _uiState.update {
        it.copy(
          activePane = ActivePane.Accounts,
          isAwaitingMicrosoftOAuth = false,
          isVerifyingAccount = false,
          accountSetupError = msg,
          statusMessage = msg,
        )
      }
      return
    }

    if (code.isNotEmpty() && handledOAuthCodes.add(code)) {
      activeLoopbackServer?.close()
      activeLoopbackServer = null
      completeMicrosoftOAuthSignIn(
        authorizationCode = code,
        codeVerifier = pending.codeVerifier,
        redirectUri = pending.redirectUri,
        fallbackEmail = pending.emailAddress,
        fallbackDisplayName = pending.displayName,
        initialDelayMs = 150L,
      )
    }
  }

  fun cancelMicrosoftOAuthSignIn() {
    activeLoopbackServer?.close()
    activeLoopbackServer = null
    activeLoopbackRedirectUri = null
    accountStore.clearPendingMicrosoftOAuth()
    _uiState.update {
      it.copy(
        isAwaitingMicrosoftOAuth = false,
        accountSetupError = null,
        statusMessage = "Microsoft sign-in cancelled.",
      )
    }
  }

  override fun onCleared() {
    activeLoopbackServer?.close()
    activeLoopbackServer = null
    super.onCleared()
  }

  fun completeMicrosoftOAuthSignIn(
    authorizationCode: String,
    codeVerifier: String,
    redirectUri: String = MicrosoftOAuthHelper.REDIRECT_URI,
    fallbackEmail: String,
    fallbackDisplayName: String,
    initialDelayMs: Long = 0L,
    onSuccess: () -> Unit = {},
  ) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isAwaitingMicrosoftOAuth = false,
          isVerifyingAccount = true,
          accountSetupError = null,
          statusMessage = "Completing Microsoft sign-in...",
        )
      }
      if (initialDelayMs > 0L) {
        delay(initialDelayMs)
      }
      val tokenResult = MicrosoftOAuthHelper.exchangeAuthorizationCode(
        code = authorizationCode,
        codeVerifier = codeVerifier,
        redirectUri = redirectUri,
      )
      if (tokenResult.isFailure) {
        val reason = tokenResult.exceptionOrNull()?.localizedMessage ?: "Failed to exchange Microsoft OAuth code"
        val errMsg = "Microsoft sign-in failed: $reason"
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            accountSetupError = errMsg,
            statusMessage = errMsg,
          )
        }
        return@launch
      }

      val token = tokenResult.getOrThrow()
      val resolvedEmail = token.emailAddress?.trim()?.takeIf { it.isNotBlank() }
        ?: fallbackEmail.trim()
      if (resolvedEmail.isBlank()) {
        val errMsg = "Please enter your Outlook email address before signing in."
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            accountSetupError = errMsg,
            statusMessage = errMsg,
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

      var verifyResult: Result<Unit> = Result.failure(IllegalStateException("IMAP XOAUTH2 verification failed"))
      for (attempt in 1..3) {
        verifyResult = mailClient.verifyAccount(newAccount)
        if (verifyResult.isSuccess) break
        if (attempt < 3) {
          delay(750L * attempt)
        }
      }

      if (verifyResult.isFailure) {
        val reason = verifyResult.exceptionOrNull()?.localizedMessage ?: "IMAP XOAUTH2 verification failed"
        val errMsg = "Outlook connection failed: $reason"
        _uiState.update {
          it.copy(
            isVerifyingAccount = false,
            accountSetupError = errMsg,
            statusMessage = errMsg,
          )
        }
        return@launch
      }

      accountStore.clearPendingMicrosoftOAuth()
      accountStore.addOrUpdateAccount(newAccount)
      onSuccess()
      _uiState.update {
        it.copy(
          isVerifyingAccount = false,
          accountSetupError = null,
          activePane = ActivePane.Feed,
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
    synchronized(attachmentBytesCache) {
      attachmentBytesCache.keys.removeAll { it.startsWith("$accountId:") }
    }
    synchronized(searchResultsCache) {
      searchResultsCache.clear()
    }
    cachedDayWindowItems = cachedDayWindowItems.filterNot { it.accountId == accountId }
    latestSyncedFeedItems = latestSyncedFeedItems.filterNot { it.accountId == accountId }
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        settingsAndContactsStore.saveCachedDayWindowSummaries(
          items = cachedDayWindowItems,
          syncedAtEpochMillis = cachedDayWindowSyncedAtMillis,
          maxDaysCovered = cachedDayWindowMaxDaysCovered,
        )
        settingsAndContactsStore.saveCachedFeedSummaries(latestSyncedFeedItems)
      }
    }
    persistDetailCacheAsync()
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

  fun reportStatusMessage(message: String) {
    _uiState.update { it.copy(statusMessage = message) }
  }

  fun saveAttachmentToUri(
    detail: EmailDetail,
    attachment: EmailAttachment,
    contentResolver: ContentResolver,
    targetUri: Uri,
  ) {
    val account = accountStore.accounts.value.find { it.id == detail.summary.accountId }
    if (account == null) {
      _uiState.update { it.copy(statusMessage = "Account not found for attachment download.") }
      return
    }
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          downloadingAttachmentPartId = attachment.partId,
          statusMessage = null,
        )
      }
      val cacheKey = "${detail.summary.compositeKey}:${attachment.partId}"
      val saveResult = withContext(Dispatchers.IO) {
        runCatching {
          val bytes = synchronized(attachmentBytesCache) { attachmentBytesCache[cacheKey] }
            ?: mailClient.downloadAttachment(account, detail.summary.uid, attachment.partId)
              .getOrThrow()
              .also { fetched ->
                if (fetched.size <= MAX_IN_MEMORY_ATTACHMENT_BYTES) {
                  synchronized(attachmentBytesCache) {
                    attachmentBytesCache[cacheKey] = fetched
                  }
                }
              }
          contentResolver.openOutputStream(targetUri)?.use { output ->
            output.write(bytes)
          } ?: throw IllegalStateException("Could not open destination file for writing.")
        }
      }
      saveResult.fold(
        onSuccess = {
          _uiState.update {
            it.copy(
              downloadingAttachmentPartId = null,
              statusMessage = "Saved ${attachment.fileName}",
            )
          }
        },
        onFailure = { error ->
          _uiState.update {
            it.copy(
              downloadingAttachmentPartId = null,
              statusMessage = "Failed to save ${attachment.fileName}: ${error.localizedMessage ?: "Download error"}",
            )
          }
        },
      )
    }
  }

  private fun knownAttachmentOverrides(): Map<String, Boolean> =
    synchronized(messageDetailCache) {
      messageDetailCache.mapValues { (_, detail) -> detail.attachments.isNotEmpty() }
    }

  private fun applyKnownAttachmentStates(items: List<EmailSummary>): List<EmailSummary> {
    val overrides = knownAttachmentOverrides()
    if (overrides.isEmpty()) return items
    return items.map { item ->
      val override = overrides[item.compositeKey]
      if (override != null && override != item.hasAttachments) {
        item.copy(hasAttachments = override)
      } else {
        item
      }
    }
  }

  private fun persistDetailCacheAsync() {
    val snapshot = synchronized(messageDetailCache) { messageDetailCache.values.toList() }
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        settingsAndContactsStore.saveCachedEmailDetails(snapshot)
      }
    }
  }

  private fun prefetchSummariesInBackground(items: List<EmailSummary>) {
    val uncached = items.filter { !messageDetailCache.containsKey(it.compositeKey) }
    if (uncached.isEmpty()) return

    val accountsById = accountStore.accounts.value.associateBy { it.id }
    val groupedByAccount = uncached.groupBy { it.accountId }

    viewModelScope.launch {
      var anyPrefetched = false
      coroutineScope {
        groupedByAccount.mapNotNull { (accountId, accountSummaries) ->
          val account = accountsById[accountId] ?: return@mapNotNull null
          val uids = accountSummaries.map { it.uid }.distinct().take(MAX_PREFETCH_PER_ACCOUNT)
          async {
            val prefetched = mailClient.prefetchMessages(account, uids)
            if (prefetched.isNotEmpty()) {
              anyPrefetched = true
            }
            for ((uid, detail) in prefetched) {
              val key = "$accountId:$uid"
              val isFlagged = detail.summary.isFlagged || settingsAndContactsStore.isEmailFlagged(key)
              val hydratedDetail = detail.copy(
                summary = detail.summary.copy(
                  isFlagged = isFlagged,
                  hasAttachments = detail.attachments.isNotEmpty(),
                ),
              )
              messageDetailCache[key] = hydratedDetail
            }
          }
        }.awaitAll()
      }
      if (anyPrefetched) {
        latestSyncedFeedItems = applyKnownAttachmentStates(latestSyncedFeedItems)
        cachedDayWindowItems = applyKnownAttachmentStates(cachedDayWindowItems)
        _uiState.update { state ->
          state.copy(
            feedItems = mergeFeedWithFlagged(
              syncedItems = latestSyncedFeedItems,
              flaggedItems = settingsAndContactsStore.flaggedEmails.value,
              enablePinToFeed = state.settings.enablePinToFeed,
            ),
            searchResults = applyKnownAttachmentStates(state.searchResults),
          )
        }
        persistDetailCacheAsync()
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
    private const val MAX_CACHED_DAY_WINDOW_ITEMS = 200
    private const val MAX_DAY_WINDOW_SEARCH_PER_ACCOUNT = 100
    private const val MAX_PREFETCH_PER_ACCOUNT = 20
    private const val MAX_SEARCH_PREFETCH_ITEMS = 15
    private const val MAX_CACHED_ATTACHMENT_BLOBS = 6
    private const val MAX_IN_MEMORY_ATTACHMENT_BYTES = 5 * 1024 * 1024
    internal const val DAY_WINDOW_CACHE_FRESHNESS_MS = 90_000L
    private const val DAY_WINDOW_WARM_INTERVAL_MS = 120_000L
    private const val GENERAL_SEARCH_CACHE_FRESHNESS_MS = 90_000L

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
