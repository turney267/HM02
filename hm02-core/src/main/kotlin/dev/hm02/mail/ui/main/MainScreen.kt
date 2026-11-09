package dev.hm02.mail.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hm02.mail.data.MicrosoftOAuthHelper
import dev.hm02.mail.data.PkceChallenge
import dev.hm02.mail.data.parseQuickDaySearchDays
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.MailAuthType
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.ui.theme.LocalSurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Material pushpin vector icon used for "Pin to Feed".
 */
private val PushPinIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "PushPin",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
  ).apply {
    path(
      fill = SolidColor(Color.Black),
      fillAlpha = 1f,
      stroke = null,
      strokeAlpha = 1f,
      strokeLineWidth = 1f,
      strokeLineCap = StrokeCap.Butt,
      strokeLineJoin = StrokeJoin.Bevel,
      strokeLineMiter = 1f,
      pathFillType = PathFillType.NonZero,
    ) {
      moveTo(16f, 12f)
      verticalLineTo(4f)
      horizontalLineTo(17f)
      verticalLineTo(2f)
      horizontalLineTo(7f)
      verticalLineTo(4f)
      horizontalLineTo(8f)
      verticalLineTo(12f)
      lineTo(6f, 14f)
      verticalLineTo(16f)
      horizontalLineTo(11.2f)
      verticalLineTo(22f)
      horizontalLineTo(12.8f)
      verticalLineTo(16f)
      horizontalLineTo(18f)
      verticalLineTo(14f)
      lineTo(16f, 12f)
      close()
    }
  }.build()
}

@Composable
fun HM02AppScreen(
  viewModel: HM02ViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val lifecycleOwner = LocalLifecycleOwner.current
  val surfColors = LocalSurfColors.current

  // Refresh the feed every time the user opens/resumes the app (no live auto-expire).
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        viewModel.refreshFeed()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissStatusMessage()
    }
  }

  BackHandler(enabled = uiState.activePane != ActivePane.Feed) {
    when (uiState.activePane) {
      is ActivePane.Reader -> viewModel.closeReader()
      is ActivePane.SearchResults -> viewModel.clearSearchAndReturnToFeed()
      else -> viewModel.showFeed()
    }
  }

  // Reader, Composer, Accounts, and Settings are all 100% full-screen with "blank page" vibes.
  val isFullScreenPane = uiState.activePane !is ActivePane.Feed &&
    uiState.activePane !is ActivePane.SearchResults

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .background(surfColors.pageBg)
      .windowInsetsPadding(WindowInsets.safeDrawing),
    containerColor = surfColors.pageBg,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      if (!isFullScreenPane) {
        HM02TopBar(
          uiState = uiState,
          onSearchQueryChange = viewModel::updateSearchQuery,
          onSelectSearchSuggestion = viewModel::selectSearchSuggestion,
          onSearchSubmit = viewModel::executeSearch,
          onClearSearch = viewModel::clearSearchAndReturnToFeed,
          onRefreshClick = viewModel::refreshFeed,
          onAccountsClick = viewModel::showAccounts,
          onSettingsClick = viewModel::showSettings,
          onFeedClick = viewModel::clearSearchAndReturnToFeed,
        )
      }
    },
    floatingActionButton = {
      if (!isFullScreenPane) {
        SurfPillButton(
          label = "+ Compose",
          isPrimary = true,
          onClick = viewModel::startNewEmail,
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(surfColors.pageBg)
        .padding(innerPadding),
    ) {
      when (val pane = uiState.activePane) {
        ActivePane.Feed -> {
          FeedPane(
            uiState = uiState,
            onEmailClick = viewModel::openMessage,
            onTogglePin = viewModel::toggleFlagged,
            onRefresh = viewModel::refreshFeed,
            onAddAccountClick = viewModel::showAccounts,
          )
        }

        is ActivePane.SearchResults -> {
          SearchResultsPane(
            query = pane.query,
            isSearching = uiState.isSearching,
            results = uiState.searchResults,
            maxSearchDays = uiState.maxSearchDays,
            enablePinToFeed = uiState.settings.enablePinToFeed,
            onEmailClick = viewModel::openMessage,
            onTogglePin = viewModel::toggleFlagged,
            onBackToFeed = viewModel::clearSearchAndReturnToFeed,
          )
        }

        is ActivePane.Reader -> {
          EmailReaderPane(
            summary = pane.summary,
            detail = pane.detail,
            isLoading = pane.isLoading,
            error = pane.error,
            blockRemoteMedia = uiState.blockRemoteMediaInEmails,
            blockExternalImages = uiState.blockExternalImagesByDefault,
            forceBlockExternalImages = uiState.forceBlockExternalImages,
            allowExternalLinks = uiState.allowExternalLinks,
            enablePinToFeed = uiState.settings.enablePinToFeed,
            accessDeviceContacts = uiState.settings.accessDeviceContacts,
            onTogglePin = { viewModel.toggleFlagged(pane.summary) },
            onSaveSenderContact = { viewModel.saveSenderContact(pane.summary) },
            onComposeMailto = viewModel::startComposeFromMailto,
            onBack = viewModel::closeReader,
            onReply = { detail -> viewModel.startReply(detail) },
            onForward = { detail -> viewModel.startForward(detail) },
          )
        }

        is ActivePane.Composer -> {
          ComposeEmailPane(
            accounts = uiState.accounts,
            initialDraft = pane.initialDraft,
            isSending = uiState.isSendingEmail,
            contactSuggestions = uiState.contactSuggestions,
            onContactQueryChange = viewModel::updateContactAutocompleteQuery,
            onClearContactSuggestions = viewModel::clearContactSuggestions,
            onSend = viewModel::sendDraft,
            onCancel = viewModel::showFeed,
          )
        }

        ActivePane.Accounts -> {
          AccountsManagerPane(
            accounts = uiState.accounts,
            isVerifying = uiState.isVerifyingAccount,
            allowCustomServers = uiState.allowCustomServers,
            allowExternalLinks = uiState.allowExternalLinks,
            onSaveAccount = viewModel::verifyAndSaveAccount,
            onCompleteMicrosoftOAuth = viewModel::completeMicrosoftOAuthSignIn,
            onRemoveAccount = viewModel::removeAccount,
            onBack = viewModel::showFeed,
          )
        }

        ActivePane.Settings -> {
          SettingsPane(
            uiState = uiState,
            onToggleAccessContacts = viewModel::updateAccessDeviceContacts,
            onToggleRememberSenders = viewModel::updateRememberSenders,
            onTogglePinToFeed = viewModel::updateEnablePinToFeed,
            onContactsPermissionResult = { granted ->
              viewModel.refreshContactsPermissionState()
              if (granted) {
                viewModel.updateAccessDeviceContacts(true)
              }
            },
            onBack = viewModel::showFeed,
          )
        }
      }
    }
  }
}

