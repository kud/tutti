package io.kud.salon.platform.security

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.salon.protocol.net.CertificatePin
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Secrets: the TV PSK and the pins. Lives in files/datastore/secure.preferences_pb, excluded
 * from backup (res/xml/*_rules.xml). Every value is encrypted by [SecretCipher]. Nothing here
 * is ever logged; use Redact if a value must be mentioned.
 */
@Singleton
class SecureStore @Inject constructor(
    @Named(SECURE) private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    suspend fun tvPsk(): String? = read(TV_PSK)

    suspend fun setTvPsk(psk: String?) = write(TV_PSK, psk?.takeIf { it.isNotBlank() })

    /** Pin of the TV's remote-protocol certificate, recorded at pairing. */
    suspend fun tvRemotePin(): CertificatePin? = read(TV_REMOTE_PIN)?.let(::CertificatePin)

    suspend fun setTvRemotePin(pin: CertificatePin?) = write(TV_REMOTE_PIN, pin?.sha256Hex)

    /** Pin of the TV's REST (HTTPS) certificate, recorded on first successful call. */
    suspend fun tvRestPin(): CertificatePin? = read(TV_REST_PIN)?.let(::CertificatePin)

    suspend fun setTvRestPin(pin: CertificatePin?) = write(TV_REST_PIN, pin?.sha256Hex)

    /** "Oublier l'appareil" (TV): PSK and both pins. */
    suspend fun forgetTv() {
        store.edit {
            it.remove(TV_PSK)
            it.remove(TV_REMOTE_PIN)
            it.remove(TV_REST_PIN)
        }
    }

    private suspend fun read(key: Preferences.Key<String>): String? {
        val encoded = store.data.first()[key] ?: return null
        val blob = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        return cipher.decrypt(blob)?.decodeToString()
    }

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        store.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                prefs[key] = Base64.encodeToString(cipher.encrypt(value.encodeToByteArray()), Base64.NO_WRAP)
            }
        }
    }

    companion object {
        const val SECURE = "secure"
        private val TV_PSK = stringPreferencesKey("tv_psk")
        private val TV_REMOTE_PIN = stringPreferencesKey("tv_remote_pin")
        private val TV_REST_PIN = stringPreferencesKey("tv_rest_pin")
    }
}
