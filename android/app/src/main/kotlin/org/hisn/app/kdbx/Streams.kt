package org.hisn.app.kdbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Payload block size both desktop block streams use. Readers accept any size. */
private const val BLOCK_SIZE = 1024 * 1024

/**
 * KDBX 4 authenticated block format.
 *
 * Each block is `[32-byte HMAC-SHA256][LE32 payload size][payload]`, the MAC covering
 * `LE64(blockIndex) || LE32(size) || payload` under a per-block key
 * `SHA-512(LE64(blockIndex) || baseKey)`. A zero-length block terminates the stream and is
 * itself authenticated, so truncation is detected. The outer header is authenticated the same
 * way using block index 0xFFFFFFFFFFFFFFFF.
 */
object HmacBlockStream {

    /** 0xFFFFFFFFFFFFFFFF — the index reserved for the header MAC. */
    const val HEADER_BLOCK_INDEX: Long = -1L

    fun blockKey(baseKey: ByteArray, blockIndex: Long): ByteArray {
        if (baseKey.size != 64) throw KdbxException("HMAC base key must be 64 bytes, got ${baseKey.size}")
        return Crypto.sha512(le64(blockIndex), baseKey)
    }

    fun headerMac(header: ByteArray, baseKey: ByteArray): ByteArray =
        Crypto.hmacSha256(blockKey(baseKey, HEADER_BLOCK_INDEX), header)

    /**
     * Verifies and concatenates every block starting at [from].
     * Any MAC mismatch aborts: a partially authenticated payload is never returned.
     */
    fun decode(source: ByteArray, from: Int, baseKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(maxOf(source.size - from, 64))
        var pos = from
        var index = 0L
        while (true) {
            if (pos + 36 > source.size) {
                throw KdbxException("Database file ends inside HMAC block $index — the file is truncated")
            }
            val mac = source.copyOfRange(pos, pos + 32)
            pos += 32
            val sizeBytes = source.copyOfRange(pos, pos + 4)
            val size = source.u32(pos)
            pos += 4
            if (size < 0 || pos + size > source.size) {
                throw KdbxException("HMAC block $index declares an impossible size of $size bytes")
            }
            val block = source.copyOfRange(pos, pos + size)
            pos += size

            val expected = Crypto.hmacSha256(blockKey(baseKey, index), le64(index), sizeBytes, block)
            if (!Crypto.constantTimeEquals(expected, mac)) {
                throw KdbxException(
                    "Integrity check failed on HMAC block $index — the database file is corrupt or was tampered with"
                )
            }
            if (size == 0) return out.toByteArray()
            out.write(block)
            index++
        }
    }

    /** Splits [payload] into authenticated blocks, always closing with the zero-length terminator. */
    fun encode(payload: ByteArray, baseKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 64 + (payload.size / BLOCK_SIZE + 1) * 36)
        var index = 0L
        var offset = 0
        while (offset < payload.size) {
            val length = minOf(BLOCK_SIZE, payload.size - offset)
            writeBlock(out, baseKey, index, payload, offset, length)
            index++
            offset += length
        }
        writeBlock(out, baseKey, index, payload, 0, 0)
        return out.toByteArray()
    }

    private fun writeBlock(
        out: ByteArrayOutputStream,
        baseKey: ByteArray,
        index: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val block = if (length == 0) ByteArray(0) else payload.copyOfRange(offset, offset + length)
        val sizeBytes = le32(length)
        out.write(Crypto.hmacSha256(blockKey(baseKey, index), le64(index), sizeBytes, block))
        out.write(sizeBytes)
        if (block.isNotEmpty()) out.write(block)
    }
}

/**
 * KDBX 3 hashed block format: `[LE32 index][32-byte SHA-256][LE32 size][payload]`, ending with a
 * block whose size is zero and whose hash field is 32 zero bytes.
 */
object HashedBlockStream {

    fun decode(source: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(source.size - from, 64))
        var pos = from
        var index = 0
        while (true) {
            if (pos + 40 > source.size) {
                throw KdbxException("Database file ends inside hashed block $index — the file is truncated")
            }
            val blockIndex = source.u32(pos)
            pos += 4
            if (blockIndex != index) {
                throw KdbxException("Hashed block out of order: expected index $index, found $blockIndex")
            }
            val hash = source.copyOfRange(pos, pos + 32)
            pos += 32
            val size = source.u32(pos)
            pos += 4
            if (size < 0 || pos + size > source.size) {
                throw KdbxException("Hashed block $index declares an impossible size of $size bytes")
            }
            if (size == 0) {
                if (hash.any { it.toInt() != 0 }) {
                    throw KdbxException("Final hashed block carries a non-zero hash — the database file is corrupt")
                }
                return out.toByteArray()
            }
            val block = source.copyOfRange(pos, pos + size)
            pos += size
            if (!Crypto.constantTimeEquals(Crypto.sha256(block), hash)) {
                throw KdbxException("Integrity check failed on hashed block $index — the database file is corrupt")
            }
            out.write(block)
            index++
        }
    }

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 40 + (payload.size / BLOCK_SIZE + 1) * 40)
        var index = 0
        var offset = 0
        while (offset < payload.size) {
            val length = minOf(BLOCK_SIZE, payload.size - offset)
            val block = payload.copyOfRange(offset, offset + length)
            out.write(le32(index))
            out.write(Crypto.sha256(block))
            out.write(le32(length))
            out.write(block)
            index++
            offset += length
        }
        out.write(le32(index))
        out.write(ByteArray(32))
        out.write(le32(0))
        return out.toByteArray()
    }
}

/**
 * The inner random stream that protects individual field values inside the XML document.
 *
 * Both variants are plain stream ciphers whose keystream is consumed strictly in document order,
 * so a single instance must process every protected value in the order it appears in the file.
 */
class InnerRandomStream(algorithmId: Int, key: ByteArray) {

    private val keystream: Keystream = when (algorithmId) {
        Kdbx.STREAM_SALSA20 ->
            Crypto.salsa20Keystream(Crypto.sha256(key), Kdbx.INNER_STREAM_SALSA20_IV.copyOf())

        Kdbx.STREAM_CHACHA20 -> {
            val keyIv = Crypto.sha512(key)
            Crypto.chacha20Keystream(keyIv.copyOfRange(0, 32), keyIv.copyOfRange(32, 44))
        }

        Kdbx.STREAM_ARC4 ->
            throw KdbxException("This database uses the obsolete ARC4 field cipher, which is not supported")

        else ->
            throw KdbxException("Unknown inner stream cipher id $algorithmId")
    }

    /** XORs [data] with the next bytes of the keystream. Encryption and decryption are identical. */
    fun process(data: ByteArray): ByteArray = keystream.xor(data)
}

internal fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size / 2 + 64)
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

internal fun gunzip(data: ByteArray): ByteArray = try {
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
} catch (e: Exception) {
    throw KdbxException("Could not decompress the database contents — the file is corrupt", e)
}