@Composable
private fun HM02TopBar(
  uiState: HM02UiState,
  onSearchQueryChange: (String) -> Unit,
  onSelectSearchSuggestion: (ContactSuggestion) -> Unit,
  onSearchSubmit: () -> Unit,
  onClearSearch: () -> Unit,
  onRefreshClick: () -> Unit,
  onAccountsClick: () -> Unit,
  onSettingsClick: () -> Unit,
  onFeedClick: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val surfColors = LocalSurfColors.current
  val isBusy = uiState.isRefreshingFeed || uiState.isSearching || uiState.isSendingEmail || uiState.isVerifyingAccount

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(surfColors.headerBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = uiState.brandTitle,
        color = surfColors.primaryText,
        fontSize = 18.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { onFeedClick() },
      )

      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = onRefreshClick,
          enabled = !uiState.isRefreshingFeed,
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Refresh Feed",
            tint = if (uiState.isRefreshingFeed) surfColors.mutedText else surfColors.primaryText,
          )
        }
        IconButton(onClick = onAccountsClick) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Email Accounts",
            tint = surfColors.primaryText,
          )
        }
        IconButton(onClick = onSettingsClick) {
          Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            tint = surfColors.primaryText,
          )
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
    ) {
      BasicTextField(
        value = uiState.searchQuery,
        onValueChange = onSearchQueryChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            focusManager.clearFocus()
            onSearchSubmit()
          },
        ),
        textStyle = TextStyle(
          color = surfColors.primaryText,
          fontSize = 14.sp,
          fontFamily = FontFamily.SansSerif,
        ),
        cursorBrush = SolidColor(surfColors.primaryText),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(42.dp)
              .background(surfColors.inputBg, RoundedCornerShape(12.dp))
              .border(1.dp, surfColors.inputStroke, RoundedCornerShape(12.dp))
              .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier.weight(1f),
              contentAlignment = Alignment.CenterStart,
            ) {
              if (uiState.searchQuery.isEmpty()) {
                Text(
                  text = "Search all emails (or 'a' today, 'a2' 2d)...",
                  color = surfColors.secondaryText,
                  fontSize = 14.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              innerTextField()
            }
            if (uiState.searchQuery.isNotEmpty()) {
              IconButton(
                onClick = {
                  focusManager.clearFocus()
                  onClearSearch()
                },
                modifier = Modifier.size(34.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Clear,
                  contentDescription = "Clear Search",
                  tint = surfColors.secondaryText,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            IconButton(
              onClick = {
                focusManager.clearFocus()
                onSearchSubmit()
              },
              modifier = Modifier.size(34.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = surfColors.secondaryText,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        },
      )
    }

    // Autosuggest dropdown of remembered senders when typing in the search bar
    if (uiState.searchSuggestions.isNotEmpty()) {
      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(surfColors.cardBg)
          .padding(horizontal = 18.dp),
      ) {
        uiState.searchSuggestions.forEachIndexed { index, suggestion ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                focusManager.clearFocus()
                onSelectSearchSuggestion(suggestion)
              }
              .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = suggestion.formatted,
              style = MaterialTheme.typography.bodySmall,
              color = surfColors.progressBlue,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = "SENDER",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = surfColors.mutedText,
            )
          }
          if (index < uiState.searchSuggestions.lastIndex) {
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
          }
        }
      }
    }

    if (isBusy) {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp),
        color = surfColors.progressBlue,
        trackColor = surfColors.progressTrack,
      )
    } else {
      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPane(
  uiState: HM02UiState,
  onEmailClick: (EmailSummary) -> Unit,
  onTogglePin: (EmailSummary) -> Unit,
  onRefresh: () -> Unit,
  onAddAccountClick: () -> Unit,
) {
  val surfColors = LocalSurfColors.current

  var currentClockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(uiState.lastRefreshedAtMillis) {
    while (true) {
      currentClockMillis = System.currentTimeMillis()
      delay(15_000L)
    }
  }

  if (uiState.accounts.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(surfColors.cardBg)
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "No Email Accounts Configured",
          style = MaterialTheme.typography.titleMedium,
          color = surfColors.primaryText,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "Connect Gmail, Outlook, iCloud, Yahoo, Fastmail, or custom IMAP/SMTP using an App Password.",
          style = MaterialTheme.typography.bodyMedium,
          color = surfColors.secondaryText,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        SurfPillButton(
          label = "Add Email Account",
          isPrimary = true,
          onClick = onAddAccountClick,
        )
      }
    }
    return
  }

  PullToRefreshBox(
    isRefreshing = uiState.isRefreshingFeed,
    onRefresh = onRefresh,
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    if (uiState.feedItems.isEmpty() && !uiState.isRefreshingFeed) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
      ) {
        FeedStatusHeader(
          lastRefreshedAtMillis = uiState.lastRefreshedAtMillis,
          currentClockMillis = currentClockMillis,
          isRefreshing = uiState.isRefreshingFeed,
          failedAccountSyncs = uiState.failedAccountSyncs,
          totalAccountCount = uiState.accounts.size,
        )
        HorizontalDivider(
          thickness = 1.dp,
          color = surfColors.cardStroke,
          modifier = Modifier.padding(horizontal = 18.dp),
        )
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(28.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "Feed is Quiet",
              style = MaterialTheme.typography.titleMedium,
              color = surfColors.primaryText,
              textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = uiState.policyEmptyDescription,
              style = MaterialTheme.typography.bodyMedium,
              color = surfColors.secondaryText,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 84.dp),
      ) {
        item(key = "feed_status_header") {
          FeedStatusHeader(
            lastRefreshedAtMillis = uiState.lastRefreshedAtMillis,
            currentClockMillis = currentClockMillis,
            isRefreshing = uiState.isRefreshingFeed,
            failedAccountSyncs = uiState.failedAccountSyncs,
            totalAccountCount = uiState.accounts.size,
          )
          HorizontalDivider(
            thickness = 1.dp,
            color = surfColors.cardStroke,
            modifier = Modifier.padding(horizontal = 18.dp),
          )
        }
        items(uiState.feedItems, key = { it.compositeKey }) { email ->
          EmailSummaryRow(
            email = email,
            showAccountBadge = uiState.accounts.size > 1,
            showPinAction = uiState.settings.enablePinToFeed,
            onTogglePin = { onTogglePin(email) },
            onClick = { onEmailClick(email) },
          )
          HorizontalDivider(
            thickness = 1.dp,
            color = surfColors.cardStroke,
            modifier = Modifier.padding(horizontal = 18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun FeedStatusHeader(
  lastRefreshedAtMillis: Long?,
  currentClockMillis: Long,
  isRefreshing: Boolean,
  failedAccountSyncs: List<dev.hm02.mail.data.AccountSyncError>,
  totalAccountCount: Int,
) {
  val surfColors = LocalSurfColors.current
  val updatedLabel = when {
    isRefreshing -> "Updating feed..."
    lastRefreshedAtMillis == null -> ""
    else -> "Last updated ${formatElapsedSince(lastRefreshedAtMillis, currentClockMillis)}"
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (updatedLabel.isNotEmpty()) {
      Text(
        text = updatedLabel,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        color = surfColors.mutedText,
      )
    }

    if (failedAccountSyncs.isNotEmpty()) {
      val errorSummary = if (failedAccountSyncs.size >= totalAccountCount) {
        "Sync failed for all accounts (${failedAccountSyncs.first().accountEmail}: ${failedAccountSyncs.first().message})"
      } else {
        "Partial sync failed: ${failedAccountSyncs.joinToString { "${it.accountEmail} (${it.message})" }}"
      }
      Text(
        text = "\u26A0 $errorSummary",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        color = surfColors.alertRed,
      )
    }
  }
}

@Composable
private fun SearchResultsPane(
  query: String,
  isSearching: Boolean,
  results: List<EmailSummary>,
  maxSearchDays: Int,
  enablePinToFeed: Boolean,
  onEmailClick: (EmailSummary) -> Unit,
  onTogglePin: (EmailSummary) -> Unit,
  onBackToFeed: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val quickDays = remember(query, maxSearchDays) { parseQuickDaySearchDays(query, maxDays = maxSearchDays) }
  val headerDescription = if (isSearching) {
    "Searching..."
  } else {
    when (quickDays) {
      1 -> "Today's emails (${results.size})"
      2 -> "Yesterday & today (${results.size})"
      null -> "\"$query\" (${results.size})"
      else -> "Last $quickDays days (${results.size})"
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBackToFeed) {
        Text(
          text = "< Back",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }
      Text(
        text = headerDescription,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = surfColors.secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 4.dp),
      )
    }

    HorizontalDivider(
      thickness = 1.dp,
      color = surfColors.cardStroke,
      modifier = Modifier.padding(horizontal = 18.dp),
    )

    if (isSearching) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = surfColors.progressBlue)
      }
    } else if (results.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(28.dp),
        contentAlignment = Alignment.Center,
      ) {
        val emptyMsg = when (quickDays) {
          1 -> "No emails received today across your connected accounts."
          2 -> "No emails received yesterday or today across your connected accounts."
          null -> "No emails matched \"$query\" across your connected accounts."
          else -> "No emails received in the last $quickDays days across your connected accounts."
        }
        Text(
          text = emptyMsg,
          style = MaterialTheme.typography.bodyMedium,
          color = surfColors.secondaryText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 84.dp),
      ) {
        items(results, key = { it.compositeKey }) { email ->
          EmailSummaryRow(
            email = email,
            showAccountBadge = true,
            showPinAction = enablePinToFeed,
            onTogglePin = { onTogglePin(email) },
            onClick = { onEmailClick(email) },
          )
          HorizontalDivider(
            thickness = 1.dp,
            color = surfColors.cardStroke,
            modifier = Modifier.padding(horizontal = 18.dp),
          )
        }
      }
    }
  }
}

