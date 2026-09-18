package com.ergouf.gecis.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravitySettingsTest {
    @Test
    fun jetskiSettingsAllowGeminiMdAndFenbiMcp() {
        val settings = JSONObject().put("altScreenMode", "never")
        assertTrue(
            AntigravityEnvironment.applyJetskiHeadlessSettings(
                settings,
                trustedWorkspaces = listOf("/data/agy-home"),
                geminiMdPaths = listOf("/data/agy-home/GEMINI.md", "/data/GEMINI.md"),
            ),
        )
        val allow = settings.getJSONObject("permissions").getJSONArray("allow")
        val rules = (0 until allow.length()).map { allow.getString(it) }
        assertTrue(rules.contains("read_file(GEMINI.md)"))
        assertTrue(rules.contains("read_file(/data/agy-home/GEMINI.md)"))
        assertTrue(rules.contains("read_file(/data/GEMINI.md)"))
        assertTrue(rules.containsAll(AntigravityEnvironment.FENBI_MCP_ALLOW_RULES))
        assertEquals("/data/agy-home", settings.getJSONArray("trustedWorkspaces").getString(0))
        assertFalse(rules.any { it.contains("fenbi.db") })
        assertFalse(
            AntigravityEnvironment.applyJetskiHeadlessSettings(
                settings,
                trustedWorkspaces = listOf("/data/agy-home"),
                geminiMdPaths = listOf("/data/agy-home/GEMINI.md", "/data/GEMINI.md"),
            ),
        )
    }
}
