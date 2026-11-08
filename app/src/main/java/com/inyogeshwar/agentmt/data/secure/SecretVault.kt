package com.inyogeshwar.agentmt.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets (API keys, MCP tokens) encrypted with an AES-256-GCM key held in the Android Keystore.
 * Ciphertext lives in a private SharedPreferences file; plaintext never touches disk or logs.
 */
class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return runCatching {
            val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(name: String) = prefs.contains(name)
    fun names(): Set<String> = prefs.all.keys
    fun remove(name: String) = prefs.edit().remove(name).apply()

    /** Last four characters only, for display. */
    fun hint(name: String): String? = get(name)?.let { if (it.length > 8) "••••" + it.takeLast(4) else "••••" }

    private companion object {
        const val ALIAS = "agentmt_vault_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
