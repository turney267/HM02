package dev.hm02.mail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkceChallenge(
  val codeVerifier: String,
  val codeChallenge: String,
  val state: String,
)

data class MicrosoftOAuthTokenResult(
  val accessToken: String,
  val refreshToken: String,
  val expiresAtEpochMillis: Long,
  val emailAddress: String?,
  val displayName: String?,
)

/**
 * Handles Microsoft OAuth2 (Modern Authentication) PKCE authorization and token refresh
 * for Outlook / Hotmail / Live / Microsoft 365 IMAP (`XOAUTH2`) and SMTP (`XOAUTH2`).
 */
object MicrosoftOAuthHelper {
  // Official Mozilla Thunderbird multi-tenant public client ID exempted by Microsoft for IMAP/SMTP OAuth2
  internal const val CLIENT_ID = "9e5f94bc-e8a4-4e73-b8be-63364c29d753"
  internal const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"
  private const val AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
  private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"

  internal val SCOPES = listOf(
    "openid",
    "profile",
    "email",
    "offline_access",
    "https://outlook.office.com/IMAP.AccessAsUser.All",
    "https://outlook.office.com/SMTP.Send",
  ).joinToString(" ")

  private val json = Json { ignoreUnknownKeys = true }
  private val secureRandom = SecureRandom()

  fun generatePkceChallenge(): PkceChallenge {
    val verifierBytes = ByteArray(32)
    secureRandom.nextBytes(verifierBytes)
    val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)

    val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
    val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    val stateBytes = ByteArray(16)
    secureRandom.nextBytes(stateBytes)
    val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)

    return PkceChallenge(
      codeVerifier = codeVerifier,
      codeChallenge = codeChallenge,
      state = state,
    )
  }

  private val CONSUMER_DOMAIN_PREFIXES = listOf(
    "outlook.",
    "hotmail.",
    "live.",
    "msn.",
    "passport.",
  )

  internal fun isConsumerMicrosoftEmail(email: String): Boolean {
    val domain = email.trim().substringAfter('@', "").lowercase()
    if (domain.isEmpty()) return false
    return CONSUMER_DOMAIN_PREFIXES.any { prefix -> domain.startsWith(prefix) }
  }

  internal fun sanitizeWebViewUserAgent(rawUserAgent: String): String =
    rawUserAgent
      .replace("; wv)", ")")
      .replace("; wv;", ";")
      .replace(Regex("\\s*Version/\\d+\\.\\d+\\s*"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  fun buildAuthorizationUrl(pkce: PkceChallenge, loginHint: String = ""): String {
    val trimmedHint = loginHint.trim()
    val params = linkedMapOf(
      "client_id" to CLIENT_ID,
      "response_type" to "code",
      "redirect_uri" to REDIRECT_URI,
      "response_mode" to "query",
      "scope" to SCOPES,
      "code_challenge" to pkce.codeChallenge,
      "code_challenge_method" to "S256",
      "state" to pkce.state,
      "prompt" to if (trimmedHint.isNotEmpty()) "login" else "select_account",
    )
    if (trimmedHint.isNotEmpty()) {
      params["login_hint"] = trimmedHint
      if (isConsumerMicrosoftEmail(trimmedHint)) {
        params["domain_hint"] = "consumers"
      }
    }
    val queryString = params.entries.joinToString("&") { (k, v) ->
      "${urlEncode(k)}=${urlEncode(v)}"
    }
    return "$AUTHORIZE_ENDPOINT?$queryString"
  }

  fun isRedirectUri(url: String): Boolean =
    url.startsWith(REDIRECT_URI, ignoreCase = true)

  suspend fun exchangeAuthorizationCode(
    code: String,
    codeVerifier: String,
  ): Result<MicrosoftOAuthTokenResult> = withContext(Dispatchers.IO) {
    runCatching {
      val formFields = linkedMapOf(
        "client_id" to CLIENT_ID,
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to REDIRECT_URI,
        "code_verifier" to codeVerifier,
        "scope" to SCOPES,
      )
      postTokenRequest(formFields, fallbackRefreshToken = null)
    }
  }

  suspend fun refreshAccessToken(
    refreshToken: String,
  ): Result<MicrosoftOAuthTokenResult> = withContext(Dispatchers.IO) {
    runCatching {
      val formFields = linkedMapOf(
        "client_id" to CLIENT_ID,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "scope" to SCOPES,
      )
      postTokenRequest(formFields, fallbackRefreshToken = refreshToken)
    }
  }

  private fun postTokenRequest(
    formFields: Map<String, String>,
    fallbackRefreshToken: String?,
  ): MicrosoftOAuthTokenResult {
    val formBody = formFields.entries.joinToString("&") { (k, v) ->
      "${urlEncode(k)}=${urlEncode(v)}"
    }.toByteArray(Charsets.UTF_8)

    val connection = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 15_000
      readTimeout = 15_000
      doOutput = true
      setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      setRequestProperty("Accept", "application/json")
    }

    return try {
      connection.outputStream.use { out ->
        out.write(formBody)
      }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val responseBody = stream?.let {
        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
      }.orEmpty()

      val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
      if (status !in 200..299 || root == null) {
        val errorDesc = root?.get("error_description")?.jsonPrimitive?.content
          ?: root?.get("error")?.jsonPrimitive?.content
          ?: "Microsoft OAuth2 failed (HTTP $status)"
        error(errorDesc.lineSequence().firstOrNull()?.trim() ?: "Microsoft OAuth2 failed")
      }

      val accessToken = root["access_token"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: error("Microsoft OAuth2 response did not include an access_token.")
      val refreshToken = root["refresh_token"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: fallbackRefreshToken
        ?: error("Microsoft OAuth2 response did not include a refresh_token.")
      val expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
      val idToken = root["id_token"]?.jsonPrimitive?.content

      val claims = parseJwtClaims(idToken) ?: parseJwtClaims(accessToken)
      val emailClaim = claims?.firstNotBlank("email", "preferred_username", "upn")
      val nameClaim = claims?.firstNotBlank("name", "given_name")

      MicrosoftOAuthTokenResult(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMillis = System.currentTimeMillis() + (expiresInSeconds * 1_000L),
        emailAddress = emailClaim,
        displayName = nameClaim,
      )
    } finally {
      connection.disconnect()
    }
  }

  internal fun parseJwtClaims(jwt: String?): Map<String, String>? {
    if (jwt.isNullOrBlank()) return null
    val parts = jwt.split('.')
    if (parts.size < 2) return null
    return runCatching {
      val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
      val payloadJson = json.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)).jsonObject
      payloadJson.entries.mapNotNull { (k, v) ->
        runCatching { k to v.jsonPrimitive.content }.getOrNull()
      }.toMap()
    }.getOrNull()
  }

  private fun Map<String, String>.firstNotBlank(vararg keys: String): String? {
    for (key in keys) {
      val value = this[key]?.trim()
      if (!value.isNullOrEmpty() && (key != "preferred_username" || value.contains("@"))) {
        return value
      }
    }
    return null
  }

  private fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8")
}
