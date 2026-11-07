package dev.hm02.mail.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import dev.hm02.mail.data.parseQuickDaySearchDays
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.ui.theme.LocalSurfColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    viewModel.showFeed()
  }

  // Opening an email (Reader) or composing/replying/forwarding (Composer) is 100% full-screen.
  val isFullScreenPane = uiState.activePane is ActivePane.Reader || uiState.activePane is ActivePane.Composer

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
          onSearchSubmit = viewModel::executeSearch,
          onClearSearch = viewModel::clearSearchAndReturnToFeed,
          onRefreshClick = viewModel::refreshFeed,
          onAccountsClick = viewModel::showAccounts,
          onSettingsClick = viewModel::showSettings,
          onFeedClick = viewModel::showFeed,
        )
      }
    },
    floatingActionButton = {
      if (uiState.activePane is ActivePane.Feed || uiState.activePane is ActivePane.SearchResults) {
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
            onToggleFlag = viewModel::toggleFlagged,
            onAddAccountClick = viewModel::showAccounts,
          )
        }

        is ActivePane.SearchResults -> {
          SearchResultsPane(
            query = pane.query,
            isSearching = uiState.isSearching,
            results = uiState.searchResults,
            enableFlagForLater = uiState.settings.enableFlagForLater,
            onEmailClick = viewModel::openMessage,
            onToggleFlag = viewModel::toggleFlagged,
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
            enableFlagForLater = uiState.settings.enableFlagForLater,
            saveContactsFromEmails = uiState.settings.saveContactsFromEmails,
            onToggleFlag = { viewModel.toggleFlagged(pane.summary) },
            onSaveSenderContact = { viewModel.saveSenderContact(pane.summary) },
            onBack = viewModel::showFeed,
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
            onSaveAccount = viewModel::verifyAndSaveAccount,
            onRemoveAccount = viewModel::removeAccount,
            onBack = viewModel::showFeed,
          )
        }

        ActivePane.Settings -> {
          SettingsPane(
            uiState = uiState,
            onToggleAccessContacts = viewModel::updateAccessDeviceContacts,
            onToggleSaveContactsFromEmails = viewModel::updateSaveContactsFromEmails,
            onToggleFlagForLater = viewModel::updateEnableFlagForLater,
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
    // Minimalist Header Row: Brand Title on Left, Refresh, Accounts & Settings IconButtons on Right
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

    // Search Input Box with embedded Clear and Search icons (supports "a" = today, "a2" = yesterday & today)
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

    // 3dp Slim Horizontal Progress Bar matching HM03 IntentBrowserActivity
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

@Composable
private fun FeedPane(
  uiState: HM02UiState,
  onEmailClick: (EmailSummary) -> Unit,
  onToggleFlag: (EmailSummary) -> Unit,
  onAddAccountClick: () -> Unit,
) {
  val surfColors = LocalSurfColors.current

  // Tick every 5 seconds while the screen is open so "Last updated 27s ago" stays accurate
  var currentClockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(uiState.lastRefreshedAtMillis) {
    while (true) {
      currentClockMillis = System.currentTimeMillis()
      delay(5_000L)
    }
  }

  if (uiState.accounts.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      SurfCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "No Email Accounts Configured",
          style = MaterialTheme.typography.titleMedium,
          color = surfColors.primaryText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "Connect Gmail, Outlook, iCloud, Yahoo, Fastmail, or custom IMAP/SMTP using an App Password.",
          style = MaterialTheme.typography.bodyMedium,
          color = surfColors.secondaryText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          SurfPillButton(
            label = "Add Email Account",
            isPrimary = true,
            onClick = onAddAccountClick,
          )
        }
      }
    }
    return
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Subtle "Last updated Xs ago" + partial/full sync error indicator at the top of the feed
    FeedStatusHeader(
      lastRefreshedAtMillis = uiState.lastRefreshedAtMillis,
      currentClockMillis = currentClockMillis,
      isRefreshing = uiState.isRefreshingFeed,
      failedAccountSyncs = uiState.failedAccountSyncs,
      totalAccountCount = uiState.accounts.size,
    )

    if (uiState.feedItems.isEmpty() && !uiState.isRefreshingFeed) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        SurfCard(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "Feed is Quiet",
            style = MaterialTheme.typography.titleMedium,
            color = surfColors.primaryText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = uiState.policyEmptyDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = surfColors.secondaryText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(uiState.feedItems, key = { it.compositeKey }) { email ->
          EmailSummaryCard(
            email = email,
            showAccountBadge = uiState.accounts.size > 1,
            showFlagAction = uiState.settings.enableFlagForLater,
            onToggleFlag = { onToggleFlag(email) },
            onClick = { onEmailClick(email) },
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
      .padding(horizontal = 16.dp, vertical = 8.dp),
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
  enableFlagForLater: Boolean,
  onEmailClick: (EmailSummary) -> Unit,
  onToggleFlag: (EmailSummary) -> Unit,
  onBackToFeed: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val quickDays = remember(query) { parseQuickDaySearchDays(query) }
  val headerDescription = when (quickDays) {
    1 -> "Today's emails (${results.size})"
    2 -> "Yesterday & today (${results.size})"
    null -> "\"$query\" (${results.size})"
    else -> "Last $quickDays days (${results.size})"
  }

  Column(modifier = Modifier.fillMaxSize()) {
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
      )
    }

    if (isSearching && results.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = surfColors.progressBlue)
      }
    } else if (results.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        SurfCard(modifier = Modifier.fillMaxWidth()) {
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
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(results, key = { it.compositeKey }) { email ->
          EmailSummaryCard(
            email = email,
            showAccountBadge = true,
            showFlagAction = enableFlagForLater,
            onToggleFlag = { onToggleFlag(email) },
            onClick = { onEmailClick(email) },
          )
        }
      }
    }
  }
}

