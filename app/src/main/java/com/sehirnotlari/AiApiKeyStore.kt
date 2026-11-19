package com.sehirnotlari

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user's key stays in app-private storage; the wrapping key cannot be exported from Android Keystore. */
class AiApiKeyStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = get() != null

    fun get(): String? {
        return try {
            val iv = prefs.getString("iv", null) ?: return null
            val encrypted = prefs.getString("value", null) ?: return null
            val key = key(false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // A restored ciphertext without its device Keystore key is unusable.
        }
    }

    fun save(raw: String): Boolean {
        val value = raw.trim()
        if (!valid(value)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("value", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) { false }
    }

    fun clear(): Boolean = prefs.edit().remove("iv").remove("value").commit()

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    companion object {
        const val PREFS = "ai_api_key"
        private const val ALIAS = "sehirnotlari_ai_api_key_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        fun valid(value: String): Boolean = value.length in 20..256 && value.all { it.code in 33..126 }
    }
}
