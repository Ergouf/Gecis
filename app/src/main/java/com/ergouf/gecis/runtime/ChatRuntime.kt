package com.ergouf.gecis.runtime

interface ChatRuntime : AutoCloseable {
    fun send(requestId: String, text: String, listener: Listener)

    interface Listener {
        fun onDelta(requestId: String, text: String)
        fun onComplete(requestId: String, text: String)
        fun onError(requestId: String, message: String)
    }
}
