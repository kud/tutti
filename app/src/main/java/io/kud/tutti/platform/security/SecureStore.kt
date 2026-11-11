package io.kud.tutti.platform.security

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.protocol.bravia.AuthCookie
import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.SecretKeys
import io.kud.tutti.platform.shortcuts.ShortcutGuard
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.MAX_PINS
import io.kud.tutti.protocol.net.PinMismatchEvent
import io.kud.tutti.protocol.net.PinMismatchLog
import io.kud.tutti.protocol.net.PinSet
import io.kud.tutti.protocol.net.PublicKeyPin
import io.kud.tutti.protocol.net.TrustedPins
import io.kud.tutti.protocol.net.ServerIdentityPin
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Secrets: the TV PSK, the IP-control cookie and the pins. Lives in files/datastore/secure.preferences_pb, excluded
 * from backup (res/xml/data_extraction_rules.xml and backup_rules.xml). Every value is encrypted by [SecretCipher]. Nothing here
 * is ever logged; use Redact if a value must be mentioned.
 *
 * Keys are per device (`psk/<id>`, `ipcookie/<id>`, `pin/<id>/remote`, REST pin keys, [SecretKeys]). For one
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

    /** Pin of the TV's remote-protocol certificate in use: the newest of [tvRemotePins]. */
    suspend fun tvRemotePin(): CertificatePin? = tvRemotePins().firstOrNull()

    /**
     * The trusted Remote v2 certificates, newest first, at most [MAX_PINS] ([TrustedPins]). An
     * install from before 0.3.1 has only the first key, read as it was.
     */
    suspend fun tvRemotePins(): List<CertificatePin> =
        readPins(SecretKeys.remotePin(TV), SecretKeys.remotePin2(TV)).map(::CertificatePin)

    /**
     * Admits [pin] as the newest trusted Remote v2 certificate, keeping the one before as the
     * second (pairing, or the automatic admission behind [io.kud.tutti.protocol.net.TrustGate]); null
     * forgets both. Also used to put an already trusted one first ([TrustedPins.follow]).
     */
    suspend fun setTvRemotePin(pin: CertificatePin?) {
        if (pin == null) {
            write(SecretKeys.remotePin(TV), null)
            write(SecretKeys.remotePin2(TV), null)
            return
        }
        val pins = TrustedPins.admit(tvRemotePins().map { it.sha256Hex }, pin.sha256Hex)
        // The second first: a crash between the two writes leaves both trusted, never neither.
        write(SecretKeys.remotePin2(TV), pins.getOrNull(1))
        write(SecretKeys.remotePin(TV), pins[0])
    }

    /**
     * REST HTTPS identity. New installs pin SubjectPublicKeyInfo; the old full-certificate digest
     * remains readable and is promoted after the next authenticated HTTPS success.
     */
    suspend fun tvRestPin(): ServerIdentityPin? {
        val keys = tvRestKeys()
        return when {
            keys.size > 1 -> PinSet(keys)
            keys.size == 1 -> keys[0]
            else -> read(SecretKeys.restPin(TV))?.let(::CertificatePin)
        }
    }

    /** The trusted REST public keys, newest first, at most [MAX_PINS]. */
    suspend fun tvRestKeys(): List<PublicKeyPin> =
        readPins(SecretKeys.restPublicKeyPin(TV), SecretKeys.restPublicKeyPin2(TV)).map(::PublicKeyPin)

    /**
     * Admits [pin] as the newest REST key, the one before staying trusted as the second; null
     * (HTTP-only after an explicit pairing) removes every generation.
     */
    suspend fun setTvRestPin(pin: PublicKeyPin?) {
        if (pin == null) {
            write(SecretKeys.restPublicKeyPin(TV), null)
            write(SecretKeys.restPublicKeyPin2(TV), null)
            write(SecretKeys.restPin(TV), null)
            return
        }
        val keys = TrustedPins.admit(tvRestKeys().map { it.sha256Hex }, pin.sha256Hex)
        // A new SPKI pin deliberately leaves the old cert digest alone so an immediate downgrade
        // still fails closed, not open. The second first, as for the remote pins.
        write(SecretKeys.restPublicKeyPin2(TV), keys.getOrNull(1))
        write(SecretKeys.restPublicKeyPin(TV), keys[0])
    }

    /** Pin mismatches recorded for the developer-mode diagnostics, newest first. Not secret, kept here off backups. */
    suspend fun pinMismatches(): List<PinMismatchEvent> = PinMismatchLog.decode(read(PIN_MISMATCHES))

    suspend fun recordPinMismatch(event: PinMismatchEvent) = write(PIN_MISMATCHES, PinMismatchLog.add(read(PIN_MISMATCHES), event))

    /** Label of the Remote v2 public key last seen on a working session, to tell a reissued certificate from a new key. */
    suspend fun lastRemoteKeyLabel(): String? = read(REMOTE_KEY_LABEL)

    suspend fun setLastRemoteKeyLabel(label: String) {
        if (read(REMOTE_KEY_LABEL) != label) write(REMOTE_KEY_LABEL, label)
    }

    /** "Oublier l'appareil" (TV): PSK, IP-control cookie and both pins, old keys included. */
    suspend fun forgetTv() = forgetDevice(TV)

    /** "Forget" for any device: every key holding its secrets. */
    suspend fun forgetDevice(id: String) {
        store.edit { prefs -> SecretKeys.all(id).forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    /**
     * This install's launcher-shortcut token ([ShortcutGuard]), created on first use. Not a device
     * secret: forgetting a device or signing out of Sony leaves it, so pinned shortcuts keep
     * working. Unreadable (the Keystore key gone) means a new one, and the next publish of the
     * shortcuts carries it.
     */
    suspend fun shortcutToken(): String {
        read(SHORTCUT_TOKEN)?.let { return it }
        var token = ""
        store.edit { prefs ->
            token = ensureShortcutToken(
                prefs,
                open = { encoded -> runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.let(cipher::decrypt)?.decodeToString() },
                seal = { plain -> Base64.encodeToString(cipher.encrypt(plain.encodeToByteArray()), Base64.NO_WRAP) },
            )
        }
        return token
    }

    /** Copies the version-1 keys to the per-device ones, once; safe to run on every start. */
    suspend fun migrate() {
        store.edit { SecretMigration.apply(it) }
    }

    /** A plane's two pin keys, read first, then ordered by [SecretKeys.pins]. */
    private suspend fun readPins(first: String, second: String): List<String> {
        val values = mapOf(first to read(first), second to read(second))
        return SecretKeys.pins({ values[it] }, first, second)
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

        /** Secure-store key of the launcher-shortcut token; outside [SecretKeys], which are per device. */
        const val SHORTCUT_TOKEN = "shortcut/token"

        /** Diagnostics only (PinMismatchLog); outside [SecretKeys], so forgetting the TV keeps the history. */
        private const val PIN_MISMATCHES = "diag/pin-mismatches"
        private const val REMOTE_KEY_LABEL = "diag/remote-key"

        /**
         * Inside one edit: the readable token already stored, else a fresh one written in its place.
         * [open] and [seal] are the cipher and its encoding; touches no other key.
         */
        internal fun ensureShortcutToken(
            prefs: MutablePreferences,
            open: (String) -> String?,
            seal: (String) -> String,
            fresh: () -> String = { ShortcutGuard.newToken() },
        ): String {
            val key = stringPreferencesKey(SHORTCUT_TOKEN)
            prefs[key]?.let(open)?.takeIf { it.isNotBlank() }?.let { return it }
            return fresh().also { prefs[key] = seal(it) }
        }

        private fun legacyOf(name: String): String? = SecretKeys.legacy.entries.firstOrNull { it.value == name }?.key

        /** The encrypted blob [read] decrypts: the per-device key, else its version-1 twin. */
        internal fun encodedSecret(prefs: Preferences, name: String): String? =
            prefs[stringPreferencesKey(name)] ?: legacyOf(name)?.let { prefs[stringPreferencesKey(it)] }
    }
}
