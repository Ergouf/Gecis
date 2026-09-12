package com.ergouf.gecis.history

import android.content.Context
import org.json.JSONObject

data class PendingChatMessage(
    val requestId: String,
    val text: String,
    val conversationId: Long?,
)

data class PendingChatState(
    val currentConversationId: Long? = null,
    val currentProjectId: Long? = null,
    val pendingAfterDatabase: PendingChatMessage? = null,
    val pendingAfterAuth: PendingChatMessage? = null,
)

class PendingChatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun save(state: PendingChatState) {
        val json = JSONObject()
            .put("currentConversationId", state.currentConversationId ?: JSONObject.NULL)
            .put("currentProjectId", state.currentProjectId ?: JSONObject.NULL)
            .put("pendingAfterDatabase", state.pendingAfterDatabase?.toJson() ?: JSONObject.NULL)
            .put("pendingAfterAuth", state.pendingAfterAuth?.toJson() ?: JSONObject.NULL)
        prefs.edit().putString(KEY, json.toString()).commit()
    }

    @Synchronized
    fun load(): PendingChatState {
        val raw = prefs.getString(KEY, null) ?: return PendingChatState()
        return try {
            val json = JSONObject(raw)
            PendingChatState(
                currentConversationId = json.nullablePositiveLong("currentConversationId"),
                currentProjectId = json.nullablePositiveLong("currentProjectId"),
                pendingAfterDatabase = json.optJSONObject("pendingAfterDatabase")?.toMessage(),
                pendingAfterAuth = json.optJSONObject("pendingAfterAuth")?.toMessage(),
            )
        } catch (_: Throwable) {
            PendingChatState()
        }
    }

    private fun JSONObject.nullablePositiveLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).takeIf { it > 0L }
    }

    private fun PendingChatMessage.toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("text", text)
        .put("conversationId", conversationId ?: JSONObject.NULL)

    private fun JSONObject.toMessage(): PendingChatMessage? {
        val requestId = optString("requestId")
        val text = optString("text")
        if (requestId.isBlank() || text.isBlank()) return null
        val conversationId = nullablePositiveLong("conversationId")
        return PendingChatMessage(requestId, text, conversationId)
    }

    companion object {
        private const val PREFS = "gecis_pending_chat"
        private const val KEY = "state"
    }
}
