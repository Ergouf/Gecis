package com.ergouf.gecis.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives Antigravity's own Google OAuth flow instead of creating a parallel Google Sign-In flow.
 *
 * Gecis forces the documented remote/SSH OAuth path so Antigravity owns the OAuth client,
 * scopes, state and token exchange. The app only opens the generated URL and forwards the
 * one-time authorization code back to the same Antigravity process.
 */
class AntigravityOAuthCoordinator(private val context: Context) : AutoCloseable {
    interface Listener {
        fun onAuthorizationUrl(url: String)
        fun onAuthorizationCodeRequested()
        fun onAuthenticated()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var listener: Listener? = null
    private val closed = AtomicBoolean(false)
    private var cancelled = false
    private var selectedGoogleOAuth = false
    private var emittedUrl: String? = null
    private var askedForCode = false
    private var authenticated = false
    private val transcript = StringBuilder()

    fun start(listener: Listener) {
        synchronized(lock) {
            check(!closed.get()) { "OAuth coordinator 已关闭" }
            if (process?.isAlive == true) return
            this.listener = listener
            cancelled = false
            selectedGoogleOAuth = false
            emittedUrl = null
            askedForCode = false
            authenticated = false
            transcript.setLength(0)
        }

        io.execute {
            try {
                val spec = NativeRuntimeSpec.resolve(context)
                val home = AntigravityEnvironment.prepareHome(context)
                val builder = ProcessBuilder(spec.interactiveCommand())
                    .directory(context.noBackupFilesDir)
                    .redirectErrorStream(true)

                builder.environment().apply {
                    remove("LD_PRELOAD")
                    remove("LD_LIBRARY_PATH")
                    putAll(AntigravityEnvironment.baseEnvironment(context, home))
                    // Force Antigravity's documented remote OAuth handoff instead of xdg-open.
                    put("SSH_CONNECTION", "127.0.0.1 1 127.0.0.1 2")
                    put("TERM", "xterm-256color")
                    put("NO_COLOR", "1")
                    // Antigravity has historically hard-wrapped long OAuth URLs to terminal width.
                    // A very wide logical terminal keeps the browser URL intact when output is piped.
                    put("COLUMNS", "4096")
                    put("LINES", "80")
                }

                val owner = builder.start()
                synchronized(lock) {
                    process = owner
                    writer = BufferedWriter(OutputStreamWriter(owner.outputStream, Charsets.UTF_8))
                }

                BufferedReader(InputStreamReader(owner.inputStream, Charsets.UTF_8)).use { reader ->
                    val chunk = CharArray(1024)
                    while (!closed.get() && !cancelled) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        if (count == 0) continue
                        inspect(String(chunk, 0, count))
                        if (authenticated) break
                    }
                }

                if (!authenticated && !closed.get() && !cancelled) {
                    fail("Google OAuth 进程已退出。${tailTranscript()}")
                }
            } catch (error: Throwable) {
                if (!closed.get() && !cancelled) fail(error.message ?: "无法启动 Google OAuth")
            } finally {
                synchronized(lock) {
                    writer = null
                    process?.destroy()
                    process = null
                }
            }
        }
    }

    fun submitAuthorizationCode(code: String) {
        val value = code.trim()
        if (value.isBlank()) {
            fail("授权码不能为空")
            return
        }
        io.execute {
            try {
                synchronized(lock) {
                    val out = writer ?: error("OAuth 会话已经结束")
                    out.write(value)
                    out.newLine()
                    out.flush()
                }
            } catch (error: Throwable) {
                if (!cancelled) fail(error.message ?: "无法提交授权码")
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            listener = null
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            process?.destroy()
            writer = null
            process = null
        }
    }

    private fun inspect(raw: String) {
        val clean = ANSI.replace(raw, "")
        transcript.append(clean)
        if (transcript.length > MAX_TRANSCRIPT) {
            transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
        }
        val text = transcript.toString()

        if (!selectedGoogleOAuth && LOGIN_METHOD_HINT.containsMatchIn(text)) {
            selectedGoogleOAuth = true
            // Google OAuth is the first/default option in Antigravity's login selector.
            synchronized(lock) {
                writer?.apply {
                    write("\n")
                    flush()
                }
            }
        }

        if (emittedUrl == null) {
            OAUTH_URL.find(text)?.value?.trimEnd(')', ']', '}', '>', '.', ',')?.let { url ->
                emittedUrl = url
                main.post { listener?.onAuthorizationUrl(url) }
            }
        }

        if (!askedForCode && CODE_PROMPT.containsMatchIn(text)) {
            askedForCode = true
            main.post { listener?.onAuthorizationCodeRequested() }
        }

        if (!authenticated && AntigravityEnvironment.hasPersistedOAuthToken(context)) {
            authenticated = true
            main.post { listener?.onAuthenticated() }
        }
    }

    private fun fail(message: String) {
        main.post { listener?.onError(message) }
    }

    private fun tailTranscript(): String {
        val value = transcript.toString().trim().replace(Regex("\\s+"), " ")
        return if (value.length <= 240) value else value.takeLast(240)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        io.shutdownNow()
    }

    companion object {
        private const val MAX_TRANSCRIPT = 16_384
        private val ANSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val LOGIN_METHOD_HINT = Regex("Select login method|Google OAuth", RegexOption.IGNORE_CASE)
        private val CODE_PROMPT = Regex("Enter the authorization code|authorization code", RegexOption.IGNORE_CASE)
        private val OAUTH_URL = Regex("https://accounts\\.google\\.com/[^\\s\\u001B]+")
    }
}