/**
 * Clean, unboxed feed/search email row separated by a 1dp `<hr>` divider matching the read email view.
 */
@Composable
private fun EmailSummaryRow(
  email: EmailSummary,
  showAccountBadge: Boolean,
  showPinAction: Boolean,
  onTogglePin: () -> Unit,
  onClick: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val weight = if (email.isUnread) FontWeight.Bold else FontWeight.Normal

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (email.isUnread) {
          Text(
            text = "\u25CF",
            color = surfColors.progressBlue,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
          )
        }
        Text(
          text = email.fromName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = weight,
          color = surfColors.primaryText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (email.fromAddress.isNotBlank() && !email.fromName.equals(email.fromAddress, ignoreCase = true)) {
          Text(
            text = "<${email.fromAddress}>",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (email.fromName.contains("@")) surfColors.alertRed else surfColors.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = formatTimestamp(email.receivedAtEpochMillis),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          color = surfColors.mutedText,
        )
        if (showPinAction) {
          Icon(
            imageVector = PushPinIcon,
            contentDescription = if (email.isFlagged) "Unpin from feed" else "Pin to feed",
            tint = if (email.isFlagged) surfColors.progressBlue else surfColors.mutedText,
            modifier = Modifier
              .size(18.dp)
              .clickable(onClick = onTogglePin)
              .padding(1.dp),
          )
        }
      }
    }

    Text(
      text = email.subject,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = weight,
      color = surfColors.primaryText,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )

    if (showAccountBadge) {
      Text(
        text = email.accountEmail,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = surfColors.mutedText,
      )
    }
  }
}

