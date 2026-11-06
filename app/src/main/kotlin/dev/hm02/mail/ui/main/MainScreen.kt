package dev.hm02.mail.ui.main

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

  // Refresh the feed every time the user opens/resumes the app.
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
    modifier = modifier.fillMaxSize(),
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
        FloatingActionButton(onClick = viewModel::startNewEmail) {
          Text(
            text = "Compose",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
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
  Surface(
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.clickable { onFeedClick() }) {
          Text(
            text = "HM02",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "${uiState.policyName} • ${uiState.accounts.size} account(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onRefreshClick, enabled = !uiState.isRefreshingFeed) {
            Text(if (uiState.isRefreshingFeed) "Syncing..." else "Refresh")
          }
          OutlinedButton(onClick = onAccountsClick) {
            Text("Accounts")
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      OutlinedTextField(
        value = uiState.searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search all emails across accounts...") },
        trailingIcon = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (uiState.searchQuery.isNotEmpty()) {
              TextButton(onClick = {
                focusManager.clearFocus()
                onClearSearch()
              }) {
                Text("Clear")
              }
            }
            TextButton(onClick = {
              focusManager.clearFocus()
              onSearchSubmit()
            }) {
              Text("Search")
            }
          }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            focusManager.clearFocus()
            onSearchSubmit()
          },
        ),
      )

      if (uiState.isRefreshingFeed || uiState.isSearching || uiState.isSendingEmail) {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        )
      }
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
  if (uiState.accounts.isEmpty()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "No Email Accounts Configured",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "Add one or more IMAP/SMTP accounts (Gmail, Outlook, iCloud, Yahoo, Fastmail, or custom) to start using HM02.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddAccountClick) {
          Text("Add Email Account")
        }
      }
    }
    return
  }

  if (uiState.feedItems.isEmpty() && !uiState.isRefreshingFeed) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Feed is Quiet",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = uiState.policyEmptyDescription,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefreshClick) {
          Text("Refresh Feed")
        }
      }
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
  ) {
    items(uiState.feedItems, key = { it.compositeKey }) { email ->
      EmailSummaryRow(
        email = email,
        showAccountBadge = uiState.accounts.size > 1,
        onClick = { onEmailClick(email) },
      )
      HorizontalDivider()
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
  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Search results for \"$query\" (${results.size})",
        style = MaterialTheme.typography.labelLarge,
      )
      AssistChip(
        onClick = onBackToFeed,
        label = { Text("Back to Feed") },
      )
    }
    HorizontalDivider()

    if (isSearching && results.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else if (results.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "No emails matched \"$query\".",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.compositeKey }) { email ->
          EmailSummaryRow(
            email = email,
            showAccountBadge = true,
            onClick = { onEmailClick(email) },
          )
          HorizontalDivider()
        }
      }
    }
  }
}