@Composable
private fun EmailSummaryCard(
  email: EmailSummary,
  showAccountBadge: Boolean,
  showFlagAction: Boolean,
  onToggleFlag: () -> Unit,
  onClick: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val weight = if (email.isUnread) FontWeight.Bold else FontWeight.Normal

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    color = surfColors.cardBg,
    border = BorderStroke(1.dp, surfColors.cardStroke),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
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
          if (showFlagAction && email.isFlagged) {
            Text(
              text = "FLAGGED",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = surfColors.progressBlue,
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
          if (showFlagAction) {
            Text(
              text = if (email.isFlagged) "\u2605" else "\u2606",
              fontSize = 16.sp,
              color = if (email.isFlagged) surfColors.progressBlue else surfColors.mutedText,
              modifier = Modifier
                .clickable(onClick = onToggleFlag)
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
}

@Composable
private fun EmailReaderPane(
  summary: EmailSummary,
  detail: EmailDetail?,
  isLoading: Boolean,
  error: String?,
  blockRemoteMedia: Boolean,
  blockExternalImages: Boolean,
  enableFlagForLater: Boolean,
  saveContactsFromEmails: Boolean,
  onToggleFlag: () -> Unit,
  onSaveSenderContact: () -> Unit,
  onBack: () -> Unit,
  onReply: (EmailDetail) -> Unit,
  onForward: (EmailDetail) -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val context = LocalContext.current

  // Full-screen Email Reader (HM02TopBar and FAB are hidden)
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
        if (enableFlagForLater) {
          IconButton(onClick = onToggleFlag) {
            Icon(
              imageVector = Icons.Default.Star,
              contentDescription = if (summary.isFlagged) "Unflag Email" else "Flag for Later",
              tint = if (summary.isFlagged) surfColors.progressBlue else surfColors.mutedText,
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

    // Unified Full-Screen Block containing both header metadata and the email body
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
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "From: ${summary.fromName} <${summary.fromAddress}>",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.primaryText,
            modifier = Modifier.weight(1f),
          )
          if (saveContactsFromEmails) {
            Text(
              text = "+ Save Contact",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = surfColors.progressBlue,
              modifier = Modifier
                .clickable {
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
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
            )
          }
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = formatFullTimestamp(summary.receivedAtEpochMillis),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            color = surfColors.mutedText,
          )
          if (enableFlagForLater && summary.isFlagged) {
            Text(
              text = "\u2605 Flagged on feed",
              fontFamily = FontFamily.Monospace,
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold,
              color = surfColors.progressBlue,
            )
          }
        }
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
            val styledHtml = remember(htmlBody, blockRemoteMedia) {
              wrapEmailHtmlForReader(htmlBody, blockRemoteMedia)
            }
            AndroidView(
              modifier = Modifier.fillMaxSize(),
              factory = { ctx ->
                WebView(ctx).apply {
                  setBackgroundColor(android.graphics.Color.TRANSPARENT)
                  settings.javaScriptEnabled = false
                  settings.loadsImagesAutomatically = !blockExternalImages
                  settings.blockNetworkImage = blockExternalImages
                  settings.allowFileAccess = false
                  settings.allowContentAccess = false
                  settings.defaultTextEncodingName = "utf-8"
                  webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                      view: WebView?,
                      request: WebResourceRequest?,
                    ): Boolean {
                      val uri = request?.url ?: return true
                      val scheme = uri.scheme?.lowercase(Locale.US)
                      if (scheme == "https" || scheme == "http" || scheme == "mailto") {
                        runCatching {
                          context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                      }
                      return true
                    }
                  }
                }
              },
              update = { webView ->
                webView.loadDataWithBaseURL(
                  "about:blank",
                  styledHtml,
                  "text/html",
                  "utf-8",
                  null,
                )
              },
            )
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

  // Full-screen Compose / Reply / Forward pane
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(surfColors.pageBg)
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
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
        fontSize = 13.sp,
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

    if (accounts.size > 1) {
      Text(
        text = "SEND FROM",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = surfColors.mutedText,
      )
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, surfColors.cardStroke, RoundedCornerShape(12.dp))
          .background(surfColors.cardBg),
      ) {
        accounts.forEachIndexed { index, account ->
          val isSelected = account.id == selectedAccount.id
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { selectedAccount = account }
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = account.emailAddress,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.5.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
              color = surfColors.primaryText,
            )
            if (isSelected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = surfColors.primaryText,
                modifier = Modifier.size(16.dp),
              )
            }
          }
          if (index < accounts.lastIndex) {
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
          }
        }
      }
    } else {
      Text(
        text = "From: ${selectedAccount.emailAddress}",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = surfColors.secondaryText,
      )
    }

    SurfInputField(
      value = to,
      onValueChange = {
        to = it
        activeRecipientField = "to"
        onContactQueryChange(it)
      },
      hint = "To (comma-separated email addresses)",
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    SurfInputField(
      value = cc,
      onValueChange = {
        cc = it
        activeRecipientField = "cc"
        onContactQueryChange(it)
      },
      hint = "Cc (optional)",
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    if (contactSuggestions.isNotEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, surfColors.cardStroke, RoundedCornerShape(12.dp))
          .background(surfColors.cardBg),
      ) {
        contactSuggestions.forEachIndexed { index, suggestion ->
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
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              if (suggestion.name.isNotBlank()) {
                Text(
                  text = suggestion.name,
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = surfColors.primaryText,
                )
              }
              Text(
                text = suggestion.emailAddress,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = surfColors.secondaryText,
              )
            }
            Text(
              text = suggestion.source.uppercase(Locale.US),
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = surfColors.mutedText,
            )
          }
          if (index < contactSuggestions.lastIndex) {
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
          }
        }
      }
    }

    SurfInputField(
      value = subject,
      onValueChange = {
        subject = it
        onClearContactSuggestions()
      },
      hint = "Subject",
      singleLine = true,
    )

    BasicTextField(
      value = bodyField,
      onValueChange = { bodyField = it },
      textStyle = TextStyle(
        color = surfColors.primaryText,
        fontSize = 14.sp,
        fontFamily = FontFamily.SansSerif,
      ),
      cursorBrush = SolidColor(surfColors.primaryText),
      modifier = Modifier.fillMaxWidth(),
      decorationBox = { innerTextField ->
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(surfColors.inputBg, RoundedCornerShape(12.dp))
            .border(1.dp, surfColors.inputStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
          contentAlignment = Alignment.TopStart,
        ) {
          if (bodyField.text.isEmpty()) {
            Text(
              text = "Write your message...",
              color = surfColors.secondaryText,
              fontSize = 14.sp,
            )
          }
          Box(modifier = Modifier.height(340.dp)) {
            innerTextField()
          }
        }
      },
    )
  }
}