@Composable
private fun EmailReaderPane(
  summary: EmailSummary,
  detail: EmailDetail?,
  isLoading: Boolean,
  error: String?,
  blockRemoteMedia: Boolean,
  blockExternalImages: Boolean,
  forceBlockExternalImages: Boolean,
  allowExternalLinks: Boolean,
  enablePinToFeed: Boolean,
  accessDeviceContacts: Boolean,
  onTogglePin: () -> Unit,
  onSaveSenderContact: () -> Unit,
  onComposeMailto: (to: String, cc: String, subject: String, body: String) -> Unit,
  onBack: () -> Unit,
  onReply: (EmailDetail) -> Unit,
  onForward: (EmailDetail) -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val context = LocalContext.current
  var loadRemoteImagesForEmail by remember(summary.compositeKey) { mutableStateOf(false) }
  val effectiveAllowImages = !forceBlockExternalImages && (!blockExternalImages || loadRemoteImagesForEmail)
  val currentEffectiveAllowImages by rememberUpdatedState(effectiveAllowImages)
  val currentAllowExternalLinks by rememberUpdatedState(allowExternalLinks)
  val currentOnComposeMailto by rememberUpdatedState(onComposeMailto)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.headerBg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text(
          text = "< Back",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (enablePinToFeed) {
          IconButton(onClick = onTogglePin) {
            Icon(
              imageVector = PushPinIcon,
              contentDescription = if (summary.isFlagged) "Unpin from feed" else "Pin to feed",
              tint = if (summary.isFlagged) surfColors.progressBlue else surfColors.mutedText,
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (detail != null) {
          SurfPillButton(label = "Reply", onClick = { onReply(detail) })
          SurfPillButton(label = "Forward", onClick = { onForward(detail) })
        }
      }
    }

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

    Column(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = summary.subject,
          style = MaterialTheme.typography.titleMedium,
          color = surfColors.primaryText,
        )

        // Sender block: sender email on a newline under the sender name; when Access Device Contacts is enabled, the email is blue and clickable to save the contact.
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = "From: ${summary.fromName}",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.primaryText,
          )
          Text(
            text = "<${summary.fromAddress}>",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = if (accessDeviceContacts) surfColors.progressBlue else surfColors.primaryText,
            modifier = if (accessDeviceContacts) {
              Modifier.clickable {
                onSaveSenderContact()
                runCatching {
                  val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                    type = ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(ContactsContract.Intents.Insert.NAME, summary.fromName)
                    putExtra(ContactsContract.Intents.Insert.EMAIL, summary.fromAddress)
                  }
                  context.startActivity(intent)
                }
              }
            } else {
              Modifier
            },
          )
        }

        val effectiveReplyTo = detail?.replyToAddress ?: summary.replyToAddress
        if (!effectiveReplyTo.isNullOrBlank() && !effectiveReplyTo.equals(summary.fromAddress, ignoreCase = true)) {
          Text(
            text = "Reply-To: <$effectiveReplyTo>",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = surfColors.alertRed,
          )
        }

        if (detail != null && detail.toAddresses.isNotEmpty()) {
          Text(
            text = "To: ${detail.toAddresses.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.secondaryText,
          )
        }
        if (detail != null && detail.ccAddresses.isNotEmpty()) {
          Text(
            text = "Cc: ${detail.ccAddresses.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.secondaryText,
          )
        }
        Text(
          text = formatFullTimestamp(summary.receivedAtEpochMillis),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.5.sp,
          color = surfColors.mutedText,
        )
      }

      HorizontalDivider(
        thickness = 1.dp,
        color = surfColors.cardStroke,
        modifier = Modifier.padding(horizontal = 18.dp),
      )

      when {
        isLoading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = surfColors.progressBlue)
          }
        }

        error != null -> {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(18.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = error,
              color = surfColors.alertRed,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }

        detail != null -> {
          val htmlBody = detail.htmlBody
          val plainBody = detail.plainTextBody
          if (!htmlBody.isNullOrBlank()) {
            val hasRemoteImages = remember(htmlBody) { containsRemoteImages(htmlBody) }
            val styledHtml by produceState<String?>(
              initialValue = null,
              htmlBody,
              blockRemoteMedia,
              effectiveAllowImages,
            ) {
              value = withContext(Dispatchers.Default) {
                sanitizeAndWrapEmailHtml(
                  rawHtml = htmlBody,
                  stripMediaElements = blockRemoteMedia,
                  allowExternalImages = effectiveAllowImages,
                )
              }
            }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }

            DisposableEffect(Unit) {
              onDispose {
                webViewRef?.apply {
                  stopLoading()
                  loadUrl("about:blank")
                  destroy()
                }
                webViewRef = null
              }
            }

            if (!effectiveAllowImages && !forceBlockExternalImages && hasRemoteImages) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Remote images blocked",
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.5.sp,
                  color = surfColors.mutedText,
                )
                SurfPillButton(
                  label = "Load remote images",
                  onClick = { loadRemoteImagesForEmail = true },
                )
              }
              HorizontalDivider(
                thickness = 1.dp,
                color = surfColors.cardStroke,
                modifier = Modifier.padding(horizontal = 18.dp),
              )
            }

            val readyHtml = styledHtml
            if (readyHtml == null) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = surfColors.progressBlue)
              }
            } else {
              AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                  WebView(ctx).apply {
                    webViewRef = this
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.loadsImagesAutomatically = effectiveAllowImages
                    settings.blockNetworkImage = !effectiveAllowImages
                    settings.blockNetworkLoads = !effectiveAllowImages
                    settings.defaultTextEncodingName = "utf-8"
                    webViewClient = object : WebViewClient() {
                      override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                      ): WebResourceResponse? {
                        val uri = request?.url ?: return blockedWebResourceResponse()
                        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                        if (scheme == "about" || scheme == "cid") {
                          return null
                        }
                        if (scheme == "data") {
                          val sspPrefix = uri.schemeSpecificPart?.take(32)?.lowercase(Locale.US).orEmpty()
                          if (request.isForMainFrame && !request.hasGesture() && sspPrefix.startsWith("text/html")) {
                            return null
                          }
                          return if (sspPrefix.startsWith("image/") && !sspPrefix.startsWith("image/svg")) {
                            null
                          } else {
                            blockedWebResourceResponse()
                          }
                        }
                        if (
                          currentEffectiveAllowImages &&
                          scheme == "https" &&
                          !request.isForMainFrame
                        ) {
                          val accept = request.requestHeaders
                            ?.entries
                            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                            ?.value
                            ?.lowercase(Locale.US)
                            .orEmpty()
                          val pathLower = uri.path?.lowercase(Locale.US).orEmpty()
                          val looksLikeImagePath = pathLower.endsWith(".png") ||
                            pathLower.endsWith(".jpg") ||
                            pathLower.endsWith(".jpeg") ||
                            pathLower.endsWith(".gif") ||
                            pathLower.endsWith(".webp") ||
                            pathLower.endsWith(".bmp") ||
                            pathLower.endsWith(".ico") ||
                            pathLower.endsWith(".avif")
                          val isDisallowedMime = accept.startsWith("text/css") ||
                            accept.startsWith("text/html") ||
                            accept.contains("javascript")
                          if (!isDisallowedMime && (accept.contains("image/") || looksLikeImagePath || accept.isBlank())) {
                            return null
                          }
                        }
                        return blockedWebResourceResponse()
                      }

                      override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                      ): Boolean {
                        if (request == null || !request.isForMainFrame || !request.hasGesture()) {
                          return true
                        }
                        val uri = request.url ?: return true
                        val scheme = uri.scheme?.lowercase(Locale.US) ?: return true
                        if (scheme == "mailto") {
                          val parsed = parseMailtoUri(uri.toString())
                          if (parsed != null) {
                            currentOnComposeMailto(parsed.to, parsed.cc, parsed.subject, parsed.body)
                          }
                          return true
                        }
                        if ((scheme == "https" || scheme == "http") && currentAllowExternalLinks) {
                          try {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                              addCategory(Intent.CATEGORY_BROWSABLE)
                              component = null
                              selector = null
                            }
                            context.startActivity(intent)
                          } catch (_: Exception) {
                          }
                        }
                        return true
                      }
                    }
                  }
                },
                update = { webView ->
                  webViewRef = webView
                  webView.settings.loadsImagesAutomatically = effectiveAllowImages
                  webView.settings.blockNetworkImage = !effectiveAllowImages
                  webView.settings.blockNetworkLoads = !effectiveAllowImages
                  if (webView.tag != readyHtml) {
                    webView.tag = readyHtml
                    webView.loadDataWithBaseURL(
                      "about:blank",
                      readyHtml,
                      "text/html",
                      "UTF-8",
                      null,
                    )
                  }
                },
              )
            }
          } else {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            ) {
              Text(
                text = plainBody ?: "(Empty email body)",
                style = MaterialTheme.typography.bodyMedium,
                color = surfColors.primaryText,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Full-screen Reply / Forward / Compose pane matching the exact "blank page" layout and typography
 * of [EmailReaderPane]: borderless Subject, From (tappable to change sender account), To, and Cc
 * in the top metadata block, a 1dp divider, and a borderless full-screen message body below.
 */
@Composable
private fun ComposeEmailPane(
  accounts: List<EmailAccount>,
  initialDraft: OutgoingEmailDraft,
  isSending: Boolean,
  contactSuggestions: List<ContactSuggestion>,
  onContactQueryChange: (String) -> Unit,
  onClearContactSuggestions: () -> Unit,
  onSend: (OutgoingEmailDraft) -> Unit,
  onCancel: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  var selectedAccount by remember(initialDraft) { mutableStateOf(initialDraft.fromAccount) }
  var showSenderDropdown by remember { mutableStateOf(false) }
  var to by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.to) }
  var cc by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.cc) }
  var subject by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.subject) }
  var activeRecipientField by remember { mutableStateOf("to") }
  var bodyField by remember(initialDraft) {
    mutableStateOf(
      TextFieldValue(
        text = initialDraft.body,
        selection = TextRange(0), // Place cursor at the very top above the 2 blank lines
      ),
    )
  }

  val modeLabel = when (initialDraft.mode) {
    ComposeMode.NEW -> "NEW EMAIL"
    ComposeMode.REPLY -> "REPLY"
    ComposeMode.FORWARD -> "FORWARD"
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    // Top Header Bar matching EmailReaderPane
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.headerBg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onCancel, enabled = !isSending) {
        Text(
          text = "< Back",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }
      Text(
        text = modeLabel,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.5.sp,
        color = surfColors.secondaryText,
      )
      SurfPillButton(
        label = if (isSending) "Sending..." else "Send",
        isPrimary = true,
        enabled = !isSending,
        onClick = {
          onSend(
            initialDraft.copy(
              fromAccount = selectedAccount,
              to = to,
              cc = cc,
              subject = subject,
              body = bodyField.text,
            ),
          )
        },
      )
    }

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

    // Unified Metadata Block matching EmailReaderPane (borderless Subject, From, To, Cc)
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Borderless Subject line matching Reader's titleMedium
      BasicTextField(
        value = subject,
        onValueChange = {
          subject = it
          onClearContactSuggestions()
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
          color = surfColors.primaryText,
        ),
        cursorBrush = SolidColor(surfColors.primaryText),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
          Box(modifier = Modifier.fillMaxWidth()) {
            if (subject.isEmpty()) {
              Text(
                text = "Subject",
                style = MaterialTheme.typography.titleMedium,
                color = surfColors.mutedText,
              )
            }
            innerTextField()
          }
        },
      )

      // Tappable "From:" row to switch sender account on New, Reply, or Forward
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = accounts.size > 1) {
            showSenderDropdown = !showSenderDropdown
          },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "From: ${selectedAccount.displayName.ifBlank { selectedAccount.emailAddress }} ",
          style = MaterialTheme.typography.bodySmall,
          color = surfColors.primaryText,
        )
        Text(
          text = if (accounts.size > 1) "<${selectedAccount.emailAddress}> \u25BE" else "<${selectedAccount.emailAddress}>",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = if (accounts.size > 1) FontWeight.SemiBold else FontWeight.Normal,
          color = if (accounts.size > 1) surfColors.progressBlue else surfColors.primaryText,
        )
      }

      if (showSenderDropdown && accounts.size > 1) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        ) {
          accounts.forEach { account ->
            val isSelected = account.id == selectedAccount.id
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  selectedAccount = account
                  showSenderDropdown = false
                }
                .padding(vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "${account.displayName.ifBlank { account.emailAddress }} <${account.emailAddress}>",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) surfColors.progressBlue else surfColors.secondaryText,
              )
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "Selected",
                  tint = surfColors.progressBlue,
                  modifier = Modifier.size(15.dp),
                )
              }
            }
          }
        }
      }

      // Borderless inline "To:" row matching Reader's "To: ..."
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "To: ",
          style = MaterialTheme.typography.bodySmall,
          color = surfColors.secondaryText,
        )
        BasicTextField(
          value = to,
          onValueChange = {
            to = it
            activeRecipientField = "to"
            onContactQueryChange(it)
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
          textStyle = MaterialTheme.typography.bodySmall.copy(
            color = surfColors.primaryText,
          ),
          cursorBrush = SolidColor(surfColors.primaryText),
          modifier = Modifier.weight(1f),
          decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
              if (to.isEmpty()) {
                Text(
                  text = "recipient@example.com",
                  style = MaterialTheme.typography.bodySmall,
                  color = surfColors.mutedText,
                )
              }
              innerTextField()
            }
          },
        )
      }

      // Borderless inline "Cc:" row matching Reader's "Cc: ..."
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Cc: ",
          style = MaterialTheme.typography.bodySmall,
          color = surfColors.secondaryText,
        )
        BasicTextField(
          value = cc,
          onValueChange = {
            cc = it
            activeRecipientField = "cc"
            onContactQueryChange(it)
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
          textStyle = MaterialTheme.typography.bodySmall.copy(
            color = surfColors.primaryText,
          ),
          cursorBrush = SolidColor(surfColors.primaryText),
          modifier = Modifier.weight(1f),
          decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
              if (cc.isEmpty()) {
                Text(
                  text = "optional",
                  style = MaterialTheme.typography.bodySmall,
                  color = surfColors.mutedText,
                )
              }
              innerTextField()
            }
          },
        )
      }

      if (contactSuggestions.isNotEmpty()) {
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
        Column(modifier = Modifier.fillMaxWidth()) {
          contactSuggestions.forEach { suggestion ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  if (activeRecipientField == "cc") {
                    cc = replaceLastRecipientToken(cc, suggestion.formatted)
                  } else {
                    to = replaceLastRecipientToken(to, suggestion.formatted)
                  }
                  onClearContactSuggestions()
                }
                .padding(vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = suggestion.formatted,
                style = MaterialTheme.typography.bodySmall,
                color = surfColors.progressBlue,
              )
              Text(
                text = suggestion.source.uppercase(Locale.US),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = surfColors.mutedText,
              )
            }
          }
        }
      }
    }

    HorizontalDivider(
      thickness = 1.dp,
      color = surfColors.cardStroke,
      modifier = Modifier.padding(horizontal = 18.dp),
    )

    // Full-screen borderless message body matching EmailReaderPane's body area
    BasicTextField(
      value = bodyField,
      onValueChange = { bodyField = it },
      textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = surfColors.primaryText,
        lineHeight = 22.sp,
      ),
      cursorBrush = SolidColor(surfColors.primaryText),
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(18.dp),
      decorationBox = { innerTextField ->
        Box(modifier = Modifier.fillMaxSize()) {
          if (bodyField.text.isEmpty()) {
            Text(
              text = "Write your message...",
              style = MaterialTheme.typography.bodyMedium,
              color = surfColors.mutedText,
            )
          }
          innerTextField()
        }
      },
    )
  }
}

