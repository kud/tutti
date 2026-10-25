package io.kud.tutti.platform.security

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.protocol.bravia.AuthCookie
import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.SecretKeys
import io.kud.tutti.protocol.net.CertificatePin
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Secrets: the TV PSK, the IP-control cookie and the pins. Lives in files/datastore/secure.preferences_pb, excluded
 * from backup (res/xml/data_extraction_rules.xml and backup_rules.xml). Every value is encrypted by [SecretCipher]. Nothing here
 * is ever logged; use Redact if a value must be mentioned.
 *
 * Keys are per device (`psk/<id>`, `ipcookie/<id>`, `pin/<id>/remote`, `pin/<id>/rest`, [SecretKeys]). For one
 * release the TV's are also written to, and read back from, the version-1 flat keys, so neither a
 * crash mid-migration nor a downgrade can lose the PSK or the pairing.
 */
@Singleton
class SecureStore @Inject constructor(
    @Named(SECURE) private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    suspend fun tvPsk(): String? = read(SecretKeys.psk(TV))

    suspend fun setTvPsk(psk: String?) = write(SecretKeys.psk(TV), psk?.takeIf { it.isNotBlank() })

    /**
     * The cookie from the code shown on the TV (Sony's "Normal" authentication), a bearer secret like
     * the PSK; it has no version-1 twin. Unreadable means none.
     */
    suspend fun tvIpCookie(): AuthCookie? = AuthCookie.decode(read(SecretKeys.ipCookie(TV)))

    suspend fun setTvIpCookie(cookie: AuthCookie?) = write(SecretKeys.ipCookie(TV), cookie?.encode())

    /** Pin of the TV's remote-protocol certificate, recorded at pairing. */
    suspend fun tvRemotePin(): CertificatePin? = read(SecretKeys.remotePin(TV))?.let(::CertificatePin)

    suspend fun setTvRemotePin(pin: CertificatePin?) = write(SecretKeys.remotePin(TV), pin?.sha256Hex)

    /** Pin of the TV's REST (HTTPS) certificate, recorded on first successful call. */
    suspend fun tvRestPin(): CertificatePin? = read(SecretKeys.restPin(TV))?.let(::CertificatePin)

    suspend fun setTvRestPin(pin: CertificatePin?) = write(SecretKeys.restPin(TV), pin?.sha256Hex)

    /** "Oublier l'appareil" (TV): PSK, IP-control cookie and both pins, old keys included. */
    suspend fun forgetTv() = forgetDevice(TV)

    /** "Forget" for any device: every key holding its secrets. */
    suspend fun forgetDevice(id: String) {
        store.edit { prefs -> SecretKeys.all(id).forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    /** Copies the version-1 keys to the per-device ones, once; safe to run on every start. */
    suspend fun migrate() {
        store.edit { SecretMigration.apply(it) }
    }

    /** The per-device key, else its version-1 twin (until the copy has run). */
    private suspend fun read(name: String): String? {
        val encoded = encodedSecret(store.data.first(), name) ?: return null
        val blob = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return cipher.decrypt(blob)?.decodeToString()
    }

    /** Writes the per-device key and, for one release, its version-1 twin, in one edit. */
    private suspend fun write(name: String, value: String?) {
        val keys = listOfNotNull(name, legacyOf(name)).map(::stringPreferencesKey)
        val encoded = value?.let { Base64.encodeToString(cipher.encrypt(it.encodeToByteArray()), Base64.NO_WRAP) }
        store.edit { prefs ->
            for (key in keys) if (encoded == null) prefs.remove(key) else prefs[key] = encoded
        }
    }

    companion object {
        const val SECURE = "secure"
        private const val TV = Device.TV_ID

        private fun legacyOf(name: String): String? = SecretKeys.legacy.entries.firstOrNull { it.value == name }?.key

        /** The encrypted blob [read] decrypts: the per-device key, else its version-1 twin. */
        internal fun encodedSecret(prefs: Preferences, name: String): String? =
            prefs[stringPreferencesKey(name)] ?: legacyOf(name)?.let { prefs[stringPreferencesKey(it)] }
    }
}
