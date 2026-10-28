package io.kud.tutti.platform.security

import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.atv.ClientNames
import io.kud.tutti.protocol.net.ClientIdentity
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.random.Random

/** The key operations [ClientIdentityFiles] needs: AndroidKeyStore on a phone, in memory in tests. */
interface IdentityKeys {
    fun keystoreKey(alias: String): PrivateKey?

    /** Throws when the keystore refuses (some devices); the caller falls back to a software key. */
    fun generateInKeystore(alias: String): KeyPair

    fun deleteKeystore(alias: String)

    fun keystoreAliases(): List<String>

    fun softwareKeyPair(): KeyPair

    fun seal(plain: ByteArray): ByteArray

    fun open(blob: ByteArray): ByteArray?
}

/** A pairing candidate: held in memory (its keystore entry, if any, under a fresh alias) until [ClientIdentityFiles.commit]. */
class StagedIdentity internal constructor(
    val identity: ClientIdentity,
    internal val alias: String?,
    internal val sealedKey: ByteArray?,
)

/**
 * Storage of the TV client identity, in files/secure/:
 * - `atv_client.der`, the certificate;
 * - `atv_client.key.enc`, the private key sealed by [SecretCipher], only for a software identity;
 * - `atv_client.alias`, the AndroidKeyStore alias of the key, since per-install names. Absent on
 *   identities made before, which live under [LEGACY_ALIAS]; they are read as they are, never rewritten.
 *
 * Pairing never overwrites the stored identity up front: it [stage]s a new one and [commit]s it
 * only once the TV accepted the code, so a cancelled or failed pairing leaves the old one working.
 */
class ClientIdentityFiles(private val dir: File, private val keys: IdentityKeys) {
    private val certFile get() = File(dir, "atv_client.der")
    private val softKeyFile get() = File(dir, "atv_client.key.enc")
    private val aliasFile get() = File(dir, "atv_client.alias")

    fun load(): ClientIdentity? {
        if (!certFile.exists()) return null
        val certificate = runCatching {
            certFile.inputStream().buffered().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        }.getOrNull() ?: return null
        val key: PrivateKey? = if (softKeyFile.exists()) {
            keys.open(softKeyFile.readBytes())?.let { pkcs8 ->
                runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }.getOrNull()
            }
        } else {
            runCatching { keys.keystoreKey(currentAlias()) }.getOrNull()
        }
        return key?.let { ClientIdentity(certificate, it) }
    }

    /**
     * A new identity for a pairing. It keeps the stored identity's name when that one is already
     * per-install (so the TV replaces this phone's own old pairing), and otherwise mints one.
     */
    fun stage(software: Boolean, mintName: () -> String): StagedIdentity {
        val name = load()?.let { ClientNames.of(it.certificate) }?.takeIf(ClientNames::isPerInstall) ?: mintName()
        if (!software) {
            val alias = "$ALIAS_PREFIX.v3.${"%08x".format(Random.nextInt())}"
            val staged = runCatching {
                val pair = keys.generateInKeystore(alias)
                StagedIdentity(ClientIdentity(ClientCertificates.selfSign(pair.public, pair.private, name), pair.private), alias, null)
            }.getOrNull()
            if (staged != null) return staged
            runCatching { keys.deleteKeystore(alias) }
        }
        val pair = keys.softwareKeyPair()
        val identity = ClientIdentity(ClientCertificates.selfSign(pair.public, pair.private, name), pair.private)
        return StagedIdentity(identity, null, keys.seal(pair.private.encoded))
    }

    fun commit(staged: StagedIdentity) {
        if (staged.sealedKey != null) {
            write(softKeyFile, staged.sealedKey)
            aliasFile.delete()
        } else {
            write(aliasFile, staged.alias!!.toByteArray())
            softKeyFile.delete()
        }
        write(certFile, staged.identity.certificate.encoded)
        // Every other entry is a previous identity or an abandoned candidate.
        keys.keystoreAliases().filter { isOurs(it) && it != staged.alias }.forEach { runCatching { keys.deleteKeystore(it) } }
    }

    fun discard(staged: StagedIdentity) {
        val alias = staged.alias ?: return
        if (alias != currentAlias()) runCatching { keys.deleteKeystore(alias) }
    }

    fun forget() {
        runCatching { keys.keystoreAliases() }.getOrDefault(emptyList())
            .filter(::isOurs)
            .forEach { runCatching { keys.deleteKeystore(it) } }
        certFile.delete()
        softKeyFile.delete()
        aliasFile.delete()
    }

    private fun isOurs(alias: String) = alias == ALIAS_PREFIX || alias.startsWith("$ALIAS_PREFIX.")

    private fun currentAlias(): String =
        aliasFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: LEGACY_ALIAS

    private fun write(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    companion object {
        /** Covers "tutti.atv.client" (pre-TLS 1.3), "tutti.atv.client.v2" and every v3 alias. */
        const val ALIAS_PREFIX = "tutti.atv.client"

        /** Where identities made before per-install names keep their key. */
        const val LEGACY_ALIAS = "tutti.atv.client.v2"
    }
}