@Composable
private fun SettingsPane(
  uiState: HM02UiState,
  onToggleAccessContacts: (Boolean) -> Unit,
  onToggleSaveContactsFromEmails: (Boolean) -> Unit,
  onToggleFlagForLater: (Boolean) -> Unit,
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
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
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
        fontSize = 13.5.sp,
        color = surfColors.primaryText,
      )
    }

    Text(
      text = "CONTACTS & AUTOCOMPLETE",
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 11.5.sp,
      color = surfColors.mutedText,
    )

    SurfCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(
            text = "Access Device Contacts",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = surfColors.primaryText,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Suggest email addresses from your phone's default Contacts app when composing or forwarding.",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.secondaryText,
          )
        }
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

      HorizontalDivider(
        thickness = 1.dp,
        color = surfColors.cardStroke,
        modifier = Modifier.padding(vertical = 14.dp),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(
            text = "Save Contacts from Emails",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = surfColors.primaryText,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Remember sender and recipient addresses from emails you read or send (${uiState.savedContactsCount} saved), plus show '+ Save Contact' inside emails.",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.secondaryText,
          )
        }
        Switch(
          checked = settings.saveContactsFromEmails,
          onCheckedChange = onToggleSaveContactsFromEmails,
          colors = SwitchDefaults.colors(
            checkedThumbColor = surfColors.activePillText,
            checkedTrackColor = surfColors.activePillBg,
          ),
        )
      }
    }

    Text(
      text = "FEED & FLAGGING",
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 11.5.sp,
      color = surfColors.mutedText,
    )

    SurfCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(
            text = "Flag Emails for Later",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = surfColors.primaryText,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Pin flagged emails (\u2605) so they stay saved on your main feed even after the 10-minute window expires (${uiState.flaggedEmailsCount} flagged).",
            style = MaterialTheme.typography.bodySmall,
            color = surfColors.secondaryText,
          )
        }
        Switch(
          checked = settings.enableFlagForLater,
          onCheckedChange = onToggleFlagForLater,
          colors = SwitchDefaults.colors(
            checkedThumbColor = surfColors.activePillText,
            checkedTrackColor = surfColors.activePillBg,
          ),
        )
      }
    }

    Text(
      text = "SEARCH SHORTCUTS",
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 11.5.sp,
      color = surfColors.mutedText,
    )

    SurfCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
      Text(
        text = "\u2022 Search \"a\" \u2192 All emails received today\n" +
          "\u2022 Search \"a2\" \u2192 All emails from yesterday and today\n" +
          "\u2022 Search \"a7\" \u2192 All emails from the last 7 days",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = surfColors.secondaryText,
      )
    }
  }
}