@Composable
private fun EmailSummaryRow(
  email: EmailSummary,
  showAccountBadge: Boolean,
  onClick: () -> Unit,
) {
  val fontWeight = if (email.isUnread) FontWeight.Bold else FontWeight.Normal
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = email.fromName,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = formatTimestamp(email.receivedAtEpochMillis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Text(
      text = email.subject,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = fontWeight,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )

    if (showAccountBadge) {
      Text(
        text = "To: ${email.accountEmail}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
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
  onBack: () -> Unit,
  onReply: (EmailDetail) -> Unit,
  onForward: (EmailDetail) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text("← Back")
      }
      if (detail != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { onReply(detail) }) {
            Text("Reply")
          }
          OutlinedButton(onClick = { onForward(detail) }) {
            Text("Forward")
          }
        }
      }
    }

    HorizontalDivider()

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = summary.subject,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "From: ${summary.fromName} <${summary.fromAddress}>",
        style = MaterialTheme.typography.bodySmall,
      )
      if (detail != null && detail.toAddresses.isNotEmpty()) {
        Text(
          text = "To: ${detail.toAddresses.joinToString(", ")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (detail != null && detail.ccAddresses.isNotEmpty()) {
        Text(
          text = "Cc: ${detail.ccAddresses.joinToString(", ")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = formatFullTimestamp(summary.receivedAtEpochMillis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    HorizontalDivider()

    when {
      isLoading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }

      error != null -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      detail != null -> {
        val htmlBody = detail.htmlBody
        val plainBody = detail.plainTextBody
        if (!htmlBody.isNullOrBlank()) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
              WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.defaultTextEncodingName = "utf-8"
              }
            },
            update = { webView ->
              webView.loadDataWithBaseURL(
                null,
                htmlBody,
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
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeEmailPane(
  accounts: List<EmailAccount>,
  initialDraft: OutgoingEmailDraft,
  isSending: Boolean,
  onSend: (OutgoingEmailDraft) -> Unit,
  onCancel: () -> Unit,
) {
  var selectedAccount by remember(initialDraft) { mutableStateOf(initialDraft.fromAccount) }
  var to by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.to) }
  var cc by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.cc) }
  var subject by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.subject) }
  var body by rememberSaveable(initialDraft) { mutableStateOf(initialDraft.body) }

  val modeLabel = when (initialDraft.mode) {
    ComposeMode.NEW -> "New Email"
    ComposeMode.REPLY -> "Reply"
    ComposeMode.FORWARD -> "Forward"
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, enabled = !isSending) {
          Text("Cancel")
        }
        Button(
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
        ) {
          Text(if (isSending) "Sending..." else "Send")
        }
      }
    }

    if (accounts.size > 1) {
      Text(
        text = "Send From:",
        style = MaterialTheme.typography.labelMedium,
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        accounts.forEach { account ->
          FilterChip(
            selected = account.id == selectedAccount.id,
            onClick = { selectedAccount = account },
            label = { Text(account.emailAddress) },
          )
        }
      }
    } else {
      Text(
        text = "From: ${selectedAccount.emailAddress}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    OutlinedTextField(
      value = to,
      onValueChange = { to = it },
      label = { Text("To (comma-separated)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    OutlinedTextField(
      value = cc,
      onValueChange = { cc = it },
      label = { Text("Cc (optional)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    OutlinedTextField(
      value = subject,
      onValueChange = { subject = it },
      label = { Text("Subject") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = body,
      onValueChange = { body = it },
      label = { Text("Message") },
      modifier = Modifier
        .fillMaxWidth()
        .height(280.dp),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountsManagerPane(
  accounts: List<EmailAccount>,
  isVerifying: Boolean,
  onSaveAccount: (EmailAccount, Boolean) -> Unit,
  onRemoveAccount: (String) -> Unit,
  onDone: () -> Unit,
) {
  val context = LocalContext.current
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
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Email Accounts",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      TextButton(onClick = onDone) {
        Text("Done")
      }
    }

    if (accounts.isNotEmpty()) {
      accounts.forEach { account ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
          ),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = account.displayName.ifBlank { account.emailAddress },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "${account.emailAddress} • ${account.imapHost}",
                style = MaterialTheme.typography.bodySmall,
              )
            }
            TextButton(onClick = { onRemoveAccount(account.id) }) {
              Text("Remove", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
      HorizontalDivider()
    }

    Text(
      text = "Add Account (IMAP / SMTP + App Password)",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MailProviderPreset.entries.forEach { preset ->
        FilterChip(
          selected = selectedPreset == preset,
          onClick = {
            selectedPreset = preset
            imapHost = preset.imapHost
            imapPort = preset.imapPort.toString()
            smtpHost = preset.smtpHost
            smtpPort = preset.smtpPort.toString()
          },
          label = { Text(preset.label) },
        )
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
      ),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = selectedPreset.setupHint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        val url = selectedPreset.appPasswordUrl
        if (url != null) {
          OutlinedButton(
            onClick = {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
              context.startActivity(intent)
            },
          ) {
            Text("Open ${selectedPreset.label} App Passwords Page")
          }
        }
      }
    }

    OutlinedTextField(
      value = displayName,
      onValueChange = { displayName = it },
      label = { Text("Display Name (e.g. Work, Personal)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
      value = emailAddress,
      onValueChange = { emailAddress = it },
      label = { Text("Email Address") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    OutlinedTextField(
      value = appPassword,
      onValueChange = { appPassword = it },
      label = { Text("App Password") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = imapHost,
        onValueChange = { imapHost = it },
        label = { Text("IMAP Host") },
        singleLine = true,
        modifier = Modifier.weight(2f),
      )
      OutlinedTextField(
        value = imapPort,
        onValueChange = { imapPort = it },
        label = { Text("Port") },
        singleLine = true,
        modifier = Modifier.weight(1f),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = smtpHost,
        onValueChange = { smtpHost = it },
        label = { Text("SMTP Host") },
        singleLine = true,
        modifier = Modifier.weight(2f),
      )
      OutlinedTextField(
        value = smtpPort,
        onValueChange = { smtpPort = it },
        label = { Text("Port") },
        singleLine = true,
        modifier = Modifier.weight(1f),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Button(
        enabled = !isVerifying && emailAddress.isNotBlank() && appPassword.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank(),
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
      ) {
        Text(if (isVerifying) "Verifying..." else "Verify & Add Account")
      }

      TextButton(
        enabled = !isVerifying && emailAddress.isNotBlank() && appPassword.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank(),
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
      ) {
        Text("Save Without Test")
      }
    }
  }
}

private fun formatTimestamp(epochMillis: Long): String {
  if (epochMillis <= 0L) return ""
  val now = System.currentTimeMillis()
  val diffMinutes = (now - epochMillis) / 60_000L
  return when {
    diffMinutes < 1 -> "Just now"
    diffMinutes < 60 -> "${diffMinutes}m ago"
    diffMinutes < 24 * 60 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
  }
}

private fun formatFullTimestamp(epochMillis: Long): String {
  if (epochMillis <= 0L) return ""
  return SimpleDateFormat("EEE, MMM d, yyyy • HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
