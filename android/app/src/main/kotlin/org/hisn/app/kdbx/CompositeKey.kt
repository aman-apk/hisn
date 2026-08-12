package org.hisn.app.kdbx

import java.io.File
import java.security.MessageDigest

/**
 * The credentials that unlock a database: password and/or key file.
 *
 * Composition matches the desktop implementation exactly — the composite raw key is
 * SHA-256 over the concatenated SHA-256 of each component, in the order password then key file.
 * Getting this wrong silently produces a different master key, so it is covered by
 * round-trip tests against databases written by the desktop app.
 */
class CompositeKey private constructor(private val components: List<ByteArray>) {

    /** SHA-256(concat(component raw keys)) — the input to the KDF. */
    fun rawKey(): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        components.forEach { sha.update(it) }
        return sha.digest()
    }

    fun isEmpty(): Boolean = components.isEmpty()

    companion object {
        fun build(password: String?, keyFile: ByteArray? = null): CompositeKey {
            val parts = mutableListOf<ByteArray>()
            if (password != null) parts.add(passwordKey(password))
            if (keyFile != null) parts.add(fileKey(keyFile))
            return CompositeKey(parts)
        }

        fun build(password: String?, keyFile: File?): CompositeKey =
            build(password, keyFile?.readBytes())

        /** PasswordKey: SHA-256 of the UTF-8 password bytes. */
        fun passwordKey(password: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))

        /**
         * FileKey with the same format autodetection the desktop uses, in the same order:
         * KeePass2 XML (v1 and v2), 32-byte binary, 64-char hex, else SHA-256 of the contents.
         */
        fun fileKey(data: ByteArray): ByteArray {
            parseXmlKeyFile(data)?.let { return it }
            if (data.size == 32) return data.copyOf()
            asHexKey(data)?.let { return it }
            return MessageDigest.getInstance("SHA-256").digest(data)
        }

        /**
         * KeePass2 XML key file. v1 stores base64 of the raw 32 bytes; v2 stores hex in
         * whitespace-separated groups plus a 4-byte truncated SHA-256 checksum we verify.
         */
        private fun parseXmlKeyFile(data: ByteArray): ByteArray? {
            val text = try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            if (!text.contains("<KeyFile", ignoreCase = true)) return null

            val version = Regex("<Version>\\s*([\\d.]+)\\s*</Version>", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()
            val dataMatch = Regex("<Data[^>]*>([\\s\\S]*?)</Data>", RegexOption.IGNORE_CASE)
                .find(text) ?: return null
            val rawInner = dataMatch.groupValues[1]

            if (version != null && version.startsWith("2")) {
                val hex = rawInner.filter { !it.isWhitespace() }
                val key = hexToBytes(hex) ?: return null
                val expected = Regex("<Data[^>]*Hash=\"([0-9a-fA-F]+)\"", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)
                if (expected != null) {
                    val actual = MessageDigest.getInstance("SHA-256").digest(key)
                        .copyOf(4).joinToString("") { "%02X".format(it) }
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw KdbxException("Key file checksum mismatch — the file may be corrupt")
                    }
                }
                return key
            }

            // v1: base64 payload of exactly 32 bytes
            return try {
                android.util.Base64.decode(rawInner.trim(), android.util.Base64.DEFAULT)
                    .takeIf { it.size == 32 }
            } catch (_: Exception) {
                null
            }
        }

        private fun asHexKey(data: ByteArray): ByteArray? {
            if (data.size != 64) return null
            val text = String(data, Charsets.US_ASCII)
            return hexToBytes(text)?.takeIf { it.size == 32 }
        }

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0 || hex.isEmpty()) return null
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        }
    }
}
