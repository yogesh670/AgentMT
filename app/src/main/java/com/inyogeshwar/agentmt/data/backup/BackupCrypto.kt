package com.inyogeshwar.agentmt.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Passphrase encryption for exported secrets: PBKDF2-HMAC-SHA256 (210k rounds) → AES-256-GCM. */
object BackupCrypto {
    private val MAGIC = "AMTS1".toByteArray()
    private const val ITER = 210_000

    private fun key(pass: CharArray, salt: ByteArray) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, ITER, 256)).encoded, "AES")

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom(); val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** Throws [WrongPassphrase] when the passphrase is wrong or the data was changed. */
    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        if (blob.size < MAGIC.size + 28 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IllegalArgumentException("Not an AgentMT secrets file")
        var o = MAGIC.size
        val salt = blob.copyOfRange(o, o + 16); o += 16
        val iv = blob.copyOfRange(o, o + 12); o += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase.toCharArray(), salt), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIC)
        return try { c.doFinal(blob, o, blob.size - o) } catch (e: javax.crypto.AEADBadTagException) { throw WrongPassphrase() }
    }

    class WrongPassphrase : Exception("Wrong passphrase")
}
