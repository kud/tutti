package io.kud.tutti.platform.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.atv.ClientNames
import io.kud.tutti.protocol.net.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TLS client identity the TV binds the pairing to. Made at pairing, reused on every
 * connection after, replaced only by pairing again or "Oublier l'appareil".
 *
 * Preferred: an RSA 2048 key generated inside AndroidKeyStore (non-exportable), with a
 * self-signed certificate built by BouncyCastle using that key. If the keystore refuses
 * (some devices), fallback: a software key encrypted at rest with [SecretCipher] in
 * files/secure/, which is excluded from backup. Layout and staging: [ClientIdentityFiles].
 *
 * The certificate's CN is the name the phone pairs under ([ClientNames]): "tutti (<model> <hex>)"
 * for identities made from now on, "tutti" for older ones, which keep working as they are.
 */
@Singleton
class ClientIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
    cipher: SecretCipher,
) {
    private val mutex = Mutex()
    @Volatile private var cached: ClientIdentity? = null

    private val files by lazy { ClientIdentityFiles(File(context.filesDir, "secure"), AndroidIdentityKeys(cipher)) }

    /** The identity if one exists; never creates one (a reconnect or the diagnostic must not change pairing state). */
    suspend fun existing(): ClientIdentity? {
        mutex.withLock {
            cached?.let { return it }
            return withContext(Dispatchers.IO) { files.load() }?.also { cached = it }
        }
    }

    /**
     * A fresh identity for a pairing attempt; the stored one stays in use until [commit].
     * [software] skips the keystore, for when its key could not sign in the TLS handshake
     * (some keystores refuse the raw RSA operation TLS 1.3 needs).
     */
    suspend fun stage(software: Boolean = false): StagedIdentity = mutex.withLock {
        withContext(Dispatchers.IO) { files.stage(software) { ClientNames.forInstall(Build.MODEL) } }
    }

    /** The TV accepted the code: [staged] becomes the stored identity. */
    suspend fun commit(staged: StagedIdentity) {
        mutex.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                files.commit(staged)
                cached = staged.identity
            }
        }
    }

    /** The pairing did not finish: drop [staged], keep the stored identity. */
    suspend fun discard(staged: StagedIdentity) {
        mutex.withLock { withContext(Dispatchers.IO + NonCancellable) { files.discard(staged) } }
    }

    suspend fun forget() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cached = null
                files.forget()
            }
        }
    }
}

private class AndroidIdentityKeys(private val cipher: SecretCipher) : IdentityKeys {
    override fun keystoreKey(alias: String): PrivateKey? = keyStore().getKey(alias, null) as? PrivateKey

    override fun generateInKeystore(alias: String): KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, SecretCipher.KEYSTORE)
        generator.initialize(
            // TLS 1.3 signs with RSA-PSS, which Conscrypt does by padding the digest itself and asking
            // the keystore for a raw private-key operation ("RSA/ECB/NoPadding" decrypt). Without
            // PURPOSE_DECRYPT and ENCRYPTION_PADDING_NONE that fails as "RSA routines: internal error"
            // in the handshake, which is how pairing with the TV first failed.
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(ClientCertificates.KEY_SIZE)
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                // TLS 1.2 signs with PKCS#1, TLS 1.3 with PSS: allow both.
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }

    override fun deleteKeystore(alias: String) = keyStore().deleteEntry(alias)

    override fun keystoreAliases(): List<String> = Collections.list(keyStore().aliases())

    override fun softwareKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(ClientCertificates.KEY_SIZE, SecureRandom()) }.generateKeyPair()

    override fun seal(plain: ByteArray): ByteArray = cipher.encrypt(plain)

    override fun open(blob: ByteArray): ByteArray? = cipher.decrypt(blob)

    private fun keyStore(): KeyStore = KeyStore.getInstance(SecretCipher.KEYSTORE).apply { load(null) }
}
