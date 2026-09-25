package org.hisn.app.kdbx

import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Little-endian primitives. KDBX stores every integer little-endian regardless of host
 * byte order, so all sizes, indices and timestamps go through these helpers.
 */

internal fun le16(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

internal fun le32(value: Int): ByteArray = ByteArray(4) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

internal fun le64(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

internal fun ByteArray.u16(offset: Int): Int {
    requireRange(offset, 2)
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun ByteArray.u32(offset: Int): Int {
    requireRange(offset, 4)
    var v = 0
    for (i in 0 until 4) v = v or ((this[offset + i].toInt() and 0xFF) shl (8 * i))
    return v
}

internal fun ByteArray.u64(offset: Int): Long {
    requireRange(offset, 8)
    var v = 0L
    for (i in 0 until 8) v = v or ((this[offset + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun ByteArray.requireRange(offset: Int, length: Int) {
    if (offset < 0 || offset + length > size) {
        throw KdbxException("Truncated database file: needed $length bytes at offset $offset of $size")
    }
}

/** RFC 4122 big-endian byte form, which is what KDBX stores for every UUID. */
internal fun UUID.toRfc4122(): ByteArray {
    val out = ByteArray(16)
    var hi = mostSignificantBits
    var lo = leastSignificantBits
    for (i in 7 downTo 0) {
        out[i] = (hi and 0xFF).toByte()
        hi = hi ushr 8
    }
    for (i in 15 downTo 8) {
        out[i] = (lo and 0xFF).toByte()
        lo = lo ushr 8
    }
    return out
}

internal fun uuidFromRfc4122(bytes: ByteArray): UUID {
    if (bytes.size != 16) throw KdbxException("Invalid UUID length ${bytes.size}, expected 16")
    var hi = 0L
    var lo = 0L
    for (i in 0 until 8) hi = (hi shl 8) or (bytes[i].toLong() and 0xFF)
    for (i in 8 until 16) lo = (lo shl 8) or (bytes[i].toLong() and 0xFF)
    return UUID(hi, lo)
}

internal val NULL_UUID: UUID = UUID(0L, 0L)

/**
 * A stream cipher positioned at a fixed point in its keystream. Successive calls continue the
 * keystream, which is exactly what the KDBX inner random stream requires: protected values are
 * XORed with one continuous keystream consumed in document order.
 */
class Keystream internal constructor(private val engine: StreamCipher) {
    fun xor(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }
}

/**
 * Cryptographic primitives behind the KDBX format, backed by JCA for AES/SHA/HMAC and by
 * BouncyCastle for the algorithms Android does not ship on every API level (Salsa20, ChaCha20,
 * Twofish, Argon2).
 */
object Crypto {

    private val secureRandom = SecureRandom()

    // --- Digests and MACs -------------------------------------------------------------------

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** Length-independent comparison, so a MAC check never leaks how far it matched. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // --- Block ciphers ----------------------------------------------------------------------

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        aesCbc(Cipher.ENCRYPT_MODE, key, iv, data)

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        aesCbc(Cipher.DECRYPT_MODE, key, iv, data)

    private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    } catch (e: Exception) {
        throw KdbxException("AES-CBC ${if (mode == Cipher.ENCRYPT_MODE) "encryption" else "decryption"} failed", e)
    }

    fun twofishCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        twofishCbc(true, key, iv, data)

    fun twofishCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        twofishCbc(false, key, iv, data)

    private fun twofishCbc(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = try {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(TwofishEngine()), PKCS7Padding())
        cipher.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
        val buffer = ByteArray(cipher.getOutputSize(data.size))
        var written = cipher.processBytes(data, 0, data.size, buffer, 0)
        written += cipher.doFinal(buffer, written)
        buffer.copyOf(written)
    } catch (e: Exception) {
        throw KdbxException("Twofish-CBC ${if (encrypt) "encryption" else "decryption"} failed", e)
    }

    // --- Stream ciphers ---------------------------------------------------------------------

    /** ChaCha20 as specified by RFC 7539: 32-byte key, 12-byte nonce, counter starting at zero. */
    fun chacha20Keystream(key: ByteArray, iv: ByteArray): Keystream {
        if (key.size != 32) throw KdbxException("ChaCha20 needs a 32-byte key, got ${key.size}")
        if (iv.size != 12) throw KdbxException("ChaCha20 needs a 12-byte nonce, got ${iv.size}")
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), iv))
        return Keystream(engine)
    }

    fun chacha20(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        chacha20Keystream(key, iv).xor(data)

    /** Salsa20 with a 32-byte key and the format's 8-byte nonce. */
    fun salsa20Keystream(key: ByteArray, iv: ByteArray): Keystream {
        if (key.size != 32) throw KdbxException("Salsa20 needs a 32-byte key, got ${key.size}")
        if (iv.size != 8) throw KdbxException("Salsa20 needs an 8-byte nonce, got ${iv.size}")
        val engine = Salsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), iv))
        return Keystream(engine)
    }

    fun salsa20(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        salsa20Keystream(key, iv).xor(data)

    // --- Outer payload cipher ---------------------------------------------------------------

    /** IV length the header must carry for the given cipher. */
    fun ivSizeFor(cipherUuid: UUID): Int = when (cipherUuid) {
        Kdbx.CIPHER_AES128, Kdbx.CIPHER_AES256, Kdbx.CIPHER_TWOFISH -> 16
        Kdbx.CIPHER_CHACHA20 -> 12
        else -> throw KdbxException("Unsupported cipher $cipherUuid")
    }

    fun encryptPayload(cipherUuid: UUID, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        when (cipherUuid) {
            Kdbx.CIPHER_AES128, Kdbx.CIPHER_AES256 -> aesCbcEncrypt(key, iv, data)
            Kdbx.CIPHER_TWOFISH -> twofishCbcEncrypt(key, iv, data)
            Kdbx.CIPHER_CHACHA20 -> chacha20(key, iv, data)
            else -> throw KdbxException("Unsupported cipher $cipherUuid")
        }

    fun decryptPayload(cipherUuid: UUID, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        when (cipherUuid) {
            Kdbx.CIPHER_AES128, Kdbx.CIPHER_AES256 -> aesCbcDecrypt(key, iv, data)
            Kdbx.CIPHER_TWOFISH -> twofishCbcDecrypt(key, iv, data)
            Kdbx.CIPHER_CHACHA20 -> chacha20(key, iv, data)
            else -> throw KdbxException("Unsupported cipher $cipherUuid")
        }

    // --- Key derivation ---------------------------------------------------------------------

    /**
     * AES-KDF: [rounds] raw AES-256 ECB encryptions of the 32-byte key in place under [seed],
     * then SHA-256 of the result. There is no padding and no IV — it is the block function alone.
     */
    fun aesKdf(seed: ByteArray, rounds: Long, key: ByteArray): ByteArray {
        if (seed.size != 32) throw KdbxException("AES-KDF seed must be 32 bytes, got ${seed.size}")
        if (key.size != 32) throw KdbxException("AES-KDF input key must be 32 bytes, got ${key.size}")
        if (rounds <= 0) throw KdbxException("AES-KDF round count must be positive, got $rounds")
        if (rounds > Kdbx.AES_KDF_MAX_ROUNDS) throw KdbxException(Kdbx.KDF_LIMIT_MESSAGE)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
        val buffer = key.copyOf()
        try {
            var i = 0L
            while (i < rounds) {
                cipher.update(buffer, 0, 32, buffer, 0)
                i++
            }
            return sha256(buffer)
        } catch (e: Exception) {
            throw KdbxException("AES-KDF transformation failed", e)
        } finally {
            wipe(buffer)
        }
    }

    fun argon2(
        argon2id: Boolean,
        password: ByteArray,
        salt: ByteArray,
        iterations: Long,
        memoryBytes: Long,
        parallelism: Int,
        version: Int,
        outputLength: Int = 32,
    ): ByteArray {
        val memoryKib = memoryBytes / 1024L
        if (memoryKib < 8 || memoryKib > Int.MAX_VALUE) {
            throw KdbxException("Argon2 memory setting out of range: $memoryBytes bytes")
        }
        if (iterations < 1 || iterations > Int.MAX_VALUE) {
            throw KdbxException("Argon2 iteration count out of range: $iterations")
        }
        // A file's parameters, not the app's: past these ceilings the derivation cannot finish
        // on a phone, so the file is treated as hostile rather than attempted.
        if (memoryBytes > Kdbx.ARGON2_MAX_MEMORY || iterations > Kdbx.ARGON2_MAX_ITERATIONS) {
            throw KdbxException(Kdbx.KDF_LIMIT_MESSAGE)
        }
        if (parallelism < 1 || parallelism > 0xFFFFFF) {
            throw KdbxException("Argon2 parallelism out of range: $parallelism")
        }
        if (version != 0x10 && version != Kdbx.ARGON2_VERSION_13) {
            throw KdbxException("Unsupported Argon2 version 0x${version.toString(16)}")
        }
        val params = Argon2Parameters.Builder(
            if (argon2id) Argon2Parameters.ARGON2_id else Argon2Parameters.ARGON2_d
        )
            .withVersion(version)
            .withIterations(iterations.toInt())
            .withMemoryAsKB(memoryKib.toInt())
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(outputLength)
        generator.generateBytes(password, out)
        return out
    }

    /**
     * Runs the KDF named by [kdfUuid] over the composite raw key, producing the 32-byte
     * transformed key that both the master key and the HMAC key are built from.
     */
    fun transformKey(
        kdfUuid: UUID,
        rawKey: ByteArray,
        seed: ByteArray,
        rounds: Long,
        memoryBytes: Long,
        parallelism: Int,
        argonVersion: Int,
    ): ByteArray = when (kdfUuid) {
        Kdbx.KDF_AES_KDBX3, Kdbx.KDF_AES_KDBX4 -> aesKdf(seed, rounds, rawKey)
        Kdbx.KDF_ARGON2D -> argon2(false, rawKey, seed, rounds, memoryBytes, parallelism, argonVersion)
        Kdbx.KDF_ARGON2ID -> argon2(true, rawKey, seed, rounds, memoryBytes, parallelism, argonVersion)
        else -> throw KdbxException("Unsupported key derivation function $kdfUuid")
    }

    // --- Misc -------------------------------------------------------------------------------

    fun random(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }

    /** Overwrites key material that is no longer needed. Best effort — the JVM may still copy. */
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) array?.fill(0)
    }
}
