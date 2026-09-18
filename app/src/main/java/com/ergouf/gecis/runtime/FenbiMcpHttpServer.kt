package com.ergouf.gecis.runtime

import android.content.Context
import android.util.Log
import com.ergouf.gecis.knowledge.FenbiKnowledgeBase
import com.ergouf.gecis.knowledge.FenbiSql
import com.ergouf.gecis.knowledge.FenbiSqlGateway
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Local streamable-HTTP MCP so the model can read fenbi.db. Bound to 127.0.0.1 only. */
internal class FenbiMcpHttpServer(
    private val context: Context,
    private val knowledgeBase: FenbiKnowledgeBase,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile var port: Int = 0
        private set

    fun start(): Int {
        if (running.get()) return port
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                Thread({ handleClient(client) }, "gecis-fenbi-mcp").start()
            }
        }, "gecis-fenbi-mcp-accept").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "fenbi MCP listening on 127.0.0.1:$port")
        return port
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val output = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
                var contentLength = 0
                var contentType = ""
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                    if (line.startsWith("Content-Type:", ignoreCase = true)) {
                        contentType = line.substringAfter(':').trim()
                    }
                    if (line.isEmpty()) break
                }
                if (contentLength <= 0) {
                    writeHttp(output, 400, """{"error":"missing body"}""")
                    return
                }
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(chars, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val body = String(chars, 0, read)
                val response = handleJsonRpc(body)
                writeHttp(output, 200, response, contentType.ifBlank { "application/json" })
            } catch (error: Throwable) {
                Log.w(TAG, "fenbi MCP client error", error)
            }
        }
    }

    private fun writeHttp(
        output: OutputStreamWriter,
        code: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
        output.write("Content-Type: $contentType; charset=utf-8\r\n")
        output.write("Content-Length: ${bytes.size}\r\n")
        output.write("Connection: close\r\n\r\n")
        output.write(body)
        output.flush()
    }

    internal fun handleJsonRpc(raw: String): String {
        val req = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return JSONObject()
                .put("jsonrpc", "2.0")
                .put("error", JSONObject().put("code", -32700).put("message", "parse error"))
                .toString()
        }
        val id = req.opt("id")
        val method = req.optString("method")
        val result = when (method) {
            "initialize" -> JSONObject()
                .put(
                    "protocolVersion",
                    req.optJSONObject("params")?.optString("protocolVersion")?.ifBlank { null } ?: "2024-11-05",
                )
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "gecis-fenbi").put("version", "0.2.0"))

            "notifications/initialized" -> null
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", toolsArray())
            "tools/call" -> handleToolCall(req.optJSONObject("params") ?: JSONObject())
            else -> null
        }
        if (method == "notifications/initialized") {
            return ""
        }
        val resp = JSONObject().put("jsonrpc", "2.0")
        if (id != null) resp.put("id", id)
        if (result != null) {
            resp.put("result", result)
        } else {
            resp.put("error", JSONObject().put("code", -32601).put("message", "method not found: $method"))
        }
        return resp.toString()
    }

    private fun toolsArray(): JSONArray = JSONArray(toolsJson())

    private fun toolsJson(): String =
        context.assets.open("fenbi-mcp/tools.json").bufferedReader().use { it.readText() }

    private fun handleToolCall(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name !in setOf("fenbi_schema", "fenbi_get", "fenbi_query")) {
            return JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "unknown tool $name")))
                .put("isError", true)
        }
        if (!knowledgeBase.hasDatabase()) {
            return notImported()
        }
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val gateway = FenbiSqlGateway(knowledgeBase.databaseFilePath())
            val value = when (name) {
                "fenbi_schema" -> gateway.schema()
                "fenbi_get" -> gateway.getRows(args.optString("table"), args.optJSONArray("ids") ?: JSONArray())
                "fenbi_query" -> gateway.query(args.optString("sql"))
                else -> error("unreachable")
            }
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString(2))))
                .put("isError", false)
        } catch (error: Throwable) {
            val message = error.message ?: "query failed"
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
                .put("isError", message != FenbiSql.NOT_IMPORTED)
        }
    }

    private fun notImported(): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", FenbiSql.NOT_IMPORTED)))
        .put("isError", false)

    companion object {
        private const val TAG = "GecisFenbiMcp"
    }
}
