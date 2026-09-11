package com.ergouf.gecis.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ergouf.gecis.auth.OAuthTokenVault
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Owns one long-lived authenticated Antigravity headless process. */
class AntigravityRuntime(
    private val context: Context,
    private val tokenVault: OAuthTokenVault,
) : ChatRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val lock = Any()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var pending: Pending? = null
    private var closed = false
    private var runtimeNetworkSummary = "网络状态未知"
    private val stderrTail = ArrayDeque<String>()

    override fun send(requestId: String, text: String, listener: ChatRuntime.Listener) {
        if (text.isBlank()) {
            deliverError(listener, requestId, "消息不能为空")
            return
        }

        io.execute {
            val oauthCredential = tokenVault.load()
            if (oauthCredential == null) {
                mainHandler.post { listener.onAuthenticationRequired(requestId) }
                return@execute
            }

            var localWriter: BufferedWriter? = null
            try {
                synchronized(lock) {
                    check(!closed) { "AI runtime 已关闭" }
                    check(pending == null) { "上一条消息仍在生成中" }
                    pending = Pending(requestId, listener)
                    try {
                        ensureProcessLocked(oauthCredential)
                        localWriter = writer ?: error("AI runtime stdin 不可用")
                    } catch (error: Throwable) {
                        pending = null
                        throw error
                    }
                }

                val input = JSONObject()
                    .put("event", "user")
                    .put("message", JSONObject().put("content", text))

                localWriter!!.apply {
                    write(input.toString())
                    newLine()
                    flush()
                }
            } catch (error: Throwable) {
                val active = synchronized(lock) {
                    val value = pending
                    pending = null
                    if (process?.isAlive != true) resetProcessLocked()
                    value
                }
                deliverError(
                    active?.listener ?: listener,
                    active?.requestId ?: requestId,
                    buildFailureMessage(error.message ?: "无法启动 AI runtime"),
                )
            }
        }
    }

    private fun ensureProcessLocked(oauthCredential: String) {
        if (process?.isAlive == true && writer != null) return

        resetProcessLocked()
        stderrTail.clear()
        val spec = NativeRuntimeSpec.resolve(context)
        val home = AntigravityEnvironment.prepareHome(context)
        val network = RuntimeNetworkEnvironment.prepare(context)
        runtimeNetworkSummary = network.summary

        val builder = ProcessBuilder(spec.headlessCommand())
            .directory(context.noBackupFilesDir)
            .redirectErrorStream(false)

        builder.environment().apply {
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
            putAll(AntigravityEnvironment.baseEnvironment(context, home, network))
            putAll(network.proxyEnvironment)
            // v1.2.0 exposes this credential entry point directly. Keep the OAuth JSON out of
            // WebView/state files and inject it only into the child process environment.
            put("JETSKI_OAUTH_TOKEN", oauthCredential)
        }

        val newProcess = builder.start()
        process = newProcess
        writer = BufferedWriter(OutputStreamWriter(newProcess.outputStream, Charsets.UTF_8))

        io.execute { readStdout(newProcess) }
        io.execute { drainStderr(newProcess) }
    }

    private fun readStdout(owner: Process) {
        try {
            BufferedReader(InputStreamReader(owner.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    handleEvent(line)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Antigravity stdout reader failed", error)
        } finally {
            val active = synchronized(lock) {
                if (process !== owner) return@synchronized null
                val value = pending
                pending = null
                value
            }
            if (active != null) {
                val exitCode = runCatching { owner.waitFor() }.getOrNull()
                val message = if (exitCode != null) {
                    "AI runtime 已退出（exit=$exitCode）"
                } else {
                    "AI runtime 输出流已关闭"
                }
                deliverError(active.listener, active.requestId, buildFailureMessage(message))
            }
            synchronized(lock) {
                if (process === owner) resetProcessLocked()
            }
        }
    }

    private fun drainStderr(owner: Process) {
        try {
            BufferedReader(InputStreamReader(owner.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    rememberStderr(line)
                    Log.w(TAG, "agy: $line")
                    if (isAuthenticationRequired(line)) {
                        handleAuthenticationRequired(owner)
                    }
                }
            }
        } catch (error: Throwable) {
            if (owner.isAlive) Log.w(TAG, "Antigravity stderr reader failed", error)
        }
    }

    private fun rememberStderr(line: String) {
        synchronized(lock) {
            val sanitized = line.trim().take(MAX_STDERR_LINE_CHARS)
            if (sanitized.isBlank()) return
            stderrTail.addLast(sanitized)
            while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
        }
    }

    private fun buildFailureMessage(base: String): String {
        val diagnostics = synchronized(lock) {
            val stderr = stderrTail.joinToString(" | ")
            buildString {
                append(base)
                append("\n网络：").append(runtimeNetworkSummary)
                if (stderr.isNotBlank()) append("\n运行时：").append(stderr)
            }
        }
        return diagnostics
    }

    private fun handleAuthenticationRequired(owner: Process) {
        var active: Pending? = null
        synchronized(lock) {
            if (process === owner && pending != null) {
                active = pending
                pending = null
                try {
                    writer?.close()
                } catch (_: Throwable) {
                }
                owner.destroy()
                resetProcessLocked()
            }
        }
        active?.let { value ->
            tokenVault.clear()
            AntigravityEnvironment.clearPlaintextOAuthTokens(context)
            mainHandler.post { value.listener.onAuthenticationRequired(value.requestId) }
        }
    }

    private fun handleEvent(line: String) {
        val event = try {
            JSONObject(line)
        } catch (_: Throwable) {
            Log.w(TAG, "Ignoring non-JSON runtime output")
            return
        }

        when (event.optString("event")) {
            "step_update" -> {
                val delta = event.optJSONObject("step_update")?.optString("text_delta").orEmpty()
                if (delta.isEmpty()) return
                val active = synchronized(lock) {
                    pending?.also { it.buffer.append(delta) }
                } ?: return
                mainHandler.post { active.listener.onDelta(active.requestId, delta) }
            }

            "result" -> {
                val result = event.optJSONObject("result") ?: return
                val status = result.optString("status")
                val errorText = result.optString("error")
                val response = result.optString("response")
                val active = synchronized(lock) {
                    val value = pending
                    pending = null
                    value
                } ?: return

                if (isAuthenticationRequired(errorText)) {
                    tokenVault.clear()
                    AntigravityEnvironment.clearPlaintextOAuthTokens(context)
                    mainHandler.post { active.listener.onAuthenticationRequired(active.requestId) }
                } else if (errorText.isNotBlank() || status in TERMINAL_ERRORS) {
                    deliverError(
                        active.listener,
                        active.requestId,
                        buildFailureMessage(errorText.ifBlank { "AI runtime 返回状态：$status" }),
                    )
                } else {
                    val finalText = response.ifBlank { active.buffer.toString() }
                    mainHandler.post { active.listener.onComplete(active.requestId, finalText) }
                }
            }
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            val value = pending
            pending = null
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            process?.destroy()
            resetProcessLocked()
            value
        }
        active?.let { deliverError(it.listener, it.requestId, "会话已关闭") }
        io.shutdownNow()
    }

    private fun resetProcessLocked() {
        writer = null
        process = null
    }

    private fun deliverError(listener: ChatRuntime.Listener, requestId: String, message: String) {
        mainHandler.post { listener.onError(requestId, message) }
    }

    private fun isAuthenticationRequired(message: String): Boolean =
        AUTH_ERRORS.any { message.contains(it, ignoreCase = true) }

    private data class Pending(
        val requestId: String,
        val listener: ChatRuntime.Listener,
        val buffer: StringBuilder = StringBuilder(),
    )

    companion object {
        private const val TAG = "GecisRuntime"
        private const val STDERR_TAIL_LINES = 6
        private const val MAX_STDERR_LINE_CHARS = 240
        private val TERMINAL_ERRORS = setOf("ERROR", "CANCELED", "INTERRUPTED", "INVALID")
        private val AUTH_ERRORS = listOf(
            "authentication required",
            "unauthenticated",
            "not logged in",
            "please sign in",
            "please run 'antigravity login'",
        )
    }
}

internal object AntigravityEnvironment {
    private const val HOME_DIR = "agy-home"
    private val TOKEN_RELATIVE_PATHS = listOf(
        ".gemini/jetski-standalone-oauth-token",
        ".gemini/antigravity-cli/jetski-standalone-oauth-token",
        ".gemini/antigravity-cli/antigravity-oauth-token",
    )

    fun home(context: Context): File = File(context.noBackupFilesDir, HOME_DIR)

    fun prepareHome(context: Context): File {
        val home = home(context).apply { mkdirs() }
        val settingsDir = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val settingsFile = File(settingsDir, "settings.json")
        val settings = try {
            if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
        settings.remove("modelProvider")
        settings.put("altScreenMode", "never")
        settingsFile.writeText(settings.toString())
        return home
    }

    fun tokenFiles(context: Context): List<File> =
        TOKEN_RELATIVE_PATHS.map { File(home(context), it) }

    fun capturePlaintextOAuthToken(context: Context, vault: OAuthTokenVault): Boolean {
        for (file in tokenFiles(context)) {
            if (!file.isFile || file.length() == 0L) continue
            try {
                vault.save(file.readText())
                clearPlaintextOAuthTokens(context)
                return true
            } catch (_: Throwable) {
                // Keep looking; different Antigravity builds use different token locations.
            }
        }
        return false
    }

    fun clearPlaintextOAuthTokens(context: Context) {
        tokenFiles(context).forEach { it.delete() }
    }

    fun baseEnvironment(
        context: Context,
        home: File = prepareHome(context),
        network: RuntimeNetworkEnvironment.Prepared = RuntimeNetworkEnvironment.prepare(context),
    ): Map<String, String> {
        val appData = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        return mapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "GODEBUG" to "netdns=cgo",
            "JETSKI_APP_DATA_DIR" to appData.absolutePath,
            "SSL_CERT_FILE" to network.caBundle.absolutePath,
        )
    }
}

internal data class NativeRuntimeSpec(
    val loader: File,
    val engine: File,
    val nativeDir: File,
) {
    fun headlessCommand(): List<String> = baseCommand() + listOf(
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json",
        "--sandbox",
        "--print-timeout",
        "5m",
    )

    fun interactiveCommand(): List<String> = baseCommand()

    private fun baseCommand(): List<String> = listOf(
        loader.absolutePath,
        "--library-path",
        nativeDir.absolutePath,
        engine.absolutePath,
    )

    companion object {
        fun resolve(context: Context): NativeRuntimeSpec {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val loader = File(nativeDir, "libgecis_ld.so")
            val engine = File(nativeDir, "libgecis_agy.so")
            if (!loader.isFile || !engine.isFile) {
                throw RuntimeUnavailableException(
                    "Antigravity native payload 尚未打包。缺少 libgecis_ld.so 或 libgecis_agy.so",
                )
            }
            return NativeRuntimeSpec(loader, engine, nativeDir)
        }
    }
}

internal class RuntimeUnavailableException(message: String) : IllegalStateException(message)
