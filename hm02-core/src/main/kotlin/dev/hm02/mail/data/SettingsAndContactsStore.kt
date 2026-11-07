package dev.hm02.mail.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Manages user-configurable settings ([UserSettings]), locally persisted flagged emails
 * (so flagged messages remain pinned on the main feed even after the 10-minute lookback window),
 * and harvested/device contacts for email address autocomplete.
 */
class SettingsAndContactsStore(context: Context) {

  private val appContext = context.applicationContext
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val prefs: SharedPreferences = appContext.getSharedPreferences(
    PREFS_FILE_NAME,
    Context.MODE_PRIVATE,
  )

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<UserSettings> = _settings.asStateFlow()

  private val _flaggedEmails = MutableStateFlow(loadFlaggedEmails())
  val flaggedEmails: StateFlow<List<EmailSummary>> = _flaggedEmails.asStateFlow()

  private val _savedContacts = MutableStateFlow(loadSavedContacts())
  val savedContacts: StateFlow<List<ContactSuggestion>> = _savedContacts.asStateFlow()

  fun updateSettings(transform: (UserSettings) -> UserSettings) {
    val updated = transform(_settings.value)
    prefs.edit().putString(KEY_USER_SETTINGS, json.encodeToString(updated)).apply()
    _settings.value = updated
  }

  fun hasContactsPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  /**
   * Toggles the flagged state of [email].
   * When flagged, the email is saved so it stays pinned on the main feed until unflagged.
   * Returns the new `isFlagged` state (`true` if now flagged, `false` if unflagged).
   */
  fun toggleFlaggedEmail(email: EmailSummary): Boolean {
    val current = _flaggedEmails.value.toMutableList()
    val existingIndex = current.indexOfFirst { it.compositeKey == email.compositeKey }
    val nowFlagged = if (existingIndex >= 0) {
      current.removeAt(existingIndex)
      false
    } else {
      current.add(0, email.copy(isFlagged = true))
      true
    }
    saveFlaggedEmails(current)
    return nowFlagged
  }

  fun isEmailFlagged(compositeKey: String): Boolean {
    return _flaggedEmails.value.any { it.compositeKey == compositeKey }
  }

  fun markFlaggedEmailRead(compositeKey: String) {
    val current = _flaggedEmails.value
    if (current.none { it.compositeKey == compositeKey && it.isUnread }) return
    val updated = current.map {
      if (it.compositeKey == compositeKey) it.copy(isUnread = false) else it
    }
    saveFlaggedEmails(updated)
  }

  fun removeFlaggedForAccount(accountId: String) {
    val updated = _flaggedEmails.value.filterNot { it.accountId == accountId }
    saveFlaggedEmails(updated)
  }

  /**
   * Harvests sender and recipient contacts from an opened email when [UserSettings.saveContactsFromEmails] is enabled.
   */
  fun harvestContactsFromEmail(detail: EmailDetail) {
    if (!_settings.value.saveContactsFromEmails) return
    val candidates = mutableListOf<ContactSuggestion>()
    if (detail.summary.fromAddress.contains("@")) {
      candidates.add(
        ContactSuggestion(
          name = detail.summary.fromName.takeIf { !it.equals(detail.summary.fromAddress, ignoreCase = true) }.orEmpty(),
          emailAddress = detail.summary.fromAddress.trim(),
          source = "Email",
        ),
      )
    }
    (detail.toAddresses + detail.ccAddresses).forEach { raw ->
      parseContactFromFormattedAddress(raw)?.let { candidates.add(it) }
    }
    addSavedContacts(candidates)
  }

  /**
   * Harvests recipient addresses from a sent email when [UserSettings.saveContactsFromEmails] is enabled.
   */
  fun harvestContactsFromRawAddresses(rawList: String) {
    if (!_settings.value.saveContactsFromEmails) return
    val candidates = rawList
      .split(",")
      .mapNotNull { parseContactFromFormattedAddress(it) }
    addSavedContacts(candidates)
  }

  fun addSavedContacts(newContacts: List<ContactSuggestion>) {
    if (newContacts.isEmpty()) return
    val byEmail = _savedContacts.value
      .associateBy { it.emailAddress.lowercase(Locale.US) }
      .toMutableMap()

    for (contact in newContacts) {
      val key = contact.emailAddress.trim().lowercase(Locale.US)
      if (key.isBlank() || !key.contains("@")) continue
      val existing = byEmail[key]
      if (existing == null || (existing.name.isBlank() && contact.name.isNotBlank())) {
        byEmail[key] = contact.copy(
          name = contact.name.trim(),
          emailAddress = contact.emailAddress.trim(),
        )
      }
    }

    val merged = byEmail.values.sortedBy {
      it.name.ifBlank { it.emailAddress }.lowercase(Locale.US)
    }
    prefs.edit().putString(KEY_SAVED_CONTACTS, json.encodeToString(merged)).apply()
    _savedContacts.value = merged
  }

