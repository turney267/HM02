package dev.hm02.mail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    "https://outlook.office.com/IMAP.AccessAsUser.All",
    "https://outlook.office.com/SMTP.Send",
    "offline_access",
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

  internal const val APP_RETURN_URI = "hm02-oauth://complete"

  fun buildAuthorizationUrl(
    pkce: PkceChallenge,
    loginHint: String = "",
    redirectUri: String = REDIRECT_URI,
  ): String {
    val trimmedHint = loginHint.trim()
    val params = linkedMapOf(
      "client_id" to CLIENT_ID,
      "response_type" to "code",
      "redirect_uri" to redirectUri,
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
    url.startsWith(REDIRECT_URI, ignoreCase = true) ||
      url.startsWith("http://localhost:", ignoreCase = true) ||
      url.startsWith("http://127.0.0.1:", ignoreCase = true)

  suspend fun exchangeAuthorizationCode(
    code: String,
    codeVerifier: String,
    redirectUri: String = REDIRECT_URI,
  ): Result<MicrosoftOAuthTokenResult> = withContext(Dispatchers.IO) {
    runCatching {
      val formFields = linkedMapOf(
        "client_id" to CLIENT_ID,
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectUri,
        "code_verifier" to codeVerifier,
      )
      postTokenRequestWithRetry(formFields, fallbackRefreshToken = null)
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
      )
      postTokenRequestWithRetry(formFields, fallbackRefreshToken = refreshToken)
    }
  }

  private fun postTokenRequestWithRetry(
    formFields: Map<String, String>,
    fallbackRefreshToken: String?,
  ): MicrosoftOAuthTokenResult {
    var lastError: Throwable? = null
    for (attempt in 1..3) {
      try {
        return postTokenRequest(formFields, fallbackRefreshToken)
      } catch (e: java.io.IOException) {
        lastError = e
        if (attempt < 3) {
          runCatching { Thread.sleep(600L * attempt) }
        }
      }
    }
    throw lastError ?: IllegalStateException("Microsoft OAuth2 network request failed")
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

  internal fun parseQueryParameters(rawTarget: String): Map<String, String> {
    val queryPart = runCatching {
      URI("http://localhost$rawTarget").rawQuery
    }.getOrNull() ?: rawTarget.substringAfter('?', "").substringBefore('#')
    if (queryPart.isBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (pair in queryPart.split('&')) {
      if (pair.isBlank()) continue
      val eqIdx = pair.indexOf('=')
      val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
      val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
      val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
      val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
      if (key.isNotBlank()) {
        result[key] = value
      }
    }
    return result
  }
}

/**
 * Ephemeral RFC 8252 Section 7.3 loopback HTTP listener (`http://localhost:<port>`)
 * that receives the OAuth2 authorization code from Surf (or the system browser) and
 * immediately redirects back into the app via [MicrosoftOAuthHelper.APP_RETURN_URI].
 */
class MicrosoftLoopbackOAuthServer(
  private val expectedState: String,
  private val brandTitle: String = "Fly",
  private val appReturnUri: String = MicrosoftOAuthHelper.APP_RETURN_URI,
  private val onResult: (Result<String>) -> Unit,
) : Closeable {
  private val serverSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).apply {
    soTimeout = 10 * 60 * 1_000 // 10-minute session window matching Surf
  }
  private val closed = AtomicBoolean(false)
  private val delivered = AtomicBoolean(false)

  val port: Int = serverSocket.localPort
  val redirectUri: String = "http://localhost:$port"

  init {
    thread(isDaemon = true, name = "hm02-ms-oauth-loopback-$port") {
      acceptLoop()
    }
  }

  private fun acceptLoop() {
    try {
      while (!closed.get()) {
        val client = try {
          serverSocket.accept()
        } catch (_: SocketTimeoutException) {
          deliverOnce(Result.failure(IllegalStateException("Microsoft sign-in timed out. Please try again.")))
          break
        } catch (_: Exception) {
          break
        }
        handleClientSocket(client)
      }
    } finally {
      close()
    }
  }

  private fun handleClientSocket(socket: Socket) {
    socket.use { client ->
      runCatching {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        // Drain HTTP headers until blank line
        while (true) {
          val headerLine = reader.readLine() ?: break
          if (headerLine.isEmpty()) break
        }

        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return
        val target = parts[1].trim()
        if (target.startsWith("/favicon.ico", ignoreCase = true)) {
          writeHttpResponse(
            client = client,
            status = "204 No Content",
            contentType = "text/plain; charset=utf-8",
            body = "",
          )
          return
        }

        val params = MicrosoftOAuthHelper.parseQueryParameters(target)
        val code = params["code"]?.trim().orEmpty()
        val error = params["error"]?.trim().orEmpty()
        val errorDesc = params["error_description"]?.trim().orEmpty()
        val returnedState = params["state"]?.trim().orEmpty()

        if (code.isEmpty() && error.isEmpty() && errorDesc.isEmpty()) {
          writeHttpResponse(
            client = client,
            status = "204 No Content",
            contentType = "text/plain; charset=utf-8",
            body = "",
          )
          return
        }

        if (expectedState.isNotBlank() && returnedState != expectedState) {
          val returnLink = buildReturnUriWithParams(
            mapOf("error" to "invalid_state", "error_description" to "OAuth state mismatch. Please try signing in again."),
          )
          val html = buildCallbackHtml(
            title = "Sign-In Error",
            subtitle = "OAuth state mismatch. Please return to $brandTitle and try again.",
            isSuccess = false,
            returnUri = returnLink,
          )
          writeHttpResponse(client, "200 OK", "text/html; charset=utf-8", html)
          deliverOnce(Result.failure(IllegalStateException("OAuth state mismatch. Please try signing in again.")))
          close()
          return
        }

        if (code.isNotEmpty()) {
          val returnLink = buildReturnUriWithParams(
            mapOf("code" to code, "state" to returnedState),
          )
          val html = buildCallbackHtml(
            title = "Sign-In Complete",
            subtitle = "Completing Outlook setup in $brandTitle\u2026",
            isSuccess = true,
            returnUri = returnLink,
          )
          writeHttpResponse(client, "200 OK", "text/html; charset=utf-8", html)
          deliverOnce(Result.success(code))
          close()
        } else {
          val msg = errorDesc.ifBlank { error.ifBlank { "Microsoft sign-in was cancelled." } }
          val returnLink = buildReturnUriWithParams(
            mapOf("error" to error.ifBlank { "cancelled" }, "error_description" to msg),
          )
          val html = buildCallbackHtml(
            title = "Sign-In Cancelled",
            subtitle = msg,
            isSuccess = false,
            returnUri = returnLink,
          )
          writeHttpResponse(client, "200 OK", "text/html; charset=utf-8", html)
          deliverOnce(Result.failure(IllegalStateException(msg)))
          close()
        }
      }
    }
  }

  private fun writeHttpResponse(
    client: Socket,
    status: String,
    contentType: String,
    body: String,
  ) {
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
    writer.write("HTTP/1.1 $status\r\n")
    writer.write("Content-Type: $contentType\r\n")
    writer.write("Content-Length: ${bodyBytes.size}\r\n")
    writer.write("Cache-Control: no-store, no-cache\r\n")
    writer.write("Connection: close\r\n")
    writer.write("\r\n")
    writer.flush()
    if (bodyBytes.isNotEmpty()) {
      client.getOutputStream().write(bodyBytes)
      client.getOutputStream().flush()
    }
  }

  private fun buildReturnUriWithParams(params: Map<String, String>): String {
    val nonEmpty = params.filterValues { it.isNotBlank() }
    if (nonEmpty.isEmpty()) return appReturnUri
    val query = nonEmpty.entries.joinToString("&") { (k, v) ->
      "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }
    return "$appReturnUri?$query"
  }

  private fun buildCallbackHtml(
    title: String,
    subtitle: String,
    isSuccess: Boolean,
    returnUri: String = appReturnUri,
  ): String {
    val safeTitle = escapeHtml(title)
    val safeSubtitle = escapeHtml(subtitle)
    val safeReturnAttr = escapeHtml(returnUri)
    val safeReturnJs = returnUri
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\r", "")
      .replace("\n", "")
    val badgeColor = if (isSuccess) "#16A34A" else "#DC2626"
    return """
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>$safeTitle</title>
        <style>
          body {
            margin: 0;
            padding: 32px 20px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: #F8FAFC;
            color: #0F172A;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 80vh;
          }
          .card {
            max-width: 380px;
            width: 100%;
            background: #FFFFFF;
            border: 1px solid #E2E8F0;
            border-radius: 16px;
            padding: 28px 24px;
            text-align: center;
          }
          .dot {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            background: $badgeColor;
            display: inline-block;
            margin-bottom: 12px;
          }
          h1 {
            font-size: 18px;
            margin: 0 0 8px 0;
          }
          p {
            font-size: 14px;
            color: #475569;
            line-height: 1.5;
            margin: 0 0 22px 0;
          }
          a.btn {
            display: inline-block;
            background: #0F172A;
            color: #FFFFFF;
            text-decoration: none;
            font-weight: 600;
            font-size: 14px;
            padding: 12px 24px;
            border-radius: 999px;
          }
        </style>
      </head>
      <body>
        <div class="card">
          <div class="dot"></div>
          <h1>$safeTitle</h1>
          <p>$safeSubtitle</p>
          <a class="btn" href="$safeReturnAttr">Return to ${escapeHtml(brandTitle)}</a>
        </div>
        <script>
          setTimeout(function() {
            try {
              window.location.replace("$safeReturnJs");
            } catch (e) {}
          }, 120);
        </script>
      </body>
      </html>
    """.trimIndent()
  }

  private fun escapeHtml(raw: String): String =
    raw
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  private fun deliverOnce(result: Result<String>) {
    if (delivered.compareAndSet(false, true)) {
      onResult(result)
    }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      runCatching { serverSocket.close() }
    }
  }
}
