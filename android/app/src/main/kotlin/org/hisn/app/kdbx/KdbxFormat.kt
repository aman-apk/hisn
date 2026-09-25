package org.hisn.app.kdbx

import java.util.UUID

/**
 * Format constants and header parameter carrier for KDBX 3.1 / 4.x.
 *
 * These values are fixed by the KeePass2 file format; they must match the desktop
 * implementation byte for byte or databases will not round trip.
 */
object Kdbx {
    const val SIGNATURE_1 = 0x9AA2D903.toInt()
    const val SIGNATURE_2 = 0xB54BFB67.toInt()

    const val FILE_VERSION_CRITICAL_MASK = 0xFFFF0000.toInt()
    const val FILE_VERSION_3_1 = 0x00030001
    const val FILE_VERSION_4 = 0x00040000
    const val FILE_VERSION_4_1 = 0x00040001

    // Outer header field ids
    const val HDR_END = 0
    const val HDR_COMMENT = 1
    const val HDR_CIPHER_ID = 2
    const val HDR_COMPRESSION_FLAGS = 3
    const val HDR_MASTER_SEED = 4
    const val HDR_TRANSFORM_SEED = 5      // KDBX3 only
    const val HDR_TRANSFORM_ROUNDS = 6    // KDBX3 only
    const val HDR_ENCRYPTION_IV = 7
    const val HDR_PROTECTED_STREAM_KEY = 8 // KDBX3 only
    const val HDR_STREAM_START_BYTES = 9   // KDBX3 only
    const val HDR_INNER_RANDOM_STREAM_ID = 10 // KDBX3 only
    const val HDR_KDF_PARAMETERS = 11      // KDBX4
    const val HDR_PUBLIC_CUSTOM_DATA = 12  // KDBX4

    // Inner header field ids (KDBX4)
    const val INNER_END = 0
    const val INNER_RANDOM_STREAM_ID = 1
    const val INNER_RANDOM_STREAM_KEY = 2
    const val INNER_BINARY = 3

    // Inner random stream algorithms
    const val STREAM_ARC4 = 1 // rejected
    const val STREAM_SALSA20 = 2
    const val STREAM_CHACHA20 = 3

    // Compression
    const val COMPRESSION_NONE = 0
    const val COMPRESSION_GZIP = 1

    /** Fixed Salsa20 IV used for the KDBX3 inner stream. */
    val INNER_STREAM_SALSA20_IV = byteArrayOf(
        0xE8.toByte(), 0x30, 0x09, 0x4B, 0x97.toByte(), 0x20, 0x5D, 0x2A
    )

    // Cipher UUIDs
    val CIPHER_AES128: UUID = UUID.fromString("61ab05a1-9464-41c3-8d74-3a563df8dd35")
    val CIPHER_AES256: UUID = UUID.fromString("31c1f2e6-bf71-4350-be58-05216afc5aff")
    val CIPHER_TWOFISH: UUID = UUID.fromString("ad68f29f-576f-4bb9-a36a-d47af965346c")
    val CIPHER_CHACHA20: UUID = UUID.fromString("d6038a2b-8b6f-4cb5-a524-339a31dbb59a")

    // KDF UUIDs
    val KDF_AES_KDBX3: UUID = UUID.fromString("c9d9f39a-628a-4460-bf74-0d08c18a4fea")
    val KDF_AES_KDBX4: UUID = UUID.fromString("7c02bb82-79a7-4ac0-927d-114a00648238")
    val KDF_ARGON2D: UUID = UUID.fromString("ef636ddf-8c29-444b-91f7-a9a403e30a0c")
    val KDF_ARGON2ID: UUID = UUID.fromString("9e298b19-56db-4773-b23d-fc3ec6f0a1e6")