/**
 * Full-screen Settings page with "blank page" vibes matching [EmailReaderPane],
 * containing the three toggle options without helper text:
 * 1. Access Device Contacts
 * 2. Remember Senders
 * 3. Enable Pin to Feed
 */
@Composable
private fun SettingsPane(
  uiState: HM02UiState,
  onToggleAccessContacts: (Boolean) -> Unit,
  onToggleRememberSenders: (Boolean) -> Unit,
  onTogglePinToFeed: (Boolean) -> Unit,
  onContactsPermissionResult: (Boolean) -> Unit,
  onBack: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val settings = uiState.settings

  val contactsPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = onContactsPermissionResult,
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.headerBg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text(
          text = "< Back",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }
      Text(
        text = "SETTINGS",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = surfColors.primaryText,
        modifier = Modifier.padding(end = 8.dp),
      )
    }

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Access Device Contacts",
          style = MaterialTheme.typography.bodyLarge,
          color = surfColors.primaryText,
        )
        Switch(
          checked = settings.accessDeviceContacts,
          onCheckedChange = { enabled ->
            if (enabled && !uiState.hasContactsPermission) {
              contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
              onToggleAccessContacts(enabled)
            }
          },
          colors = SwitchDefaults.colors(
            checkedThumbColor = surfColors.activePillText,
            checkedTrackColor = surfColors.activePillBg,
          ),
        )
      }

      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Remember Senders",
          style = MaterialTheme.typography.bodyLarge,
          color = surfColors.primaryText,
        )
        Switch(
          checked = settings.rememberSenders,
          onCheckedChange = onToggleRememberSenders,
          colors = SwitchDefaults.colors(
            checkedThumbColor = surfColors.activePillText,
            checkedTrackColor = surfColors.activePillBg,
          ),
        )
      }

      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Enable Pin to Feed",
          style = MaterialTheme.typography.bodyLarge,
          color = surfColors.primaryText,
        )
        Switch(
          checked = settings.enablePinToFeed,
          onCheckedChange = onTogglePinToFeed,
          colors = SwitchDefaults.colors(
            checkedThumbColor = surfColors.activePillText,
            checkedTrackColor = surfColors.activePillBg,
          ),
        )
      }

      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

      val policyConfig = dev.hm02.mail.policy.MailPolicyProvider.getConfig()
      val updateHandler = policyConfig.onCheckForAppUpdate
      if (updateHandler != null) {
        val context = LocalContext.current
        val versionName = remember(context) {
          try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
          } catch (_: Exception) {
            "1.0"
          }
        }
        var isUpdating by remember { mutableStateOf(false) }
        var updateStatusText by remember { mutableStateOf("${policyConfig.brandTitle} v$versionName") }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = "App Update",
              style = MaterialTheme.typography.bodyLarge,
              color = surfColors.primaryText,
            )
            Text(
              text = updateStatusText,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = surfColors.secondaryText,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
          SurfPillButton(
            label = if (isUpdating) "Checking..." else "Update App",
            enabled = !isUpdating,
            onClick = {
              isUpdating = true
              updateStatusText = "Checking for updates..."
              updateHandler(
                context,
                { status: String -> updateStatusText = status },
                { _: Boolean, finalMessage: String ->
                  isUpdating = false
                  updateStatusText = finalMessage
                },
              )
            },
          )
        }

        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
      }
    }
  }
}