  /**
   * Queries matching contact suggestions from both saved email contacts and Android device contacts
   * (depending on user settings and runtime permission).
   */
  suspend fun queryContactSuggestions(
    rawInput: String,
    limit: Int = 6,
  ): List<ContactSuggestion> = withContext(Dispatchers.IO) {
    val activeToken = rawInput.substringAfterLast(',').trim().lowercase(Locale.US)
    if (activeToken.isEmpty()) return@withContext emptyList()

    val currentSettings = _settings.value
    val results = LinkedHashMap<String, ContactSuggestion>()

    if (currentSettings.saveContactsFromEmails) {
      for (contact in _savedContacts.value) {
        if (contact.name.lowercase(Locale.US).contains(activeToken) ||
          contact.emailAddress.lowercase(Locale.US).contains(activeToken)
        ) {
          results[contact.emailAddress.lowercase(Locale.US)] = contact
          if (results.size >= limit) break
        }
      }
    }

    if (currentSettings.accessDeviceContacts && hasContactsPermission() && results.size < limit) {
      runCatching {
        val projection = arrayOf(
          ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
          ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        val selection =
          "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ? OR " +
            "${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?"
        val selectionArgs = arrayOf("%$activeToken%", "%$activeToken%")
        appContext.contentResolver.query(
          ContactsContract.CommonDataKinds.Email.CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          "${ContactsContract.CommonDataKinds.Email.STARRED} DESC, " +
            "${ContactsContract.CommonDataKinds.Email.TIMES_CONTACTED} DESC",
        )?.use { cursor ->
          val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
          val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
          while (cursor.moveToNext() && results.size < limit) {
            val email = if (emailIdx >= 0) cursor.getString(emailIdx)?.trim().orEmpty() else ""
            if (email.isBlank() || !email.contains("@")) continue
            val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
            val key = email.lowercase(Locale.US)
            if (!results.containsKey(key)) {
              results[key] = ContactSuggestion(
                name = name,
                emailAddress = email,
                source = "Contacts",
              )
            }
          }
        }
      }
    }

    results.values.toList()
  }

  private fun parseContactFromFormattedAddress(raw: String): ContactSuggestion? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || !trimmed.contains("@")) return null
    return if (trimmed.contains("<") && trimmed.endsWith(">")) {
      val namePart = trimmed.substringBefore("<").trim().trim('"')
      val emailPart = trimmed.substringAfter("<").substringBeforeLast(">").trim()
      if (emailPart.contains("@")) {
        ContactSuggestion(name = namePart, emailAddress = emailPart, source = "Email")
      } else {
        null
      }
    } else {
      ContactSuggestion(name = "", emailAddress = trimmed, source = "Email")
    }
  }

  private fun saveFlaggedEmails(items: List<EmailSummary>) {
    val sorted = items.sortedByDescending { it.receivedAtEpochMillis }
    prefs.edit().putString(KEY_FLAGGED_EMAILS, json.encodeToString(sorted)).apply()
    _flaggedEmails.value = sorted
  }

  private fun loadSettings(): UserSettings {
    val raw = prefs.getString(KEY_USER_SETTINGS, null) ?: return UserSettings()
    return runCatching { json.decodeFromString<UserSettings>(raw) }.getOrDefault(UserSettings())
  }

  private fun loadFlaggedEmails(): List<EmailSummary> {
    val raw = prefs.getString(KEY_FLAGGED_EMAILS, null) ?: return emptyList()
    return runCatching { json.decodeFromString<List<EmailSummary>>(raw) }.getOrDefault(emptyList())
  }

  private fun loadSavedContacts(): List<ContactSuggestion> {
    val raw = prefs.getString(KEY_SAVED_CONTACTS, null) ?: return emptyList()
    return runCatching { json.decodeFromString<List<ContactSuggestion>>(raw) }.getOrDefault(emptyList())
  }

  companion object {
    private const val PREFS_FILE_NAME = "hm02_settings_and_contacts"
    private const val KEY_USER_SETTINGS = "user_settings_v1"
    private const val KEY_FLAGGED_EMAILS = "flagged_emails_v1"
    private const val KEY_SAVED_CONTACTS = "saved_contacts_v1"
  }
}
