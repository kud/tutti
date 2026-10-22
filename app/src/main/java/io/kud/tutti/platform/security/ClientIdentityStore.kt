package io.kud.tutti.platform.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.net.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TLS client identity the TV binds the pairing to. Generated once, reused forever
 * (until "Oublier l'appareil").
 *
 * Preferred: an RSA 2048 key generated inside AndroidKeyStore (non-exportable), with a
 * self-signed certificate built by BouncyCastle using that key. If the keystore refuses
 * (some devices), fallback: a software key encrypted at rest with [SecretCipher] in
 * files/secure/, which is excluded from backup.
 */
@Singleton
class ClientIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
) {
    private val mutex = Mutex()
    @Volatile private var cached: ClientIdentity? = null

    private val dir: File get() = File(context.filesDir, "secure").apply { mkdirs() }
    private val certFile: File get() = File(dir, "atv_client.der")
    private val softKeyFile: File get() = File(dir, "atv_client.key.enc")

    suspend fun getOrCreate(): ClientIdentity {
        mutex.withLock {
            val existing = cached
            if (existing != null) return existing
            val identity = withContext(Dispatchers.IO) { load() ?: create() }
            cached = identity
            return identity
        }
    }

    /** The identity if one exists; never creates one (the diagnostic must not change pairing state). */
    suspend fun existing(): ClientIdentity? {
        mutex.withLock {
            cached?.let { return it }
            return withContext(Dispatchers.IO) { load() }?.also { cached = it }
        }
    }

    suspend fun forget() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cached = null
                runCatching { keyStore().deleteEntry(ALIAS) }
                certFile.delete()
                softKeyFile.delete()
            }
        }
    }

    private fun load(): ClientIdentity? {
        if (!certFile.exists()) return null
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream().buffered()) as X509Certificate
        }.getOrNull() ?: return null
        val key: PrivateKey? = if (softKeyFile.exists()) {
            cipher.decrypt(softKeyFile.readBytes())?.let { pkcs8 ->
                runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }.getOrNull()
            }
        } else {
            runCatching { keyStore().getKey(ALIAS, null) as? PrivateKey }.getOrNull()
        }
        return key?.let { ClientIdentity(certificate, it) }
    }

    private fun create(): ClientIdentity {
        certFile.delete()
        softKeyFile.delete()
        val identity = runCatching { createInKeystore() }.getOrElse {
            runCatching { keyStore().deleteEntry(ALIAS) }
            createSoftware()
        }
        certFile.writeBytes(identity.certificate.encoded)
        return identity
    }

    private fun createInKeystore(): ClientIdentity {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, SecretCipher.KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(ClientCertificates.KEY_SIZE)
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                // TLS 1.2 signs with PKCS#1, TLS 1.3 with PSS: allow both.
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .build(),
        )
        val pair = generator.generateKeyPair()
        val certificate = ClientCertificates.selfSign(pair.public, pair.private, COMMON_NAME)
        return ClientIdentity(certificate, pair.private)
    }

    private fun createSoftware(): ClientIdentity {
        val identity = ClientCertificates.generate(COMMON_NAME)
        softKeyFile.writeBytes(cipher.encrypt(identity.privateKey.encoded))
        return identity
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(SecretCipher.KEYSTORE).apply { load(null) }

    private companion object {
        const val ALIAS = "tutti.atv.client"
        const val COMMON_NAME = "tutti"
    }
}
