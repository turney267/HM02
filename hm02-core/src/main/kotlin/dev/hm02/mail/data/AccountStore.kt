package dev.hm02.mail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.hm02.mail.model.EmailAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class PendingMicrosoftOAuthSession(
  val state: String,
  val codeVerifier: String,
  val redirectUri: String,
  val emailAddress: String,
  val displayName: String,
  val createdAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Manages multiple [EmailAccount] configurations on-device.
 * Credentials (including App Passwords) are encrypted at rest using AES-GCM
 * backed by the Android hardware KeyStore.
 */
class AccountStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  private val _accounts = MutableStateFlow(loadAccounts())
  val accounts: StateFlow<List<EmailAccount>> = _accounts.asStateFlow()

  @Synchronized
  fun addOrUpdateAccount(account: EmailAccount) {
    val updated = _accounts.value.toMutableList().apply {
      val existingIndex = indexOfFirst { it.id == account.id || it.emailAddress.equals(account.emailAddress, ignoreCase = true) }
      if (existingIndex >= 0) {
        set(existingIndex, account.copy(id = get(existingIndex).id))
      } else {
        add(account)
      }
    }
    if (saveAccounts(updated)) {
      _accounts.value = updated
    }
  }

  @Synchronized
  fun removeAccount(accountId: String) {
    val updated = _accounts.value.filterNot { it.id == accountId }
    if (saveAccounts(updated)) {
      _accounts.value = updated
    }
  }

  @Synchronized
  fun savePendingMicrosoftOAuth(session: PendingMicrosoftOAuthSession) {
    runCatching {
      prefs.edit()
        .putString(KEY_PENDING_MS_OAUTH, json.encodeToString(session))
        .apply()
    }
  }

  @Synchronized
  fun loadPendingMicrosoftOAuth(): PendingMicrosoftOAuthSession? {
    val raw = prefs.getString(KEY_PENDING_MS_OAUTH, null) ?: return null
    return runCatching {
      json.decodeFromString<PendingMicrosoftOAuthSession>(raw)
    }.getOrNull()
  }

  @Synchronized
  fun clearPendingMicrosoftOAuth() {
    prefs.edit().remove(KEY_PENDING_MS_OAUTH).apply()
  }

  private fun loadAccounts(): List<EmailAccount> {
    val ciphertext = prefs.getString(KEY_ACCOUNTS_CIPHERTEXT, null) ?: return emptyList()
    val ivBase64 = prefs.getString(KEY_ACCOUNTS_IV, null) ?: return emptyList()
    return runCatching {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
      val decryptedBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
      try {
        json.decodeFromString<List<EmailAccount>>(decryptedBytes.decodeToString())
      } finally {
        decryptedBytes.fill(0)
      }
    }.getOrDefault(emptyList())
  }

  private fun saveAccounts(accounts: List<EmailAccount>): Boolean {
    return runCatching {
      val plaintextBytes = json.encodeToString(accounts).encodeToByteArray()
      val ciphertextBytes = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plaintextBytes)
        prefs.edit()
          .putString(KEY_ACCOUNTS_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
          .putString(KEY_ACCOUNTS_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
          .apply()
        encrypted
      } finally {
        plaintextBytes.fill(0)
      }
      ciphertextBytes.isNotEmpty() || accounts.isEmpty()
    }.getOrDefault(false)
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
    private const val KEY_ALIAS = "hm02_accounts_aes_key"
    private const val PREFS_NAME = "hm02_encrypted_accounts"
    private const val KEY_ACCOUNTS_CIPHERTEXT = "accounts_blob"
    private const val KEY_ACCOUNTS_IV = "accounts_iv"
    private const val KEY_PENDING_MS_OAUTH = "pending_ms_oauth_session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
  }
}
