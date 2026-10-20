package com.example.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Field-level encryption for secrets at rest (router passwords, Telegram bot
 * tokens). Uses an AES-256-GCM key that never leaves the Android Keystore.
 *
 * Migration-friendly: [decrypt] transparently returns legacy plaintext values
 * (anything without the "enc:v1:" prefix) so existing databases keep working;
 * values are re-encrypted in place the next time they are saved.
 */
object CryptoManager {

    private const val KEY_ALIAS = "netcraft_master_key"
    private const val PREFIX = "enc:v1:"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey? {
        cachedKey?.let { return it }
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey().also { cachedKey = it }
        } catch (e: Exception) {
            // Keystore unavailable (rare ROM issue) — caller falls back to plaintext.
            android.util.Log.e("CryptoManager", "Keystore key unavailable", e)
            null
        }
    }

    /** Encrypts [plain]; returns null for blank input. Falls back to the original
     *  value when the Keystore is unavailable (never throws). */
    fun encrypt(plain: String): String? {
        if (plain.isBlank()) return plain
        if (plain.startsWith(PREFIX)) return plain // already encrypted
        val key = getOrCreateKey() ?: return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "encrypt failed — storing as-is", e)
            plain
        }
    }

    /** Decrypts a value produced by [encrypt]. Legacy plaintext (no prefix) is
     *  returned unchanged so pre-migration data still opens. Never throws. */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrBlank()) return stored ?: ""
        if (!stored.startsWith(PREFIX)) return stored // legacy plaintext
        val key = getOrCreateKey() ?: return ""
        return try {
            val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "decrypt failed", e)
            ""
        }
    }
}
