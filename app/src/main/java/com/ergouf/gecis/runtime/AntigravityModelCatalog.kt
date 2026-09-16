package com.ergouf.gecis.runtime

import android.content.Context
import com.ergouf.gecis.auth.OAuthTokenVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reads the model catalog from the bundled Antigravity provider instead of hard-coding slugs. */
internal class AntigravityModelCatalog(
    private val context: Context,
    private val tokenVault: OAuthTokenVault,
) {
    fun loadJson(): JSONObject {
        val families = load()
        return JSONObject().put(
            "models",
            JSONArray().apply {
                families.forEach { family ->
                    put(
                        JSONObject()
                            .put("id", family.id)
                            .put("label", family.label)
                            .put(
                                "variants",
                                JSONArray().apply {
                                    family.variants.forEach { variant ->
                                        put(
                                            JSONObject()
                                                .put("slug", variant.slug)
                                                .put("label", variant.label)
                                                .put("effort", variant.effort ?: JSONObject.NULL),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
    }

    fun load(): List<ModelFamily> {
        val credential = tokenVault.load()
            ?: throw IllegalStateException("请先连接 Google 账号后读取可用模型")
        val output = queryProviderModels(credential)
        val variants = parse(output)
        if (variants.isEmpty()) throw IllegalStateException("Antigravity 没有返回可用模型")

        val grouped = LinkedHashMap<String, MutableList<ModelVariant>>()
        for (variant in variants) {
            grouped.getOrPut(variant.familyLabel) { mutableListOf() }.add(variant)
        }
        return grouped.map { (label, items) ->
            ModelFamily(items.first().familyId, label, items)
        }
    }

    private fun queryProviderModels(credential: String): String {
        val spec = NativeRuntimeSpec.resolve(context)
        val home = AntigravityEnvironment.prepareHome(context)
        val network = RuntimeNetworkEnvironment.prepare(context)
        AntigravityEnvironment.materializeOAuthToken(context, credential)

        val readerPool = Executors.newSingleThreadExecutor()
        try {
            val builder = ProcessBuilder(spec.interactiveCommand() + "models")
                .directory(context.noBackupFilesDir)
                .redirectErrorStream(true)
            builder.environment().apply {
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                remove("JETSKI_OAUTH_TOKEN")
                putAll(AntigravityEnvironment.baseEnvironment(context, home, network))
                putAll(network.proxyEnvironment)
            }

            val process = builder.start()
            val outputFuture = readerPool.submit<String> {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { it.readText() }
            }
            if (!process.waitFor(MODEL_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("读取上游模型列表超时")
            }
            val output = outputFuture.get(2, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                val detail = output.trim().lineSequence().toList().takeLast(4).joinToString(" | ")
                throw IllegalStateException(detail.ifBlank { "无法读取 Antigravity 模型列表" })
            }
            return output
        } finally {
            readerPool.shutdownNow()
            AntigravityEnvironment.capturePlaintextOAuthToken(context, tokenVault)
            AntigravityEnvironment.clearPlaintextOAuthTokens(context)
        }
    }

    internal fun parse(raw: String): List<ModelVariant> {
        val out = mutableListOf<ModelVariant>()
        val seen = linkedSetOf<String>()
        for (rawLine in raw.lineSequence()) {
            val line = ANSI_ESCAPE.replace(rawLine, "").trim()
            if (line.isEmpty()) continue
            val split = line.indexOfFirst { it.isWhitespace() }
            if (split <= 0) continue
            val slug = line.substring(0, split).trim()
            val display = line.substring(split).trim()
            if (!MODEL_SLUG.matches(slug) || display.isEmpty() || !seen.add(slug)) continue

            val displayEffort = EFFORT_SUFFIX.find(display)?.groupValues?.getOrNull(1)?.lowercase()
            val slugEffort = slug.substringAfterLast('-', "").takeIf { it in EFFORT_LEVELS }
            val effort = displayEffort ?: slugEffort
            val familyLabel = if (displayEffort != null) display.replace(EFFORT_SUFFIX, "").trim() else display
            val familyId = if (slugEffort != null) slug.removeSuffix("-$slugEffort") else slug
            val label = effort?.replaceFirstChar { it.uppercase() } ?: "固定"
            out += ModelVariant(slug, label, effort, familyId, familyLabel)
        }
        return out
    }

    internal data class ModelFamily(
        val id: String,
        val label: String,
        val variants: List<ModelVariant>,
    )

    internal data class ModelVariant(
        val slug: String,
        val label: String,
        val effort: String?,
        val familyId: String,
        val familyLabel: String,
    )

    companion object {
        private const val MODEL_QUERY_TIMEOUT_SECONDS = 15L
        private val EFFORT_LEVELS = setOf("low", "medium", "high")
        private val MODEL_SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val EFFORT_SUFFIX = Regex("\\s*\\((Low|Medium|High)\\)\\s*$", RegexOption.IGNORE_CASE)
        private val ANSI_ESCAPE = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}
