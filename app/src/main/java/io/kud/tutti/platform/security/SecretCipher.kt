package io.kud.tutti.platform.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key. The key never leaves the
 * keystore and is never backed up, so restored ciphertext is unreadable by design.
 * Blob layout: [iv length][iv][ciphertext + tag].
 */
@Singleton
class SecretCipher @Inject constructor() {
    private val lock = Any()

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    /** Null when the blob is corrupt or was written under another key (e.g. after a restore). */
    fun decrypt(blob: ByteArray): ByteArray? = runCatching {
        val ivSize = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(blob, 1 + ivSize, blob.size - 1 - ivSize)
    }.getOrNull()

    private fun key(): SecretKey {
        synchronized(lock) {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            if (existing != null) return existing
            return generate()
        }
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tutti.secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
