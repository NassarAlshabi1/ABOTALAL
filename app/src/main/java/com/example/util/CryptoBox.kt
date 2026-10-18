package com.example.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore-backed AES/GCM encryption for values at rest (router passwords,
 * Telegram bot tokens). The key never leaves the hardware-backed keystore; values
 * are stored as "enc:v1:" + base64(iv || ciphertext).
 *
 * Existing plaintext values remain readable (they simply don't carry the prefix)
 * and get re-encrypted the next time they are saved — a transparent migration.
 */
object CryptoBox {
    private const val TAG = "CryptoBox"
    private const val KEY_ALIAS = "netcraft_master_key"
    private const val PREFIX = "enc:v1:"
    private const val IV_LEN = 12
    private const val TAG_LEN = 128

    private fun getOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Keystore unavailable — values stay as given", e)
        null
    }

    /** Encrypts [plain]; returns null when the keystore is unavailable (caller stores as-is). */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return plain
        val key = getOrCreateKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /**
     * Decrypts a value produced by [encrypt]. Legacy plaintext (no prefix) is
     * returned untouched so old rows keep working until they are re-saved.
     */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return stored.orEmpty()
        val key = getOrCreateKey() ?: return ""
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (blob.size <= IV_LEN) return ""
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            ""
        }
    }

    /** True when the stored value is already encrypted. */
    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    /** Returns the value encrypted only when needed (no double-encryption). */
    fun encryptIfPlain(plain: String): String =
        if (isEncrypted(plain) || plain.isEmpty()) plain else encrypt(plain) ?: plain
}
