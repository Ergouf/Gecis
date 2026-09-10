package com.ergouf.gecis.runtime

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ergouf.gecis.auth.OAuthTokenVault
import org.json.JSONObject
import java.io.BufferedReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Runs Antigravity-compatible Google OAuth natively on Android.
 *
 * The OAuth client is an installed/public client. PKCE and state protect each authorization
 * attempt. Gecis listens only on 127.0.0.1:51121, exchanges the returned code through Android's
 * HTTPS stack, then stores the Antigravity credential wrapper in OAuthTokenVault.
 */
class AntigravityOAuthCoordinator(
    private val tokenVault: OAuthTokenVault,
) : AutoCloseable {
    interface Listener {
        fun onAuthorizationUrl(url: String)
        fun onAuthenticated()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var listener: Listener? = null
    private var serverSocket: ServerSocket? = null
    private var closed = false
    private var cancelled = false
    private var active = false

    fun start(listener: Listener) {
        synchronized(lock) {
            check(!closed) { "OAuth coordinator 已关闭" }
            if (active) return
            active = true
            this.listener = listener
            cancelled = false
        }

        io.execute {
            var server: ServerSocket? = null
            try {
                val state = randomBase64Url(24)
                val verifier = randomBase64Url(32)
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(Charsets.US_ASCII)),
                )

                val boundServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(
                        InetSocketAddress(InetAddress.getByName("127.0.0.1"), CALLBACK_PORT),
                        1,
                    )
                    soTimeout = CALLBACK_TIMEOUT_MS
                }
                server = boundServer
                synchronized(lock) {
                    if (cancelled || closed) {
                        boundServer.close()
                        return@execute
                    }
                    serverSocket = boundServer
                }

                val authorizationUrl = buildAuthorizationUrl(state, challenge)
                main.post { this.listener?.onAuthorizationUrl(authorizationUrl) }

                val code = waitForAuthorizationCode(boundServer, state)
                if (cancelled || closed) return@execute

                val credential = exchangeCode(code, verifier)
                tokenVault.save(credential.toString())
                main.post { this.listener?.onAuthenticated() }
            } catch (error: Throwable) {
                if (!cancelled && !closed) {
                    val message = when (error) {
                        is java.net.BindException -> "Google 登录端口 $CALLBACK_PORT 被占用，请关闭占用程序后重试"
                        is java.net.SocketTimeoutException -> "Google 登录超时，请重新发起登录"
                        else -> error.message ?: "Google 登录失败"
                    }
                    main.post { this.listener?.onError(message) }
                }
            } finally {
                synchronized(lock) {
                    try {
                        server?.close()
                    } catch (_: Throwable) {
                    }
                    if (serverSocket === server) serverSocket = null
                    active = false
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            listener = null
            try {
                serverSocket?.close()
            } catch (_: Throwable) {
            }
            serverSocket = null
            active = false
        }
    }

    private fun buildAuthorizationUrl(state: String, challenge: String): String =
        Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

    private fun waitForAuthorizationCode(server: ServerSocket, expectedState: String): String {
        while (!cancelled && !closed) {
            server.accept().use { socket ->
                val callback = readCallback(socket)
                if (callback == null) {
                    respond(socket, 404, "Not Found")
                    continue
                }

                val returnedState = callback.getQueryParameter("state")
                if (returnedState != expectedState) {
                    respond(socket, 400, "Invalid OAuth state")
                    continue
                }

                callback.getQueryParameter("error")?.let { oauthError ->
                    respond(socket, 400, "Google authorization was not completed")
                    throw IllegalStateException("Google 授权失败：$oauthError")
                }

                val code = callback.getQueryParameter("code").orEmpty()
                if (code.isBlank()) {
                    respond(socket, 400, "Missing authorization code")
                    throw IllegalStateException("Google 回调缺少授权码")
                }

                redirectToSuccess(socket)
                return code
            }
        }
        throw IllegalStateException("Google 登录已取消")
    }

    private fun readCallback(socket: Socket): Uri? {
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
        val reader = BufferedReader(socket.getInputStream().reader(Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val target = parts[1]
        val uri = Uri.parse("http://127.0.0.1$target")
        return uri.takeIf { it.path == CALLBACK_PATH }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 404) "Not Found" else "Bad Request"
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(bodyBytes)
            output.flush()
        }
    }

    private fun redirectToSuccess(socket: Socket) {
        val response = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: https://antigravity.google/auth-success\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().use { output ->
            output.write(response)
            output.flush()
        }
    }

    private fun exchangeCode(code: String, verifier: String): JSONObject {
        val body = formEncode(
            mapOf(
                "client_id" to CLIENT_ID,
                "client_secret" to clientSecret(),
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ),
        )

        val connection = URL(TOKEN_URL).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded;charset=UTF-8",
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Google OAuth token exchange failed (HTTP $status)")
            }
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val response = JSONObject(payload)
            val accessToken = response.optString("access_token")
            val refreshToken = response.optString("refresh_token")
            if (accessToken.isBlank() || refreshToken.isBlank()) {
                throw IllegalStateException("Google OAuth 未返回可续期的登录凭据")
            }

            val expiresIn = response.optLong("expires_in", 3600L).coerceAtLeast(60L)
            val token = JSONObject()
                .put("access_token", accessToken)
                .put("refresh_token", refreshToken)
                .put("token_type", response.optString("token_type", "Bearer"))
                .put("expiry", Instant.now().plusSeconds(expiresIn).toString())
            response.optString("scope").takeIf { it.isNotBlank() }?.let { token.put("scope", it) }

            return JSONObject()
                .put("auth_method", "consumer")
                .put("token", token)
        } finally {
            connection.disconnect()
        }
    }

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${urlEncode(it.key)}=${urlEncode(it.value)}"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun randomBase64Url(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        cancel()
        io.shutdownNow()
    }

    companion object {
        private const val CALLBACK_PORT = 51121
        private const val CALLBACK_PATH = "/oauth-callback"
        private const val REDIRECT_URI = "http://127.0.0.1:51121/oauth-callback"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        private const val CLIENT_SECRET_PREFIX = "GOCSPX-"
        private const val CLIENT_SECRET_SUFFIX = "K58FWR486LdLJ1mLB8sXC4z6qDAf"
        private const val CALLBACK_TIMEOUT_MS = 180_000
        private const val SOCKET_READ_TIMEOUT_MS = 5_000
        private const val NETWORK_TIMEOUT_MS = 20_000

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        private fun clientSecret(): String = CLIENT_SECRET_PREFIX + CLIENT_SECRET_SUFFIX
    }
}
