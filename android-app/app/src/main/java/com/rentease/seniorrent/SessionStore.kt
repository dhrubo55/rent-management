package com.rentease.seniorrent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("senior_rent_session", Context.MODE_PRIVATE)
    private val alias = "rentease-session-v1"

    fun accessToken(): String? = readSecret("access_token")
    fun refreshToken(): String? = readSecret("refresh_token")
    fun role(): String? = prefs.getString("role", null)

    fun save(accessToken: String, refreshToken: String, role: String?) {
        prefs.edit()
            .putString("access_token", encrypt(accessToken))
            .putString("refresh_token", encrypt(refreshToken))
            .putString("role", role)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readSecret(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        if (!stored.startsWith("v1:")) return stored // Existing installs migrate on their next successful login/refresh.
        return runCatching {
            val bytes = Base64.decode(stored.substring(3), Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes)
            val iv = ByteArray(12)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            clear()
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val output = ByteBuffer.allocate(cipher.iv.size + value.toByteArray(Charsets.UTF_8).size + 16)
            .put(cipher.iv)
            .put(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            .array()
        return "v1:" + Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}
