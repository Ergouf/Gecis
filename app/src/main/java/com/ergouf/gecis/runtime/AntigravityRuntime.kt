package com.ergouf.gecis.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/** Owns one long-lived authenticated Antigravity headless process. */
class AntigravityRuntime(private val context: Context) : ChatRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val lock = Any()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var pending: Pending? = null
    private var closed = false

    override fun send(requestId: String, text: String, listener: ChatRuntime.Listener) {
        if (text.isBlank()) {
            deliverError(listener, requestId, "消息不能为空")
            return
        }

        io.execute {
            var localWriter: BufferedWriter? = null
            try {
                synchronized(lock) {
                    check(!closed) { "AI runtime 已关闭" }
                    check(pending == null) { "上一条消息仍在生成中" }
                    ensureProcessLocked()
                    pending = Pending(requestId, listener)
                    localWriter = writer ?: error("AI runtime stdin 不可用")
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
                    error.message ?: "无法启动 AI runtime",
                )
            }
        }
    }

    private fun ensureProcessLocked() {
        if (process?.isAlive == true && writer != null) return

        resetProcessLocked()
        val spec = NativeRuntimeSpec.resolve(context)
        val home = AntigravityEnvironment.prepareHome(context)
        val builder = ProcessBuilder(spec.headlessCommand())
            .directory(context.noBackupFilesDir)
            .redirectErrorStream(false)

        builder.environment().apply {
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
            putAll(AntigravityEnvironment.baseEnvironment(context, home))
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
                resetProcessLocked()
                value
            }
            active?.let {
                deliverError(it.listener, it.requestId, "AI runtime 已退出，请重新登录或重试")
            }
        }
    }

    private fun drainStderr(owner: Process) {
        try {
            BufferedReader(InputStreamReader(owner.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line -> Log.w(TAG, "agy: $line") }
            }
        } catch (error: Throwable) {
            if (owner.isAlive) Log.w(TAG, "Antigravity stderr reader failed", error)
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

                if (errorText.isNotBlank() || status in TERMINAL_ERRORS) {
                    deliverError(
                        active.listener,
                        active.requestId,
                        errorText.ifBlank { "AI runtime 返回状态：$status" },
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

    private data class Pending(
        val requestId: String,
        val listener: ChatRuntime.Listener,
        val buffer: StringBuilder = StringBuilder(),
    )

    companion object {
        private const val TAG = "GecisRuntime"
        private val TERMINAL_ERRORS = setOf("ERROR", "CANCELED", "INTERRUPTED", "INVALID")
    }
}

internal object AntigravityEnvironment {
    private const val HOME_DIR = "agy-home"
    private const val TOKEN_RELATIVE_PATH = ".gemini/antigravity-cli/antigravity-oauth-token"

    fun prepareHome(context: Context): File {
        val home = File(context.noBackupFilesDir, HOME_DIR).apply { mkdirs() }
        val settingsDir = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val settingsFile = File(settingsDir, "settings.json")

        val settings = try {
            if (settingsFile.isFile) JSONObject(settingsFile.readText()) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
        // Standard account OAuth must not inherit the Gemini API-key provider switch.
        settings.remove("modelProvider")
        // SSH OAuth is easier to machine-drive in inline rendering mode.
        settings.put("altScreenMode", "never")
        settingsFile.writeText(settings.toString())
        return home
    }

    fun tokenFile(context: Context): File = File(prepareHome(context), TOKEN_RELATIVE_PATH)

    fun hasPersistedOAuthToken(context: Context): Boolean {
        val file = tokenFile(context)
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val root = JSONObject(file.readText())
            root.optJSONObject("token")?.optString("refresh_token").orEmpty().isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    fun baseEnvironment(context: Context, home: File = prepareHome(context)): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "GODEBUG" to "netdns=cgo",
        // Antigravity's container/headless-compatible OAuth store avoids a Linux Secret Service
        // dependency inside the Android APK. The token file stays inside noBackupFilesDir.
        "GEMINI_FORCE_FILE_STORAGE" to "true",
    )
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
