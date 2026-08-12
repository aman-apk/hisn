package org.hisn.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Biometric-gated storage for the master password.
 *
 * The password is sealed with AES-256-GCM under a key that lives in the Android Keystore with
 * `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`: the
 * ciphertext on disk is worthless without a fresh strong-biometric authentication, and enrolling
 * a new fingerprint or face destroys the key outright.
 *
 * Why the key is *imported* rather than generated inside the Keystore: an auth-bound symmetric
 * key requires authentication for encryption as well as decryption, which would force a second
 * biometric prompt just to switch the feature on. Instead the 32 random key bytes are produced
 * here, used once to seal the password, imported under the auth-bound protection, and then
 * zeroed. From that moment the only path back to the plaintext is through the Keystore, i.e.
 * through the biometric prompt.
 *
 * Usage from the UI:
 *   1. `decryptCipher()` -> hand the Cipher to `BiometricPrompt.CryptoObject`
 *   2. after `onAuthenticationSucceeded`, `unwrap(result.cryptoObject!!.cipher!!)`
 *   3. feed those bytes to `VaultRepository.unlockWithBiometric(...)`
 */
class SecureStore(context: Context) {

    /** Distinguishes recoverable states so the UI can pick the right localised message. */
    enum class Reason { NotEnrolled, KeyInvalidated, AuthenticationRequired, Unavailable, Corrupt }

    class SecureStoreException(val reason: Reason, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val appContext = context.applicationContext
    private val blobFile = File(File(appContext.filesDir, "secure").apply { mkdirs() }, "master.bio")

    /** True when a wrapped password is on disk *and* its Keystore key still exists. */
    val isEnrolled: Boolean
        get() = blobFile.isFile && runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Seals [password] for later biometric unlock, replacing any previous enrolment.
     * Fast enough to call on the main thread: one key generation plus one GCM operation.
     */
    fun enroll(password: String): Result<Unit> = runCatching {
        require(password.isNotEmpty()) { "Refusing to store an empty master password" }
        val keyBytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
            val sealed = cipher.doFinal(passwordBytes)
            importKey(keyBytes)
            writeBlob(iv, sealed)
        } catch (e: SecureStoreException) {
            throw e
        } catch (e: Exception) {
            clear()
            throw SecureStoreException(
                Reason.Unavailable,
                "This device refused to store the key in its hardware keystore: ${e.message}",
                e,
            )
        } finally {
            wipe(keyBytes)
            wipe(passwordBytes)
        }
    }

    /**
     * A Cipher initialised for decryption with the stored IV, ready for
     * `BiometricPrompt.CryptoObject`. Fails with [Reason.KeyInvalidated] after a new biometric
     * enrolment — the enrolment is cleared in that case, so the UI should ask the user to
     * switch biometric unlock back on with their password.
     */
    fun decryptCipher(): Result<Cipher> = runCatching {
        val (iv, _) = readBlob()
        val key = keyStore().getKey(ALIAS, null)
            ?: throw SecureStoreException(Reason.NotEnrolled, "No biometric key is enrolled for this vault")
        try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            throw SecureStoreException(
                Reason.KeyInvalidated,
                "Biometric enrolment changed, so the stored master password was discarded",
                e,
            )
        } catch (e: UserNotAuthenticatedException) {
            throw SecureStoreException(Reason.AuthenticationRequired, "Biometric authentication is required", e)
        }
    }

    /**
     * Unseals the master password with a Cipher that BiometricPrompt has already authorised.
     * The returned bytes are the caller's to wipe (see [wipe]).
     */
    fun unwrap(authenticatedCipher: Cipher): Result<ByteArray> = runCatching {
        val (_, sealed) = readBlob()
        try {
            authenticatedCipher.doFinal(sealed)
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            throw SecureStoreException(Reason.KeyInvalidated, "Biometric enrolment changed; enable it again", e)
        } catch (e: UserNotAuthenticatedException) {
            throw SecureStoreException(Reason.AuthenticationRequired, "Biometric authentication is required", e)
        } catch (e: Exception) {
            throw SecureStoreException(
                Reason.Corrupt,
                "The stored master password could not be decrypted: ${e.message}",
                e,
            )
        }
    }

    /** Removes the wrapped password and the Keystore key. Safe to call when nothing is enrolled. */
    fun clear() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        if (blobFile.exists()) {
            // Overwrite before unlinking: the ciphertext is short and the file may survive in
            // the journal otherwise.
            runCatching { blobFile.writeBytes(ByteArray(blobFile.length().toInt().coerceAtLeast(1))) }
            blobFile.delete()
        }
    }

    private fun importKey(keyBytes: ByteArray) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 0 seconds = authorise a single crypto operation, strong biometrics only.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        val store = keyStore()
        runCatching { store.deleteEntry(ALIAS) }
        store.setEntry(ALIAS, KeyStore.SecretKeyEntry(SecretKeySpec(keyBytes, "AES")), protection)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun writeBlob(iv: ByteArray, sealed: ByteArray) {
        val out = ByteArray(2 + iv.size + sealed.size)
        out[0] = BLOB_VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(sealed, 0, out, 2 + iv.size, sealed.size)
        blobFile.parentFile?.mkdirs()
        blobFile.writeBytes(out)
    }

    /** @return iv to ciphertext. */
    private fun readBlob(): Pair<ByteArray, ByteArray> {
        if (!blobFile.isFile) {
            throw SecureStoreException(Reason.NotEnrolled, "No biometric key is enrolled for this vault")
        }
        val raw = blobFile.readBytes()
        if (raw.size < 3 || raw[0] != BLOB_VERSION) {
            throw SecureStoreException(Reason.Corrupt, "The biometric key file is not readable")
        }
        val ivLen = raw[1].toInt() and 0xFF
        if (ivLen != IV_BYTES || raw.size <= 2 + ivLen) {
            throw SecureStoreException(Reason.Corrupt, "The biometric key file is not readable")
        }
        return raw.copyOfRange(2, 2 + ivLen) to raw.copyOfRange(2 + ivLen, raw.size)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "hisn_master_password_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val BLOB_VERSION: Byte = 1

        /** Zeroes a byte array holding secret material. */
        fun wipe(bytes: ByteArray?) {
            if (bytes != null) java.util.Arrays.fill(bytes, 0)
        }
    }
}
