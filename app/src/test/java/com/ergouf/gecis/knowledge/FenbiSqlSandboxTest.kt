package com.ergouf.gecis.knowledge

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FenbiSqlSandboxTest {
    @Test
    fun sandboxCasesMatchSharedJson() {
        val raw = javaClass.getResource("/sandbox-cases.json")!!.readText()
        val cases = JSONArray(raw)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val sql = case.getString("sql")
            val expect = case.getString("expect")
            val reason = case.getString("reason")
            val result = runCatching { FenbiSql.prepare(sql) }
            when (expect) {
                "accept" -> assertTrue("$reason: $sql -> ${result.exceptionOrNull()}", result.isSuccess)
                "reject" -> assertTrue("$reason: $sql should reject", result.isFailure)
                else -> error("unknown expect $expect")
            }
        }
    }

    @Test
    fun selectIsWrappedWithLimit() {
        val prepared = FenbiSql.prepare("SELECT * FROM t")
        assertTrue(prepared.sql.startsWith("SELECT * FROM ("))
        assertTrue(prepared.sql.contains("LIMIT 50"))
        assertFalse(prepared.explain)
    }

    @Test
    fun explainIsNotWrapped() {
        val prepared = FenbiSql.prepare("EXPLAIN QUERY PLAN SELECT id FROM t")
        assertTrue(prepared.sql.startsWith("EXPLAIN"))
        assertFalse(prepared.sql.startsWith("SELECT * FROM"))
        assertTrue(prepared.explain)
    }

    @Test
    fun toolsJsonHasDirectReadToolsOnly() {
        val raw = javaClass.getResource("/tools.json")!!.readText()
        val tools = JSONArray(raw)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("fenbi_schema", "fenbi_get", "fenbi_query")))
        assertFalse(names.contains("search_fenbi"))
        assertTrue(raw.contains("Inspect the user's local fenbi.db"))
    }
}
