package dev.hm02.mail.ui.main

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import dev.hm02.mail.model.ComposeMode
import dev.hm02.mail.model.EmailAccount
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.MailProviderPreset
import dev.hm02.mail.model.OutgoingEmailDraft
import dev.hm02.mail.ui.theme.LocalSurfColors
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

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .background(surfColors.pageBg)
      .windowInsetsPadding(WindowInsets.safeDrawing),
    containerColor = surfColors.pageBg,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      HM02TopBar(
        uiState = uiState,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onSearchSubmit = viewModel::executeSearch,
        onClearSearch = viewModel::clearSearchAndReturnToFeed,
        onRefreshClick = viewModel::refreshFeed,
        onAccountsClick = viewModel::showAccounts,
        onFeedClick = viewModel::showFeed,
      )
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
            onAddAccountClick = viewModel::showAccounts,
            onRefreshClick = viewModel::refreshFeed,
          )
        }

        is ActivePane.SearchResults -> {
          SearchResultsPane(
            query = pane.query,
            isSearching = uiState.isSearching,
            results = uiState.searchResults,
            onEmailClick = viewModel::openMessage,
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
            onDone = viewModel::showFeed,
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
    // 52dp HM03/Surf-style Header Row: Status Icon + Bold Monospace Brand + Pill Actions
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier
          .weight(1f)
          .clickable { onFeedClick() },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (isBusy) "\u2026" else "\u25CF",
          color = if (isBusy) surfColors.mutedText else surfColors.verifiedGreen,
          fontSize = 13.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.padding(end = 8.dp),
        )
        Column {
          Text(
            text = uiState.brandTitle,
            color = surfColors.primaryText,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${uiState.policyName.uppercase(Locale.US)} • ${uiState.accounts.size} ACCT",
            color = surfColors.mutedText,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
          )
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SurfPillButton(
          label = if (uiState.isRefreshingFeed) "\u2026" else "\u21BB Refresh",
          enabled = !uiState.isRefreshingFeed,
          onClick = onRefreshClick,
        )
        SurfPillButton(
          label = "Accounts",
          onClick = onAccountsClick,
        )
      }
    }

    // Surf-style 12dp-rounded bordered Search Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SurfInputField(
        value = uiState.searchQuery,
        onValueChange = onSearchQueryChange,
        hint = "Search all emails across accounts...",
        modifier = Modifier.weight(1f),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            focusManager.clearFocus()
            onSearchSubmit()
          },
        ),
      )

      if (uiState.searchQuery.isNotEmpty()) {
        SurfPillButton(
          label = "\u2715",
          onClick = {
            focusManager.clearFocus()
            onClearSearch()
          },
        )
      }

      SurfPillButton(
        label = "Search",
        onClick = {
          focusManager.clearFocus()
          onSearchSubmit()
        },
      )
    }

    // 4dp Slim Horizontal Progress Bar matching HM03 IntentBrowserActivity
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
  onAddAccountClick: () -> Unit,
  onRefreshClick: () -> Unit,
) {
  val surfColors = LocalSurfColors.current

  if (uiState.accounts.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      SurfCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "${uiState.brandTitle.uppercase(Locale.US)} SETUP",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
          letterSpacing = 0.8.sp,
          color = surfColors.mutedText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
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

  if (uiState.feedItems.isEmpty() && !uiState.isRefreshingFeed) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      SurfCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "${uiState.brandTitle.uppercase(Locale.US)} POLICY • ${uiState.policyName.uppercase(Locale.US)}",
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
          letterSpacing = 0.8.sp,
          color = surfColors.mutedText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "Feed is Quiet",
          style = MaterialTheme.typography.titleMedium,
          color = surfColors.primaryText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = uiState.policyEmptyDescription,
          style = MaterialTheme.typography.bodyMedium,
          color = surfColors.secondaryText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          SurfPillButton(
            label = "\u21BB Refresh Feed",
            onClick = onRefreshClick,
          )
        }
      }
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(uiState.feedItems, key = { it.compositeKey }) { email ->
      EmailSummaryCard(
        email = email,
        showAccountBadge = uiState.accounts.size > 1,
        onClick = { onEmailClick(email) },
      )
    }
  }
}

