package com.prosync.crmcompanion

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Quick sign-in keys, one per person on this phone. The CRM issues a random key after a
 * password sign-in; it is stored encrypted with Android Keystore keys that never leave the
 * phone's secure hardware:
 *  - the PIN copy is encrypted with a plain Keystore key (the CRM checks the PIN),
 *  - the fingerprint copy is encrypted with a key that only opens after a fingerprint scan,
 *    and Android destroys that key when any new fingerprint is added to the phone.
 * The phone never stores the password or the PIN.
 */
object QuickSignInStore {
    private const val PREFS = "quick_sign_in"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PIN_KEY = "prosync_quick_pin"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun bioAlias(employeeId: String) = "prosync_quick_bio_$employeeId"
    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasQuickSignIn(context: Context, employeeId: String) = prefs(context).contains("pin_$employeeId")

    fun hasFingerprint(context: Context, employeeId: String) =
        prefs(context).contains("bio_$employeeId") && fingerprintAvailable(context)

    /** A fingerprint sensor with at least one fingerprint saved in the phone's settings. */
    fun fingerprintAvailable(context: Context) =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** People who set up fingerprint sign-in on this phone (used for one-touch sign-in). */
    fun fingerprintEmployeeIds(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith("bio_") }.map { it.removePrefix("bio_") }

    // ---- PIN copy ----

    private fun pinKey(): SecretKey {
        (keyStore().getEntry(PIN_KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(PIN_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun savePinToken(context: Context, employeeId: String, token: String, expiresAt: Long) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, pinKey()) }
        prefs(context).edit()
            .putString("pin_$employeeId", pack(cipher.iv, cipher.doFinal(token.toByteArray())))
            .putLong("expires_$employeeId", expiresAt)
            .apply()
    }

    fun pinToken(context: Context, employeeId: String): String? = runCatching {
        val (iv, data) = unpack(prefs(context).getString("pin_$employeeId", null) ?: return null)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, pinKey(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data))
    }.getOrNull()

    // ---- Fingerprint copy ----

    private fun createBioKey(employeeId: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            val spec = KeyGenParameterSpec.Builder(bioAlias(employeeId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                // A fingerprint added later must not be able to sign in as this person.
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            init(spec.build())
        }.generateKey()

    /** Cipher to lock the key behind the fingerprint; the fingerprint prompt unlocks it. */
    fun fingerprintEncryptCipher(employeeId: String): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createBioKey(employeeId)) }

    fun saveFingerprintToken(context: Context, employeeId: String, cipher: Cipher, token: String) {
        prefs(context).edit().putString("bio_$employeeId", pack(cipher.iv, cipher.doFinal(token.toByteArray()))).apply()
    }

    /**
     * Cipher to read the key after a fingerprint scan, or null when the fingerprint key is gone
     * (a new fingerprint was added to the phone): fingerprint sign-in is then switched off.
     */
    fun fingerprintDecryptCipher(context: Context, employeeId: String): Cipher? {
        val stored = prefs(context).getString("bio_$employeeId", null) ?: return null
        return try {
            val key = (keyStore().getEntry(bioAlias(employeeId), null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unpack(stored).first)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearFingerprint(context, employeeId)
            null
        }
    }

    fun readFingerprintToken(context: Context, employeeId: String, cipher: Cipher): String? = runCatching {
        String(cipher.doFinal(unpack(prefs(context).getString("bio_$employeeId", null) ?: return null).second))
    }.getOrNull()

    fun clearFingerprint(context: Context, employeeId: String) {
        prefs(context).edit().remove("bio_$employeeId").apply()
        runCatching { keyStore().deleteEntry(bioAlias(employeeId)) }
    }

    /** Forget quick sign-in for this person on this phone (expired, locked, deactivated). */
    fun clear(context: Context, employeeId: String) {
        prefs(context).edit().remove("pin_$employeeId").remove("expires_$employeeId").apply()
        clearFingerprint(context, employeeId)
    }

    // ---- "Not now" on the setup offer: ask again after a week ----

    fun setupSkippedRecently(context: Context, employeeId: String) =
        System.currentTimeMillis() - prefs(context).getLong("skip_$employeeId", 0L) < 7L * 86_400_000

    fun skipSetup(context: Context, employeeId: String) =
        prefs(context).edit().putLong("skip_$employeeId", System.currentTimeMillis()).apply()

    private fun pack(iv: ByteArray, data: ByteArray) =
        "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(data, Base64.NO_WRAP)}"

    private fun unpack(value: String): Pair<ByteArray, ByteArray> {
        val (iv, data) = value.split(":", limit = 2)
        return Base64.decode(iv, Base64.NO_WRAP) to Base64.decode(data, Base64.NO_WRAP)
    }
}
