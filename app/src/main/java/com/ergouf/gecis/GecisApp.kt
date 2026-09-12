package com.ergouf.gecis

import android.app.Application
import com.ergouf.gecis.auth.OAuthTokenVault
import com.ergouf.gecis.history.PendingChatStore
import com.ergouf.gecis.runtime.AntigravityOAuthCoordinator
import com.ergouf.gecis.runtime.OAuthSessionService

class GecisApp : Application() {
    lateinit var tokenVault: OAuthTokenVault
        private set
    lateinit var oauth: AntigravityOAuthCoordinator
        private set
    lateinit var pendingChatStore: PendingChatStore
        private set

    override fun onCreate() {
        super.onCreate()
        OAuthSessionService.ensureChannel(this)
        tokenVault = OAuthTokenVault(this)
        pendingChatStore = PendingChatStore(this)
        oauth = AntigravityOAuthCoordinator(this, tokenVault)
    }
}