/**
 * Full-screen Accounts page with "blank page" vibes matching [EmailReaderPane].
 */
@Composable
private fun AccountsManagerPane(
  accounts: List<EmailAccount>,
  isVerifying: Boolean,
  allowCustomServers: Boolean,
  allowExternalLinks: Boolean,
  onSaveAccount: (EmailAccount, Boolean, () -> Unit) -> Unit,
  onCompleteMicrosoftOAuth: (
    authorizationCode: String,
    codeVerifier: String,
    fallbackEmail: String,
    fallbackDisplayName: String,
    onSuccess: () -> Unit,
  ) -> Unit,
  onRemoveAccount: (String) -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val surfColors = LocalSurfColors.current

  var isAddingAccount by rememberSaveable(accounts.isEmpty()) {
    mutableStateOf(accounts.isEmpty())
  }
  var selectedPreset by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL) }
  var displayName by rememberSaveable { mutableStateOf("") }
  var emailAddress by rememberSaveable { mutableStateOf("") }
  var appPassword by remember { mutableStateOf("") }
  var imapHost by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.imapHost) }
  var imapPort by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.imapPort.toString()) }
  var smtpHost by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.smtpHost) }
  var smtpPort by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.smtpPort.toString()) }
  var activeMicrosoftPkce by remember { mutableStateOf<PkceChallenge?>(null) }
  var oauthErrorBanner by remember { mutableStateOf<String?>(null) }

  val availablePresets = remember(allowCustomServers) {
    if (allowCustomServers) {
      MailProviderPreset.entries
    } else {
      MailProviderPreset.entries.filter { it != MailProviderPreset.CUSTOM }
    }
  }

  LaunchedEffect(allowCustomServers) {
    if (!allowCustomServers && selectedPreset == MailProviderPreset.CUSTOM) {
      selectedPreset = MailProviderPreset.GMAIL
      imapHost = MailProviderPreset.GMAIL.imapHost
      imapPort = MailProviderPreset.GMAIL.imapPort.toString()
      smtpHost = MailProviderPreset.GMAIL.smtpHost
      smtpPort = MailProviderPreset.GMAIL.smtpPort.toString()
    } else if (!allowCustomServers) {
      imapHost = selectedPreset.imapHost
      imapPort = selectedPreset.imapPort.toString()
      smtpHost = selectedPreset.smtpHost
      smtpPort = selectedPreset.smtpPort.toString()
    }
  }

  val activePkce = activeMicrosoftPkce
  if (activePkce != null) {
    MicrosoftOAuthSignInSheet(
      pkce = activePkce,
      loginHint = emailAddress.trim(),
      onDismiss = { activeMicrosoftPkce = null },
      onAuthCodeReceived = { code ->
        val verifier = activePkce.codeVerifier
        activeMicrosoftPkce = null
        oauthErrorBanner = null
        onCompleteMicrosoftOAuth(
          code,
          verifier,
          emailAddress.trim(),
          displayName.trim(),
        ) {
          appPassword = ""
          emailAddress = ""
          displayName = ""
          isAddingAccount = false
        }
      },
      onAuthError = { errMsg ->
        activeMicrosoftPkce = null
        oauthErrorBanner = errMsg
      },
    )
    return
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.headerBg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text(
          text = "< Back",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = "EMAIL ACCOUNTS (${accounts.size})",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
        IconButton(onClick = { isAddingAccount = !isAddingAccount }) {
          Icon(
            imageVector = if (isAddingAccount && accounts.isNotEmpty()) Icons.Default.Clear else Icons.Default.Add,
            contentDescription = "Add Email Account",
            tint = surfColors.primaryText,
          )
        }
      }
    }

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp),
    ) {
      accounts.forEach { account ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = account.displayName.ifBlank { account.emailAddress },
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
              color = surfColors.primaryText,
            )
            Text(
              text = "${account.emailAddress} • ${account.imapHost}",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.5.sp,
              color = surfColors.secondaryText,
            )
          }
          TextButton(onClick = { onRemoveAccount(account.id) }) {
            Text(
              text = "Remove",
              color = surfColors.alertRed,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
      }

      if (isAddingAccount) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "SELECT EMAIL PROVIDER",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.5.sp,
          color = surfColors.mutedText,
        )
        Spacer(modifier = Modifier.height(6.dp))

        availablePresets.forEach { preset ->
          val isSelected = selectedPreset == preset
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                selectedPreset = preset
                imapHost = preset.imapHost
                imapPort = preset.imapPort.toString()
                smtpHost = preset.smtpHost
                smtpPort = preset.smtpPort.toString()
              }
              .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = preset.label,
              fontSize = 14.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
              color = surfColors.primaryText,
            )
            if (isSelected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = surfColors.progressBlue,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = selectedPreset.setupHint,
          style = MaterialTheme.typography.bodySmall,
          color = surfColors.secondaryText,
        )
        val url = selectedPreset.appPasswordUrl
        if (url != null && allowExternalLinks) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Open ${selectedPreset.label} App Passwords \u2197",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = surfColors.progressBlue,
            modifier = Modifier.clickable {
              runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                  addCategory(Intent.CATEGORY_BROWSABLE)
                  component = null
                  selector = null
                }
                context.startActivity(intent)
              }
            },
          )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

        BlankPageFieldRow(
          label = "Name: ",
          value = displayName,
          onValueChange = { displayName = it },
          placeholder = "Work, Personal",
        )
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

        BlankPageFieldRow(
          label = "Email: ",
          value = emailAddress,
          onValueChange = { emailAddress = it },
          placeholder = if (selectedPreset == MailProviderPreset.OUTLOOK) "you@outlook.com (optional)" else "you@example.com",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

        if (selectedPreset.authType == MailAuthType.OAUTH2_MICROSOFT) {
          if (!oauthErrorBanner.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = oauthErrorBanner.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = surfColors.alertRed,
            )
          }
          Spacer(modifier = Modifier.height(16.dp))
          SurfPillButton(
            label = if (isVerifying) "Signing in..." else "Sign in with Microsoft",
            isPrimary = true,
            enabled = !isVerifying,
            onClick = {
              oauthErrorBanner = null
              activeMicrosoftPkce = MicrosoftOAuthHelper.generatePkceChallenge()
            },
          )
          Spacer(modifier = Modifier.height(24.dp))
        } else {
          BlankPageFieldRow(
            label = "App Password: ",
            value = appPassword,
            onValueChange = { appPassword = it },
            placeholder = "16-character password",
            isPassword = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          )
          HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

          if (allowCustomServers) {
            BlankPageFieldRow(
              label = "IMAP Host: ",
              value = imapHost,
              onValueChange = { imapHost = it },
              placeholder = "imap.example.com",
            )
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

            BlankPageFieldRow(
              label = "IMAP Port: ",
              value = imapPort,
              onValueChange = { imapPort = it },
              placeholder = "993",
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

            BlankPageFieldRow(
              label = "SMTP Host: ",
              value = smtpHost,
              onValueChange = { smtpHost = it },
              placeholder = "smtp.example.com",
            )
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

            BlankPageFieldRow(
              label = "SMTP Port: ",
              value = smtpPort,
              onValueChange = { smtpPort = it },
              placeholder = "465",
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
          }

          Spacer(modifier = Modifier.height(16.dp))

          val effectiveImapHost = if (allowCustomServers) imapHost.trim() else selectedPreset.imapHost
          val effectiveImapPort = if (allowCustomServers) (imapPort.toIntOrNull() ?: 993) else selectedPreset.imapPort
          val effectiveSmtpHost = if (allowCustomServers) smtpHost.trim() else selectedPreset.smtpHost
          val effectiveSmtpPort = if (allowCustomServers) (smtpPort.toIntOrNull() ?: 465) else selectedPreset.smtpPort

          val canSubmit = !isVerifying &&
            emailAddress.isNotBlank() &&
            appPassword.isNotBlank() &&
            effectiveImapHost.isNotBlank() &&
            effectiveSmtpHost.isNotBlank()

          SurfPillButton(
            label = if (isVerifying) "Verifying..." else "Verify & Add Account",
            isPrimary = true,
            enabled = canSubmit,
            onClick = {
              val submittedPassword = if (selectedPreset == MailProviderPreset.CUSTOM) {
                appPassword
              } else {
                appPassword.trim()
              }
              val newAccount = EmailAccount(
                displayName = displayName.ifBlank { emailAddress.trim() },
                emailAddress = emailAddress.trim(),
                imapHost = effectiveImapHost,
                imapPort = effectiveImapPort,
                smtpHost = effectiveSmtpHost,
                smtpPort = effectiveSmtpPort,
                appPassword = submittedPassword,
                preset = selectedPreset,
              )
              onSaveAccount(newAccount, false) {
                appPassword = ""
                emailAddress = ""
                displayName = ""
                isAddingAccount = false
              }
            },
          )
          Spacer(modifier = Modifier.height(24.dp))
        }
      }
    }
  }
}