@Composable
private fun SearchResultsPane(
  query: String,
  isSearching: Boolean,
  results: List<EmailSummary>,
  onEmailClick: (EmailSummary) -> Unit,
  onBackToFeed: () -> Unit,
) {
  val surfColors = LocalSurfColors.current

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "SEARCH: \"$query\" (${results.size})",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = surfColors.secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(8.dp))
      SurfPillButton(
        label = "< Back to Feed",
        onClick = onBackToFeed,
      )
    }
    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

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
          Text(
            text = "NO MATCHES",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            color = surfColors.mutedText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "No emails matched \"$query\" across your connected accounts.",
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(results, key = { it.compositeKey }) { email ->
          EmailSummaryCard(
            email = email,
            showAccountBadge = true,
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
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = formatTimestamp(email.receivedAtEpochMillis),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          color = surfColors.mutedText,
        )
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
        Box(
          modifier = Modifier
            .background(surfColors.inputBg, RoundedCornerShape(8.dp))
            .border(1.dp, surfColors.cardStroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
          Text(
            text = email.accountEmail,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = surfColors.secondaryText,
          )
        }
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
  onBack: () -> Unit,
  onReply: (EmailDetail) -> Unit,
  onForward: (EmailDetail) -> Unit,
) {
  val surfColors = LocalSurfColors.current
  val context = LocalContext.current

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SurfPillButton(label = "< Back", onClick = onBack)
      if (detail != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SurfPillButton(label = "Reply", onClick = { onReply(detail) })
          SurfPillButton(label = "Forward", onClick = { onForward(detail) })
        }
      }
    }

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(surfColors.cardBg)
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = summary.subject,
        style = MaterialTheme.typography.titleMedium,
        color = surfColors.primaryText,
      )
      Text(
        text = "From: ${summary.fromName} <${summary.fromAddress}>",
        style = MaterialTheme.typography.bodySmall,
        color = surfColors.primaryText,
      )
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

    HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)

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
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          SurfCard(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = error,
              color = surfColors.alertRed,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
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
              .padding(16.dp),
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

@Composable
private fun ComposeEmailPane(
  accounts: List<EmailAccount>,
  initialDraft: OutgoingEmailDraft,
  isSending: Boolean,
  onSend: (OutgoingEmailDraft) -> Unit,
  onCancel: () -> Unit,
) {
  val surfColors = LocalSurfColors.current
  var selectedAccount by remember(initialDraft) { mutableStateOf(initialDraft.fromAccount) }
  var to by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.to) }
  var cc by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.cc) }
  var subject by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.subject) }
  var body by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.body) }

  val modeLabel = when (initialDraft.mode) {
    ComposeMode.NEW -> "NEW EMAIL"
    ComposeMode.REPLY -> "REPLY"
    ComposeMode.FORWARD -> "FORWARD"
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = modeLabel,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = surfColors.primaryText,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfPillButton(
          label = "Cancel",
          enabled = !isSending,
          onClick = onCancel,
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
                body = body,
              ),
            )
          },
        )
      }
    }

    if (accounts.size > 1) {
      Text(
        text = "SEND FROM",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = surfColors.mutedText,
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        accounts.forEach { account ->
          SurfPillButton(
            label = account.emailAddress,
            isPrimary = account.id == selectedAccount.id,
            onClick = { selectedAccount = account },
          )
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
      onValueChange = { to = it },
      hint = "To (comma-separated email addresses)",
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    SurfInputField(
      value = cc,
      onValueChange = { cc = it },
      hint = "Cc (optional)",
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    SurfInputField(
      value = subject,
      onValueChange = { subject = it },
      hint = "Subject",
      singleLine = true,
    )

    SurfInputField(
      value = body,
      onValueChange = { body = it },
      hint = "Write your message...",
      singleLine = false,
      minHeight = 260.dp,
    )
  }
}

@Composable
private fun AccountsManagerPane(
  accounts: List<EmailAccount>,
  isVerifying: Boolean,
  onSaveAccount: (EmailAccount, Boolean) -> Unit,
  onRemoveAccount: (String) -> Unit,
  onDone: () -> Unit,
) {
  val context = LocalContext.current
  val surfColors = LocalSurfColors.current
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
      Text(
        text = "EMAIL ACCOUNTS",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = surfColors.primaryText,
      )
      SurfPillButton(label = "Done", onClick = onDone)
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
            SurfPillButton(
              label = "Remove",
              onClick = { onRemoveAccount(account.id) },
            )
          }
        }
      }
      HorizontalDivider(thickness = 1.dp, color = surfColors.cardStroke)
    }

    Text(
      text = "ADD ACCOUNT (IMAP / SMTP + APP PASSWORD)",
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 11.5.sp,
      color = surfColors.mutedText,
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MailProviderPreset.entries.forEach { preset ->
        SurfPillButton(
          label = preset.label,
          isPrimary = selectedPreset == preset,
          onClick = {
            selectedPreset = preset
            imapHost = preset.imapHost
            imapPort = preset.imapPort.toString()
            smtpHost = preset.smtpHost
            smtpPort = preset.smtpPort.toString()
          },
        )
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
        },
      )

      SurfPillButton(
        label = "Save Without Test",
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
          onSaveAccount(newAccount, true)
        },
      )
    }
  }
}

/**
 * Surf-styled pill button (`cornerRadius = 999dp`, `1dp` border stroke, bold `13.5sp` label)
 * matching `SurfThemeHelper.createPillButton`.
 */
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

/**
 * Surf-styled input box (`cornerRadius = 12dp`, `1dp` border stroke, `14sp` text)
 * matching `SurfThemeHelper.createStyledEditText`.
 */
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

/**
 * Surf-styled bordered card (`cornerRadius = 20dp`, `1dp` border stroke)
 * matching `SurfThemeHelper.createStyledDialog` and `BlockedPageRenderer`.
 */
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

/**
 * Wraps raw HTML email content in a clean, distraction-free stylesheet matching HM03's `BlockedPageRenderer`,
 * and optionally strips `<video>`, `<audio>`, `<iframe>`, `<object>`, and `<embed>` elements.
 */
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
          background-color: #ffffff;
          padding: 16px;
          margin: 0;
          overflow-wrap: break-word;
          word-wrap: break-word;
        }
        @media (prefers-color-scheme: dark) {
          body {
            color: #fafafa;
            background-color: #09090b;
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