    // KDF parameter keys inside the variant map
    const val KDF_PARAM_UUID = "\$UUID"
    const val KDF_PARAM_ROUNDS = "R"
    const val KDF_PARAM_SEED = "S"
    const val KDF_PARAM_PARALLELISM = "P"
    const val KDF_PARAM_MEMORY = "M"
    const val KDF_PARAM_ITERATIONS = "I"
    const val KDF_PARAM_VERSION = "V"
    const val KDF_PARAM_SECRET_KEY = "K"
    const val KDF_PARAM_ASSOC_DATA = "A"

    const val VARIANTMAP_VERSION = 0x0100
    const val VARIANTMAP_CRITICAL_MASK = 0xFF00

    // Variant map value types
    const val VM_END = 0x00
    const val VM_UINT32 = 0x04
    const val VM_UINT64 = 0x05
    const val VM_BOOL = 0x08
    const val VM_INT32 = 0x0C
    const val VM_INT64 = 0x0D
    const val VM_STRING = 0x18
    const val VM_BYTEARRAY = 0x42

    /** Argon2 defaults matching the desktop app's "new database" settings. */
    const val ARGON2_DEFAULT_MEMORY = 64L * 1024 * 1024 // bytes
    const val ARGON2_DEFAULT_PARALLELISM = 2
    const val ARGON2_DEFAULT_ITERATIONS = 10L
    const val ARGON2_VERSION_13 = 0x13

    /**
     * Hard ceilings for KDF parameters read from a file. A database file is untrusted input:
     * hostile parameters would otherwise make the phone attempt an allocation or a computation
     * it cannot finish, which is a denial of service dressed up as a KeePass file. The limits
     * are far above anything a real benchmark produces on any current device.
     */
    const val ARGON2_MAX_MEMORY = 1024L * 1024 * 1024 // 1 GiB
    const val ARGON2_MAX_ITERATIONS = 10_000_000L
    const val AES_KDF_MAX_ROUNDS = 100_000_000L

    /** رسالة المستخدم عند تجاوز الحدود؛ هذه الطبقة لا تملك موارد نصوص فتُخزَّن الرسالة هنا. */
    const val KDF_LIMIT_MESSAGE = "إعدادات اشتقاق المفتاح في هذا الملف تتجاوز ما يستطيع هذا الجهاز حسابه."
}

/**
 * Everything needed to re-encrypt a database the way it was encrypted when read.
 * Defaults describe a fresh KDBX 4.1 / AES-256 / Argon2d database.
 */
data class KdbxParams(
    var version: Int = Kdbx.FILE_VERSION_4_1,
    var cipherUuid: UUID = Kdbx.CIPHER_AES256,
    var compression: Int = Kdbx.COMPRESSION_GZIP,
    var kdfUuid: UUID = Kdbx.KDF_ARGON2D,
    var kdfRounds: Long = Kdbx.ARGON2_DEFAULT_ITERATIONS,
    var kdfMemory: Long = Kdbx.ARGON2_DEFAULT_MEMORY,
    var kdfParallelism: Int = Kdbx.ARGON2_DEFAULT_PARALLELISM,
    var kdfVersion: Int = Kdbx.ARGON2_VERSION_13,
    /** Extra KDF variant-map entries we did not model, preserved on write. */
    var kdfExtras: MutableMap<String, VariantValue> = linkedMapOf(),
    var publicCustomData: MutableMap<String, VariantValue> = linkedMapOf(),
) {
    val isKdbx4: Boolean get() = (version and Kdbx.FILE_VERSION_CRITICAL_MASK) >= Kdbx.FILE_VERSION_4
}

/** Typed value inside a KDBX4 variant map. */
sealed class VariantValue {
    data class U32(val value: Int) : VariantValue()
    data class U64(val value: Long) : VariantValue()
    data class Bool(val value: Boolean) : VariantValue()
    data class I32(val value: Int) : VariantValue()
    data class I64(val value: Long) : VariantValue()
    data class Str(val value: String) : VariantValue()
    data class Bytes(val value: ByteArray) : VariantValue() {
        override fun equals(other: Any?) = other is Bytes && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
}

class KdbxException(message: String, cause: Throwable? = null) : Exception(message, cause)