@Composable
private fun MicrosoftOAuthSignInSheet(
  pkce: PkceChallenge,
  loginHint: String,
  onDismiss: () -> Unit,
  onAuthCodeReceived: (String) -> Unit,
  onAuthError: (String) -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val authorizeUrl = remember(pkce, loginHint) {
    MicrosoftOAuthHelper.buildAuthorizationUrl(pkce = pkce, loginHint = loginHint)
  }
  var isPageLoading by remember { mutableStateOf(true) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  BackHandler {
    onDismiss()
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewRef?.apply {
        stopLoading()
        loadUrl("about:blank")
        destroy()
      }
      webViewRef = null
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.cardBg),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.headerBg)
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onDismiss) {
        Text(
          text = "< Cancel",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          color = surfColors.primaryText,
        )
      }
      Text(
        text = "MICROSOFT SIGN IN",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = surfColors.primaryText,
        modifier = Modifier.padding(end = 8.dp),
      )
    }

    if (isPageLoading) {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp),
        color = surfColors.progressBlue,
        trackColor = surfColors.progressTrack,
      )
    } else {
      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
    }

    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        WebView(ctx).apply {
          webViewRef = this
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.allowFileAccess = false
          settings.allowContentAccess = false
          webViewClient = object : WebViewClient() {
            private var handledRedirect = false

            private fun handlePotentialRedirect(uri: Uri?): Boolean {
              if (handledRedirect || uri == null) return false
              val fullUrl = uri.toString()
              if (!MicrosoftOAuthHelper.isRedirectUri(fullUrl)) return false
              handledRedirect = true

              val returnedState = uri.getQueryParameter("state")
              if (!returnedState.isNullOrBlank() && returnedState != pkce.state) {
                onAuthError("OAuth state mismatch. Please try signing in again.")
                return true
              }

              val code = uri.getQueryParameter("code")
              if (!code.isNullOrBlank()) {
                onAuthCodeReceived(code)
                return true
              }

              val errDesc = uri.getQueryParameter("error_description")
                ?: uri.getQueryParameter("error")
                ?: "Microsoft sign-in was cancelled."
              onAuthError(errDesc)
              return true
            }

            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?,
            ): Boolean {
              val uri = request?.url ?: return false
              if (handlePotentialRedirect(uri)) {
                return true
              }
              return false
            }

            override fun onPageStarted(
              view: WebView?,
              url: String?,
              favicon: android.graphics.Bitmap?,
            ) {
              super.onPageStarted(view, url, favicon)
              isPageLoading = true
              if (url != null) {
                handlePotentialRedirect(Uri.parse(url))
              }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              isPageLoading = false
            }
          }
          loadUrl(authorizeUrl)
        }
      },
      update = { webView ->
        webViewRef = webView
      },
    )
  }
}

@Composable
private fun BlankPageFieldRow(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  isPassword: Boolean = false,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
  val surfColors = LocalSurfColors.current
  val visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = surfColors.secondaryText,
    )
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      visualTransformation = visualTransformation,
      keyboardOptions = keyboardOptions,
      textStyle = MaterialTheme.typography.bodySmall.copy(
        color = surfColors.primaryText,
      ),
      cursorBrush = SolidColor(surfColors.primaryText),
      modifier = Modifier.weight(1f),
      decorationBox = { innerTextField ->
        Box(modifier = Modifier.fillMaxWidth()) {
          if (value.isEmpty()) {
            Text(
              text = placeholder,
              style = MaterialTheme.typography.bodySmall,
              color = surfColors.mutedText,
            )
          }
          innerTextField()
        }
      },
    )
  }
}

@Composable
fun SurfPillButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  isPrimary: Boolean = false,
) {
  val surfColors = LocalSurfColors.current
  val bg = when {
    !enabled -> surfColors.inputBg
    isPrimary -> surfColors.activePillBg
    else -> surfColors.pillBg
  }
  val textColor = when {
    !enabled -> surfColors.mutedText
    isPrimary -> surfColors.activePillText
    else -> surfColors.primaryText
  }
  val strokeColor = if (isPrimary) surfColors.activePillBg else surfColors.pillStroke

  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(999.dp))
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(999.dp),
    color = bg,
    border = BorderStroke(1.dp, strokeColor),
  ) {
    Text(
      text = label,
      color = textColor,
      fontSize = 13.5.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
    )
  }
}

@Composable
fun SurfCard(
  modifier: Modifier = Modifier,
  padding: Dp = 22.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val surfColors = LocalSurfColors.current
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    color = surfColors.cardBg,
    border = BorderStroke(1.dp, surfColors.cardStroke),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(padding),
      content = content,
    )
  }
}

private fun replaceLastRecipientToken(currentValue: String, selectedFormatted: String): String {
  val lastComma = currentValue.lastIndexOf(',')
  return if (lastComma < 0) {
    "$selectedFormatted, "
  } else {
    val prefix = currentValue.substring(0, lastComma + 1).trimEnd()
    "$prefix $selectedFormatted, "
  }
}

private val ALWAYS_BLOCKED_HTML_TAGS =
  "script|iframe|object|embed|applet|form|meta|base|link|portal|frame|frameset"
private val MEDIA_HTML_TAGS = "video|audio"

private val PAIRED_DANGEROUS_TAGS_WITH_MEDIA_REGEX =
  Regex("(?is)<($ALWAYS_BLOCKED_HTML_TAGS|$MEDIA_HTML_TAGS)\\b[^>]*>.*?</\\1\\s*>")
private val SINGLE_DANGEROUS_TAGS_WITH_MEDIA_REGEX =
  Regex("(?is)</?($ALWAYS_BLOCKED_HTML_TAGS|$MEDIA_HTML_TAGS)\\b[^>]*>")
private val EOF_DANGEROUS_TAGS_WITH_MEDIA_REGEX =
  Regex("(?is)</?($ALWAYS_BLOCKED_HTML_TAGS|$MEDIA_HTML_TAGS)\\b[^>]*$")

