package com.ergouf.gecis.runtime

import android.os.Handler
import android.os.Looper
import com.ergouf.gecis.knowledge.FenbiKnowledgeBase
import java.util.concurrent.Executors

/** Adds bounded local fenbi.db context before delegating to the Antigravity runtime. */
class KnowledgeAugmentingRuntime(
    private val delegate: ChatRuntime,
    private val knowledgeBase: FenbiKnowledgeBase,
) : ChatRuntime {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    override fun send(requestId: String, text: String, listener: ChatRuntime.Listener) {
        if (closed) {
            main.post { listener.onError(requestId, "会话已关闭") }
            return
        }

        worker.execute {
            if (closed) return@execute
            val augmented = try {
                knowledgeBase.augmentUserMessage(text)
            } catch (_: Throwable) {
                text
            }
            delegate.send(requestId, augmented, listener)
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        delegate.close()
    }
}
