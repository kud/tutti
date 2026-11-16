package io.kud.tutti.platform.security

import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.atv.ClientNames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom

/** An in-memory stand-in for AndroidKeyStore and SecretCipher. */
private class FakeKeys : IdentityKeys {
    val keystore = linkedMapOf<String, PrivateKey>()
    var refuseKeystore = false

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    override fun keystoreKey(alias: String) = keystore[alias]

    override fun generateInKeystore(alias: String): KeyPair {
        check(!refuseKeystore) { "keystore refused" }
        return pair().also { keystore[alias] = it.private }
    }

    override fun deleteKeystore(alias: String) {
        keystore.remove(alias)
    }

    override fun keystoreAliases() = keystore.keys.toList()

    override fun softwareKeyPair() = pair()

    override fun seal(plain: ByteArray) = plain.reversedArray()

    override fun open(blob: ByteArray) = blob.reversedArray()
}

/**
 * Per-install client names must not cost anyone their pairing: an identity stored before them
 * (CN "tutti", key under "tutti.atv.client.v2", no alias file) keeps loading byte for byte until
 * the user pairs again, and a pairing that does not finish never touches it.
 */
class ClientIdentityUpgradeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = FakeKeys()
    private val dir get() = File(folder.root, "secure").apply { mkdirs() }
    private fun files() = ClientIdentityFiles(dir, keys)
    private val mint = { ClientNames.forInstall("Phone X") }

    /** What v0.2.0-dev builds left on disk with a keystore key. */
    private fun legacyKeystoreLayout(): ByteArray {
        val pair = keys.generateInKeystore(ClientIdentityFiles.LEGACY_ALIAS)
        val certificate = ClientCertificates.selfSign(pair.public, pair.private, "tutti")
        File(dir, "atv_client.der").writeBytes(certificate.encoded)
        return certificate.encoded
    }

    @Test
    fun `a legacy keystore identity loads unchanged`() {
        val stored = legacyKeystoreLayout()
        val loaded = files().load()
        assertNotNull(loaded)
        assertArrayEquals(stored, loaded!!.certificate.encoded)
        assertEquals(keys.keystore[ClientIdentityFiles.LEGACY_ALIAS], loaded.privateKey)
        assertEquals("tutti", ClientNames.of(loaded.certificate))
        assertTrue(!File(dir, "atv_client.alias").exists())
    }

    @Test
    fun `a legacy software identity loads unchanged`() {
        val identity = ClientCertificates.generate("tutti")
        File(dir, "atv_client.der").writeBytes(identity.certificate.encoded)
        File(dir, "atv_client.key.enc").writeBytes(keys.seal(identity.privateKey.encoded))
        val loaded = files().load()!!
        assertArrayEquals(identity.certificate.encoded, loaded.certificate.encoded)
        assertArrayEquals(identity.privateKey.encoded, loaded.privateKey.encoded)
    }

    @Test
    fun `a pairing that does not finish leaves the legacy identity in place`() {
        val stored = legacyKeystoreLayout()
        val store = files()
        val staged = store.stage(software = false, mint)
        assertTrue(ClientNames.isPerInstall(ClientNames.of(staged.identity.certificate)))
        store.discard(staged)
        assertArrayEquals(stored, store.load()!!.certificate.encoded)
        assertEquals(listOf(ClientIdentityFiles.LEGACY_ALIAS), keys.keystoreAliases())
    }

    @Test
    fun `pairing again replaces the legacy identity with a per-install one`() {
        legacyKeystoreLayout()
        val store = files()
        val staged = store.stage(software = false, mint)
        store.commit(staged)
        val loaded = store.load()!!
        assertArrayEquals(staged.identity.certificate.encoded, loaded.certificate.encoded)
        assertEquals(staged.identity.privateKey, loaded.privateKey)
        assertTrue(ClientNames.of(loaded.certificate).startsWith("tutti (Phone X "))
        assertNull(keys.keystore[ClientIdentityFiles.LEGACY_ALIAS])
        assertEquals(1, keys.keystore.size)
    }

    @Test
    fun `pairing again later keeps the phone's own name, with a fresh key`() {
        val store = files()
        val first = store.stage(software = false, mint).also(store::commit)
        val second = store.stage(software = false) { error("must reuse the stored name") }
        assertEquals(ClientNames.of(first.identity.certificate), ClientNames.of(second.identity.certificate))
        assertNotEquals(first.identity.certificate.publicKey, second.identity.certificate.publicKey)
        store.commit(second)
        assertEquals(1, keys.keystore.size)
        assertArrayEquals(second.identity.certificate.encoded, store.load()!!.certificate.encoded)
    }

    @Test
    fun `a refused keystore falls back to a software key that survives a reload`() {
        legacyKeystoreLayout()
        keys.refuseKeystore = true
        val store = files()
        val staged = store.stage(software = false, mint)
        store.commit(staged)
        val reloaded = ClientIdentityFiles(dir, keys).load()!!
        assertArrayEquals(staged.identity.certificate.encoded, reloaded.certificate.encoded)
        assertArrayEquals(staged.identity.privateKey.encoded, reloaded.privateKey.encoded)
        assertTrue(keys.keystore.isEmpty())
    }

    @Test
    fun `forget removes every identity`() {
        legacyKeystoreLayout()
        val store = files()
        store.stage(software = false, mint).also(store::commit)
        store.forget()
        assertNull(store.load())
        assertTrue(keys.keystore.isEmpty())
    }
}