@Composable
private fun AccountsManagerPane(
  accounts: List<EmailAccount>,
  isVerifying: Boolean,
  onSaveAccount: (EmailAccount, Boolean) -> Unit,
  onRemoveAccount: (String) -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val surfColors = LocalSurfColors.current

  // Show the Add Account form automatically only if no accounts exist yet;
  // otherwise toggle it via the (+) icon button next to EMAIL ACCOUNTS (N).
  var isAddingAccount by rememberSaveable(accounts.isEmpty()) {
    mutableStateOf(accounts.isEmpty())
  }
  var selectedPreset by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL) }
  var displayName by rememberSaveable { mutableStateOf("") }
  var emailAddress by rememberSaveable { mutableStateOf("") }
  var appPassword by rememberSaveable { mutableStateOf("") }
  var imapHost by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.imapHost) }
  var imapPort by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.imapPort.toString()) }
  var smtpHost by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.smtpHost) }
  var smtpPort by rememberSaveable { mutableStateOf(MailProviderPreset.GMAIL.smtpPort.toString()) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = "EMAIL ACCOUNTS (${accounts.size})",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.5.sp,
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

    if (accounts.isNotEmpty()) {
      accounts.forEach { account ->
        SurfCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
          Row(
            modifier = Modifier.fillMaxWidth(),
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
        }
      }
    }

    if (isAddingAccount) {
      if (accounts.isNotEmpty()) {
        HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
      }

      Text(
        text = "SELECT EMAIL PROVIDER",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        color = surfColors.mutedText,
      )

      // Vertical Provider List including "Other (Custom IMAP / SMTP)" at the end
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .border(1.dp, surfColors.cardStroke, RoundedCornerShape(14.dp))
          .background(surfColors.cardBg),
      ) {
        MailProviderPreset.entries.forEachIndexed { index, preset ->
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
              .padding(horizontal = 14.dp, vertical = 12.dp),
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
                tint = surfColors.primaryText,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          if (index < MailProviderPreset.entries.lastIndex) {
            HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
          }
        }
      }

      SurfCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(
          text = selectedPreset.setupHint,
          style = MaterialTheme.typography.bodySmall,
          color = surfColors.secondaryText,
        )
        val url = selectedPreset.appPasswordUrl
        if (url != null) {
          Spacer(modifier = Modifier.height(10.dp))
          SurfPillButton(
            label = "Open ${selectedPreset.label} App Passwords \u2197",
            onClick = {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
              }
            },
          )
        }
      }

      SurfInputField(
        value = displayName,
        onValueChange = { displayName = it },
        hint = "Display Name (e.g. Work, Personal)",
        singleLine = true,
      )

      SurfInputField(
        value = emailAddress,
        onValueChange = { emailAddress = it },
        hint = "Email Address (you@example.com)",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      )

      SurfInputField(
        value = appPassword,
        onValueChange = { appPassword = it },
        hint = "16-Character App Password",
        singleLine = true,
        isPassword = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SurfInputField(
          value = imapHost,
          onValueChange = { imapHost = it },
          hint = "IMAP Host",
          singleLine = true,
          modifier = Modifier.weight(2f),
        )
        SurfInputField(
          value = imapPort,
          onValueChange = { imapPort = it },
          hint = "IMAP Port",
          singleLine = true,
          modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SurfInputField(
          value = smtpHost,
          onValueChange = { smtpHost = it },
          hint = "SMTP Host",
          singleLine = true,
          modifier = Modifier.weight(2f),
        )
        SurfInputField(
          value = smtpPort,
          onValueChange = { smtpPort = it },
          hint = "SMTP Port",
          singleLine = true,
          modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }

      val canSubmit = !isVerifying &&
        emailAddress.isNotBlank() &&
        appPassword.isNotBlank() &&
        imapHost.isNotBlank() &&
        smtpHost.isNotBlank()

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        SurfPillButton(
          label = if (isVerifying) "Verifying..." else "Verify & Add Account",
          isPrimary = true,
          enabled = canSubmit,
          onClick = {
            val newAccount = EmailAccount(
              displayName = displayName.ifBlank { emailAddress.trim() },
              emailAddress = emailAddress.trim(),
              imapHost = imapHost.trim(),
              imapPort = imapPort.toIntOrNull() ?: 993,
              smtpHost = smtpHost.trim(),
              smtpPort = smtpPort.toIntOrNull() ?: 465,
              appPassword = appPassword.trim(),
            )
            onSaveAccount(newAccount, false)
            isAddingAccount = false
          },
        )
      }
    }
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
fun SurfInputField(
  value: String,
  onValueChange: (String) -> Unit,
  hint: String,
  modifier: Modifier = Modifier,
  singleLine: Boolean = true,
  isPassword: Boolean = false,
  minHeight: Dp = 44.dp,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  val surfColors = LocalSurfColors.current
  val visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None

  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = singleLine,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    textStyle = TextStyle(
      color = surfColors.primaryText,
      fontSize = 14.sp,
      fontFamily = FontFamily.SansSerif,
    ),
    cursorBrush = SolidColor(surfColors.primaryText),
    modifier = modifier.fillMaxWidth(),
    decorationBox = { innerTextField ->
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(if (singleLine) minHeight else Dp.Unspecified)
          .background(surfColors.inputBg, RoundedCornerShape(12.dp))
          .border(1.dp, surfColors.inputStroke, RoundedCornerShape(12.dp))
          .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
      ) {
        if (value.isEmpty()) {
          Text(
            text = hint,
            color = surfColors.secondaryText,
            fontSize = 14.sp,
          )
        }
        Box(
          modifier = if (!singleLine) Modifier.height(minHeight) else Modifier,
        ) {
          innerTextField()
        }
      }
    },
  )
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

internal fun wrapEmailHtmlForReader(rawHtml: String, stripMediaElements: Boolean): String {
  val sanitized = if (stripMediaElements) {
    rawHtml
      .replace(Regex("(?is)<(video|audio|iframe|object|embed)\\b[^>]*>.*?</\\1>"), "")
      .replace(Regex("(?is)<(video|audio|iframe|object|embed)\\b[^>]*/?>"), "")
  } else {
    rawHtml
  }
  return """
    <!DOCTYPE html>
    <html>
    <head>
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

private fun formatElapsedSince(lastRefreshedAtMillis: Long, currentClockMillis: Long): String {
  val diffSeconds = ((currentClockMillis - lastRefreshedAtMillis) / 1_000L).coerceAtLeast(0L)
  return when {
    diffSeconds < 5 -> "just now"
    diffSeconds < 60 -> "${diffSeconds}s ago"
    diffSeconds < 120 -> "1 minute ago"
    diffSeconds < 3600 -> "${diffSeconds / 60} minutes ago"
    else -> "${diffSeconds / 3600}h ago"
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