private val PAIRED_DANGEROUS_TAGS_NO_MEDIA_REGEX =
  Regex("(?is)<($ALWAYS_BLOCKED_HTML_TAGS)\\b[^>]*>.*?</\\1\\s*>")
private val SINGLE_DANGEROUS_TAGS_NO_MEDIA_REGEX =
  Regex("(?is)</?($ALWAYS_BLOCKED_HTML_TAGS)\\b[^>]*>")
private val EOF_DANGEROUS_TAGS_NO_MEDIA_REGEX =
  Regex("(?is)</?($ALWAYS_BLOCKED_HTML_TAGS)\\b[^>]*$")

private val INLINE_EVENT_ATTR_REGEX =
  Regex("(?is)\\s+on[a-z0-9_]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
private val SRCDOC_ATTR_REGEX =
  Regex("(?is)\\s+srcdoc\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
private const val HTML_WS_OR_ENTITY = "(?:\\s|&#0*(?:9|10|13|32);?|&#x0*(?:9|a|d|20);?)*"
private const val HTML_COLON_OR_ENTITY = "(?::|&#0*58;?|&#x0*3a;?)"
private val DANGEROUS_SCRIPT_SCHEME_REGEX = Regex(
  "(?is)(j${HTML_WS_OR_ENTITY}a${HTML_WS_OR_ENTITY}v${HTML_WS_OR_ENTITY}a${HTML_WS_OR_ENTITY}s${HTML_WS_OR_ENTITY}c${HTML_WS_OR_ENTITY}r${HTML_WS_OR_ENTITY}i${HTML_WS_OR_ENTITY}p${HTML_WS_OR_ENTITY}t|" +
    "v${HTML_WS_OR_ENTITY}b${HTML_WS_OR_ENTITY}s${HTML_WS_OR_ENTITY}c${HTML_WS_OR_ENTITY}r${HTML_WS_OR_ENTITY}i${HTML_WS_OR_ENTITY}p${HTML_WS_OR_ENTITY}t)" +
    "${HTML_WS_OR_ENTITY}${HTML_COLON_OR_ENTITY}",
)
private val DANGEROUS_DATA_HTML_REGEX = Regex(
  "(?is)d${HTML_WS_OR_ENTITY}a${HTML_WS_OR_ENTITY}t${HTML_WS_OR_ENTITY}a${HTML_WS_OR_ENTITY}${HTML_COLON_OR_ENTITY}${HTML_WS_OR_ENTITY}text${HTML_WS_OR_ENTITY}/${HTML_WS_OR_ENTITY}html",
)
private val REMOTE_IMAGE_HINT_REGEX =
  Regex("(?is)(<img\\b[^>]*\\b(src|srcset)\\s*=\\s*['\"]?https?://|\\b(src|srcset)\\s*=\\s*['\"]?https?://|url\\s*\\(\\s*['\"]?https?://)")

private fun blockedWebResourceResponse(): WebResourceResponse =
  WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
  )

internal fun containsRemoteImages(rawHtml: String): Boolean =
  REMOTE_IMAGE_HINT_REGEX.containsMatchIn(rawHtml)

internal fun sanitizeEmailHtml(
  rawHtml: String,
  stripMediaElements: Boolean = true,
): String {
  val pairedRegex = if (stripMediaElements) {
    PAIRED_DANGEROUS_TAGS_WITH_MEDIA_REGEX
  } else {
    PAIRED_DANGEROUS_TAGS_NO_MEDIA_REGEX
  }
  val singleRegex = if (stripMediaElements) {
    SINGLE_DANGEROUS_TAGS_WITH_MEDIA_REGEX
  } else {
    SINGLE_DANGEROUS_TAGS_NO_MEDIA_REGEX
  }
  val eofRegex = if (stripMediaElements) {
    EOF_DANGEROUS_TAGS_WITH_MEDIA_REGEX
  } else {
    EOF_DANGEROUS_TAGS_NO_MEDIA_REGEX
  }

  var current = rawHtml.replace("\u0000", "")
  for (iteration in 0 until 5) {
    val next = current
      .replace(pairedRegex, "")
      .replace(singleRegex, "")
      .replace(eofRegex, "")
      .replace(INLINE_EVENT_ATTR_REGEX, "")
      .replace(SRCDOC_ATTR_REGEX, "")
      .replace(DANGEROUS_SCRIPT_SCHEME_REGEX, "blocked:")
      .replace(DANGEROUS_DATA_HTML_REGEX, "blocked:text/plain")
    if (next == current) {
      break
    }
    current = next
  }
  return current
}

internal fun sanitizeAndWrapEmailHtml(
  rawHtml: String,
  stripMediaElements: Boolean = true,
  allowExternalImages: Boolean = false,
): String {
  val sanitized = sanitizeEmailHtml(rawHtml, stripMediaElements)
  val imgSrcAllowance = if (allowExternalImages) " https:" else ""
  val csp =
    "default-src 'none'; style-src 'unsafe-inline'; img-src data: cid:$imgSrcAllowance; " +
      "media-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none';"
  return """
    <!DOCTYPE html>
    <html>
    <head>
      <meta http-equiv="Content-Security-Policy" content="$csp" />
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <style>
        :root { color-scheme: light dark; }
        body {
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
          font-size: 15px;
          line-height: 1.55;
          color: #09090b;
          background-color: transparent;
          padding: 18px;
          margin: 0;
          overflow-wrap: break-word;
          word-wrap: break-word;
        }
        @media (prefers-color-scheme: dark) {
          body {
            color: #fafafa;
            background-color: transparent;
          }
          a { color: #60a5fa; }
        }
        img { max-width: 100%; height: auto; border-radius: 8px; }
        pre, code { font-family: monospace; font-size: 13px; white-space: pre-wrap; }
        blockquote {
          margin: 8px 0;
          padding-left: 12px;
          border-left: 2px solid #71717a;
          color: #71717a;
        }
      </style>
    </head>
    <body>
      $sanitized
    </body>
    </html>
  """.trimIndent()
}

internal fun wrapEmailHtmlForReader(rawHtml: String, stripMediaElements: Boolean): String =
  sanitizeAndWrapEmailHtml(
    rawHtml = rawHtml,
    stripMediaElements = stripMediaElements,
    allowExternalImages = false,
  )

internal fun formatElapsedSince(lastRefreshedAtMillis: Long, currentClockMillis: Long): String {
  val diffSeconds = ((currentClockMillis - lastRefreshedAtMillis) / 1_000L).coerceAtLeast(0L)
  val diffMinutes = diffSeconds / 60L
  return when {
    diffMinutes < 1L -> "just now"
    diffMinutes == 1L -> "1 minute ago"
    diffMinutes < 60L -> "$diffMinutes minutes ago"
    else -> "${diffMinutes / 60L}h ago"
  }
}

private fun formatTimestamp(epochMillis: Long): String {
  if (epochMillis <= 0L) return ""
  val now = System.currentTimeMillis()
  val diffMinutes = (now - epochMillis) / 60_000L
  return when {
    diffMinutes < 1 -> "NOW"
    diffMinutes < 60 -> "${diffMinutes}M AGO"
    diffMinutes < 24 * 60 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis)).uppercase(Locale.US)
  }
}

private fun formatFullTimestamp(epochMillis: Long): String {
  if (epochMillis <= 0L) return ""
  return SimpleDateFormat("EEE, MMM d, yyyy • HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
