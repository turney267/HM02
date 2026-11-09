package dev.hm02.mail.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.hm02.mail.model.ContactSuggestion
import dev.hm02.mail.model.EmailDetail
import dev.hm02.mail.model.EmailSummary
import dev.hm02.mail.model.UserSettings
import dev.hm02.mail.model.sanitizeHeaderControlChars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
   * Harvests sender names and addresses from feed or search summaries when [UserSettings.rememberSenders] is enabled.
   */
  fun harvestSendersFromSummaries(summaries: List<EmailSummary>) {
    if (!_settings.value.rememberSenders || summaries.isEmpty()) return
    val candidates = summaries.mapNotNull { summary ->
      if (!summary.fromAddress.contains("@")) return@mapNotNull null
      ContactSuggestion(
        name = summary.fromName.takeIf { !it.equals(summary.fromAddress, ignoreCase = true) }.orEmpty(),
        emailAddress = summary.fromAddress.trim(),
        source = "Sender",
      )
    }
    addSavedContacts(candidates)
  }

  /**
   * Harvests sender and recipient contacts from an opened email when [UserSettings.rememberSenders] is enabled.
   */
  fun harvestContactsFromEmail(detail: EmailDetail) {
    if (!_settings.value.rememberSenders) return
    val candidates = mutableListOf<ContactSuggestion>()
    if (detail.summary.fromAddress.contains("@")) {
      candidates.add(
        ContactSuggestion(
          name = detail.summary.fromName.takeIf { !it.equals(detail.summary.fromAddress, ignoreCase = true) }.orEmpty(),
          emailAddress = detail.summary.fromAddress.trim(),
          source = "Sender",
        ),
      )
    }
    (detail.toAddresses + detail.ccAddresses).forEach { raw ->
      parseContactFromFormattedAddress(raw)?.let { candidates.add(it) }
    }
    addSavedContacts(candidates)
  }

  /**
   * Harvests recipient addresses from a sent email when [UserSettings.rememberSenders] is enabled.
   */
  fun harvestContactsFromRawAddresses(rawList: String) {
    if (!_settings.value.rememberSenders) return
    val candidates = rawList
      .split(",")
      .mapNotNull { parseContactFromFormattedAddress(it) }
    addSavedContacts(candidates)
  }

  @Synchronized
  fun addSavedContacts(newContacts: List<ContactSuggestion>) {
    if (newContacts.isEmpty()) return
    val byEmail = _savedContacts.value
      .associateBy { it.emailAddress.lowercase(Locale.US) }
      .toMutableMap()

    for (contact in newContacts) {
      val cleanEmail = sanitizeHeaderControlChars(contact.emailAddress).replace(" ", "")
      val cleanName = sanitizeHeaderControlChars(contact.name)
      val key = cleanEmail.lowercase(Locale.US)
      if (key.isBlank() || !key.contains("@")) continue
      val existing = byEmail[key]
      if (existing == null || (existing.name.isBlank() && cleanName.isNotBlank())) {
        byEmail[key] = contact.copy(
          name = cleanName,
          emailAddress = cleanEmail,
        )
      }
    }

    val merged = byEmail.values
      .sortedBy { it.name.ifBlank { it.emailAddress }.lowercase(Locale.US) }
      .take(MAX_SAVED_CONTACTS)
    writeEncryptedJson(KEY_SAVED_CONTACTS, json.encodeToString(merged))
    _savedContacts.value = merged
  }

  /**
   * Queries matching remembered senders for the search bar autosuggest dropdown
   * when [UserSettings.rememberSenders] is enabled.
   */
  fun querySenderSearchSuggestions(
    rawQuery: String,
    limit: Int = 5,
    maxSearchDays: Int = 2,
  ): List<ContactSuggestion> {
    if (!_settings.value.rememberSenders) return emptyList()
    val token = rawQuery.trim().lowercase(Locale.US)
    if (token.isEmpty() || parseQuickDaySearchDays(token, maxDays = maxSearchDays) != null) return emptyList()

    return _savedContacts.value
      .filter { contact ->
        contact.name.lowercase(Locale.US).contains(token) ||
          contact.emailAddress.lowercase(Locale.US).contains(token)
      }
      .take(limit)
  }

  /**
   * Queries matching contact suggestions for prefill in the `To` / `Cc` fields of emails:
   * - Includes remembered senders when [UserSettings.rememberSenders] is enabled
   * - Includes Android device contacts when [UserSettings.accessDeviceContacts] is enabled
   */
  suspend fun queryContactSuggestions(
    rawInput: String,
    limit: Int = 6,
  ): List<ContactSuggestion> = withContext(Dispatchers.IO) {
    val activeToken = rawInput.substringAfterLast(',').trim().lowercase(Locale.US)
    if (activeToken.isEmpty()) return@withContext emptyList()

    val currentSettings = _settings.value
    if (!currentSettings.rememberSenders && !currentSettings.accessDeviceContacts) {
      return@withContext emptyList()
    }

    val results = LinkedHashMap<String, ContactSuggestion>()

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

    if (currentSettings.rememberSenders && results.size < limit) {
      for (contact in _savedContacts.value) {
        if (contact.name.lowercase(Locale.US).contains(activeToken) ||
          contact.emailAddress.lowercase(Locale.US).contains(activeToken)
        ) {
          val key = contact.emailAddress.lowercase(Locale.US)
          if (!results.containsKey(key)) {
            results[key] = contact
            if (results.size >= limit) break
          }
        }
      }
    }

    results.values.toList()
  }

  private fun parseContactFromFormattedAddress(raw: String): ContactSuggestion? {
    val trimmed = sanitizeHeaderControlChars(raw)
    if (trimmed.isEmpty() || !trimmed.contains("@")) return null
    return if (trimmed.contains("<") && trimmed.endsWith(">")) {
      val namePart = trimmed
        .substringBeforeLast("<")
        .trim()
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .trim()
      val emailPart = trimmed.substringAfterLast("<").substringBeforeLast(">").trim()
      if (emailPart.contains("@")) {
        ContactSuggestion(name = namePart, emailAddress = emailPart, source = "Email")
      } else {
        null
      }
    } else {
      ContactSuggestion(name = "", emailAddress = trimmed, source = "Email")
    }
  }

  @Synchronized
  private fun saveFlaggedEmails(items: List<EmailSummary>) {
    val sorted = items.sortedByDescending { it.receivedAtEpochMillis }
    writeEncryptedJson(KEY_FLAGGED_EMAILS, json.encodeToString(sorted))
    _flaggedEmails.value = sorted
  }

  private fun loadSettings(): UserSettings {
    val raw = prefs.getString(KEY_USER_SETTINGS, null) ?: return UserSettings()
    return runCatching { json.decodeFromString<UserSettings>(raw) }.getOrDefault(UserSettings())
  }

  private fun loadFlaggedEmails(): List<EmailSummary> {
    val rawJson = readEncryptedOrLegacyJson(KEY_FLAGGED_EMAILS) ?: return emptyList()
    return runCatching { json.decodeFromString<List<EmailSummary>>(rawJson) }.getOrDefault(emptyList())
  }

  private fun loadSavedContacts(): List<ContactSuggestion> {
    val rawJson = readEncryptedOrLegacyJson(KEY_SAVED_CONTACTS) ?: return emptyList()
    return runCatching { json.decodeFromString<List<ContactSuggestion>>(rawJson) }.getOrDefault(emptyList())
  }

  private fun writeEncryptedJson(prefKey: String, plaintextJson: String) {
    runCatching {
      val plaintextBytes = plaintextJson.encodeToByteArray()
      try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(prefKey.encodeToByteArray())
        val ciphertextBytes = cipher.doFinal(plaintextBytes)
        val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
        prefs.edit().putString(prefKey, "$ENCRYPTED_PREFIX$ivB64:$ctB64").apply()
      } finally {
        plaintextBytes.fill(0)
      }
    }
  }

  private fun readEncryptedOrLegacyJson(prefKey: String): String? {
    val stored = prefs.getString(prefKey, null) ?: return null
    if (stored.startsWith(ENCRYPTED_PREFIX)) {
      val payload = stored.removePrefix(ENCRYPTED_PREFIX)
      val separatorIndex = payload.indexOf(':')
      if (separatorIndex <= 0 || separatorIndex >= payload.lastIndex) return null
      val ivB64 = payload.substring(0, separatorIndex)
      val ctB64 = payload.substring(separatorIndex + 1)
      return runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(prefKey.encodeToByteArray())
        val decryptedBytes = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
        try {
          decryptedBytes.decodeToString()
        } finally {
          decryptedBytes.fill(0)
        }
      }.getOrNull()
    }

    // Smoothly migrate legacy plaintext JSON ("[...]") to AES-256-GCM on read.
    val trimmed = stored.trimStart()
    if (trimmed.startsWith("[")) {
      writeEncryptedJson(prefKey, stored)
      return stored
    }
    return null
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existingEntry != null) {
      return existingEntry.secretKey
    }
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "hm02_settings_contacts_aes_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val MAX_SAVED_CONTACTS = 500
    private const val PREFS_FILE_NAME = "hm02_settings_and_contacts"
    private const val KEY_USER_SETTINGS = "user_settings_v1"
    private const val KEY_FLAGGED_EMAILS = "flagged_emails_v1"
    private const val KEY_SAVED_CONTACTS = "saved_contacts_v1"
  }
}
