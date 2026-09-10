package com.ergouf.gecis.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Antigravity's OAuth token wrapper encrypted at rest.
 *
 * The WebView never receives this value. AntigravityRuntime decrypts it only when creating the
 * child-process environment and passes it through JETSKI_OAUTH_TOKEN.
 */
class OAuthTokenVault(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCredential(): Boolean = load() != null

    fun save(rawJson: String) {
        val normalized = normalize(rawJson)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val encrypted = prefs.getString(CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val json = String(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
            normalize(json)
        } catch (_: Throwable) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(CIPHERTEXT).remove(IV).apply()
    }

    private fun normalize(rawJson: String): String {
        val root = JSONObject(rawJson)
        val wrapped = if (root.optJSONObject("token") != null) {
            root
        } else {
            JSONObject()
                .put("token", root)
                .put("auth_method", "consumer")
        }

        val token = wrapped.optJSONObject("token")
            ?: throw IllegalArgumentException("OAuth credential is missing token payload")
        require(token.optString("access_token").isNotBlank()) {
            "OAuth credential is missing access_token"
        }
        require(token.optString("refresh_token").isNotBlank()) {
            "OAuth credential is missing refresh_token"
        }
        if (wrapped.optString("auth_method").isBlank()) {
            wrapped.put("auth_method", "consumer")
        }
        return wrapped.toString()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "gecis_oauth"
        private const val CIPHERTEXT = "antigravity_token_ciphertext"
        private const val IV = "antigravity_token_iv"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gecis.antigravity.oauth"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
