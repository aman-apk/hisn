package org.hisn.app.kdbx

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The composite key did not unlock the database.
 *
 * Deliberately distinct from [KdbxException]: the UI must re-prompt for the password rather than
 * tell the user their file is broken. Every other failure mode is a [KdbxException].
 */
class KdbxWrongCredentialsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Seconds between 0001-01-01T00:00:00Z (the KDBX epoch) and the Unix epoch. */
internal const val KDBX_EPOCH_OFFSET_SECONDS = 62_135_596_800L

internal fun base64Decode(text: String): ByteArray =
    // MIME decoder, like Qt's fromBase64, tolerates the line breaks some writers insert.
    try {
        Base64.getMimeDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw KdbxException("Malformed base64 value in the database XML", e)
    }

internal fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

/**
 * The parsed outer header. Exposed so callers can show cipher/KDF details, or check a file's
 * format version, without holding the credentials.
 */
class KdbxHeader internal constructor(
    val params: KdbxParams,
    val masterSeed: ByteArray,
    val encryptionIv: ByteArray,
    /** KDBX 3 only: the AES-KDF seed, which KDBX 4 carries inside the KDF variant map instead. */
    val transformSeed: ByteArray?,
    /** KDBX 3 only: the inner random stream key (KDBX 4 moves it into the inner header). */
    val protectedStreamKey: ByteArray?,
    /** KDBX 3 only: the 32 plaintext bytes that prove the key was right. */
    val streamStartBytes: ByteArray?,
    /** KDBX 3 only: the inner stream algorithm id. */
    val innerRandomStreamId: Int,
    /** Header bytes exactly as stored, which the SHA-256 and HMAC are computed over. */
    val rawBytes: ByteArray,
    /** Offset of the first byte after the header. */
    val payloadOffset: Int,
)

/**
 * Reads KDBX 3.1 and KDBX 4.0/4.1 databases produced by KeePass and KeePassXC.
 *
 * The reader is deliberately whole-file: databases are small, and holding the plaintext in one
 * buffer lets every integrity check run before a single field reaches the model.
 */
object KdbxReader {

    /** Parses signatures, version and outer header without touching the encrypted payload. */
    fun readHeaderOnly(bytes: ByteArray): KdbxHeader {
        if (bytes.size < 12) throw KdbxException("File is too small to be a KeePass database")

        val signature1 = bytes.u32(0)
        val signature2 = bytes.u32(4)
        if (signature1 != Kdbx.SIGNATURE_1 || signature2 != Kdbx.SIGNATURE_2) {
            throw KdbxException("Not a KeePass database file (bad file signature)")
        }

        val version = bytes.u32(8)
        val critical = version and Kdbx.FILE_VERSION_CRITICAL_MASK
        if (critical > Kdbx.FILE_VERSION_4) {
            throw KdbxException(
                "This database uses format version ${versionName(version)}, which this version of Hisn cannot read"
            )
        }
        if (critical < FILE_VERSION_2) {
            throw KdbxException(
                "Database format version ${versionName(version)} is too old; open it once with KeePassXC to upgrade it"
            )
        }
        // KDBX 2.x shares the KDBX 3 layout apart from the in-XML header hash, so it reads the same way.
        val isKdbx4 = critical >= Kdbx.FILE_VERSION_4

        val params = KdbxParams(version = version)
        var masterSeed: ByteArray? = null
        var encryptionIv: ByteArray? = null
        var transformSeed: ByteArray? = null
        var transformRounds: Long? = null
        var protectedStreamKey: ByteArray? = null
        var streamStartBytes: ByteArray? = null
        var innerStreamId = Kdbx.STREAM_SALSA20
        var cipherSeen = false
        var kdfSeedFromVariantMap: ByteArray? = null

        var pos = 12
        while (true) {
            if (pos >= bytes.size) throw KdbxException("Database header is truncated")
            val fieldId = bytes[pos].toInt() and 0xFF
            pos++
            val fieldLength = if (isKdbx4) {
                bytes.u32(pos).also { pos += 4 }
            } else {
                bytes.u16(pos).also { pos += 2 }
            }
            if (fieldLength < 0 || pos + fieldLength > bytes.size) {
                throw KdbxException("Database header field $fieldId declares an impossible length")
            }
            val data = bytes.copyOfRange(pos, pos + fieldLength)
            pos += fieldLength

            when (fieldId) {
                Kdbx.HDR_END -> Unit
                Kdbx.HDR_COMMENT -> Unit

                Kdbx.HDR_CIPHER_ID -> {
                    params.cipherUuid = uuidFromRfc4122(data)
                    // Fail here rather than after the KDF has burned seconds of the user's time.
                    Crypto.ivSizeFor(params.cipherUuid)
                    cipherSeen = true
                }

                Kdbx.HDR_COMPRESSION_FLAGS -> {
                    val flag = data.u32(0)
                    if (flag != Kdbx.COMPRESSION_NONE && flag != Kdbx.COMPRESSION_GZIP) {
                        throw KdbxException("Unsupported compression algorithm id $flag")
                    }
                    params.compression = flag
                }

                Kdbx.HDR_MASTER_SEED -> {
                    if (data.size != 32) throw KdbxException("Master seed must be 32 bytes, got ${data.size}")
                    masterSeed = data
                }

                Kdbx.HDR_ENCRYPTION_IV -> encryptionIv = data

                Kdbx.HDR_TRANSFORM_SEED -> {
                    if (isKdbx4) throw KdbxException("KDBX 4 file contains a legacy KDBX 3 header field")
                    if (data.size != 32) throw KdbxException("Transform seed must be 32 bytes, got ${data.size}")
                    transformSeed = data
                }

                Kdbx.HDR_TRANSFORM_ROUNDS -> {
                    if (isKdbx4) throw KdbxException("KDBX 4 file contains a legacy KDBX 3 header field")
                    transformRounds = data.u64(0)
                }

                Kdbx.HDR_PROTECTED_STREAM_KEY -> {
                    if (isKdbx4) throw KdbxException("KDBX 4 file contains a legacy KDBX 3 header field")
                    protectedStreamKey = data
                }

                Kdbx.HDR_STREAM_START_BYTES -> {
                    if (isKdbx4) throw KdbxException("KDBX 4 file contains a legacy KDBX 3 header field")
                    if (data.size != 32) throw KdbxException("Stream start bytes must be 32 bytes, got ${data.size}")
                    streamStartBytes = data
                }

                Kdbx.HDR_INNER_RANDOM_STREAM_ID -> {
                    if (isKdbx4) throw KdbxException("KDBX 4 file contains a legacy KDBX 3 header field")
                    innerStreamId = data.u32(0)
                }

                Kdbx.HDR_KDF_PARAMETERS -> {
                    if (!isKdbx4) throw KdbxException("KDBX 3 file contains a KDBX 4 header field")
                    kdfSeedFromVariantMap = applyKdfParameters(readVariantMap(data), params)
                }

                Kdbx.HDR_PUBLIC_CUSTOM_DATA -> {
                    if (!isKdbx4) throw KdbxException("KDBX 3 file contains a KDBX 4 header field")
                    params.publicCustomData = readVariantMap(data)
                }

                // Unknown fields are tolerated the way the desktop tolerates them.
                else -> Unit
            }
            if (fieldId == Kdbx.HDR_END) break
        }

        if (!cipherSeen) throw KdbxException("Database header does not name a cipher")
        val seed = masterSeed ?: throw KdbxException("Database header is missing the master seed")
        val iv = encryptionIv ?: throw KdbxException("Database header is missing the encryption IV")
        val expectedIvSize = Crypto.ivSizeFor(params.cipherUuid)
        if (iv.size != expectedIvSize) {
            throw KdbxException("Encryption IV is ${iv.size} bytes, but this cipher needs $expectedIvSize")
        }

        if (isKdbx4) {
            if (kdfSeedFromVariantMap == null) throw KdbxException("Database header is missing the KDF parameters")
        } else {
            params.kdfUuid = Kdbx.KDF_AES_KDBX3
            params.kdfRounds = transformRounds
                ?: throw KdbxException("Database header is missing the KDF round count")
            if (transformSeed == null) throw KdbxException("Database header is missing the transform seed")
            if (protectedStreamKey == null) throw KdbxException("Database header is missing the field cipher key")
            if (streamStartBytes == null) throw KdbxException("Database header is missing the stream start bytes")
            if (innerStreamId == Kdbx.STREAM_ARC4) {
                throw KdbxException("This database uses the obsolete ARC4 field cipher, which is not supported")
            }
        }

        return KdbxHeader(
            params = params,
            masterSeed = seed,
            encryptionIv = iv,
            transformSeed = transformSeed ?: kdfSeedFromVariantMap,
            protectedStreamKey = protectedStreamKey,
            streamStartBytes = streamStartBytes,
            innerRandomStreamId = innerStreamId,
            rawBytes = bytes.copyOfRange(0, pos),
            payloadOffset = pos,
        )
    }

    fun read(bytes: ByteArray, key: CompositeKey): KdbxDatabase {
        val header = readHeaderOnly(bytes)
        return if (header.params.isKdbx4) readKdbx4(bytes, header, key) else readKdbx3(bytes, header, key)
    }

    // --- KDBX 4 -------------------------------------------------------------------------------

    private fun readKdbx4(bytes: ByteArray, header: KdbxHeader, key: CompositeKey): KdbxDatabase {
        val params = header.params
        val transformed = Crypto.transformKey(
            kdfUuid = params.kdfUuid,
            rawKey = key.rawKey(),
            seed = header.transformSeed ?: throw KdbxException("Database header is missing the KDF seed"),
            rounds = params.kdfRounds,
            memoryBytes = params.kdfMemory,
            parallelism = params.kdfParallelism,
            argonVersion = params.kdfVersion,
        )
        val masterKey = Crypto.sha256(header.masterSeed, transformed)
        val hmacKey = Crypto.sha512(header.masterSeed, transformed, byteArrayOf(0x01))
        Crypto.wipe(transformed)

        val checksumOffset = header.payloadOffset
        if (bytes.size < checksumOffset + 64) {
            throw KdbxException("Database file ends before the header checksums — the file is truncated")
        }
        val storedSha = bytes.copyOfRange(checksumOffset, checksumOffset + 32)
        val storedHmac = bytes.copyOfRange(checksumOffset + 32, checksumOffset + 64)

        if (!Crypto.constantTimeEquals(Crypto.sha256(header.rawBytes), storedSha)) {
            throw KdbxException("The database header checksum does not match — the file is corrupt")
        }
        if (!Crypto.constantTimeEquals(HmacBlockStream.headerMac(header.rawBytes, hmacKey), storedHmac)) {
            throw KdbxWrongCredentialsException("Wrong password or key file")
        }

        val ciphertext = HmacBlockStream.decode(bytes, checksumOffset + 64, hmacKey)
        val compressed = Crypto.decryptPayload(params.cipherUuid, masterKey, header.encryptionIv, ciphertext)
        Crypto.wipe(masterKey, hmacKey)
        val plaintext = if (params.compression == Kdbx.COMPRESSION_GZIP) gunzip(compressed) else compressed

        val db = KdbxDatabase(params = params)
        val inner = readInnerHeader(plaintext, db)
        val randomStream = InnerRandomStream(inner.streamId, inner.streamKey)
        val xmlBytes = plaintext.copyOfRange(inner.xmlOffset, plaintext.size)

        XmlDocumentReader(randomStream, db, inner.poolIndex).parse(parseDocument(xmlBytes))
        db.root.relink()
        return db
    }

    private class InnerHeader(
        val streamId: Int,
        val streamKey: ByteArray,
        val poolIndex: MutableMap<String, Int>,
        val xmlOffset: Int,
    )

    private fun readInnerHeader(plaintext: ByteArray, db: KdbxDatabase): InnerHeader {
        var pos = 0
        var streamId = -1
        var streamKey: ByteArray? = null
        val poolIndex = LinkedHashMap<String, Int>()

        while (true) {
            if (pos >= plaintext.size) throw KdbxException("Inner database header is truncated")
            val fieldId = plaintext[pos].toInt() and 0xFF
            pos++
            val length = plaintext.u32(pos)
            pos += 4
            if (length < 0 || pos + length > plaintext.size) {
                throw KdbxException("Inner header field $fieldId declares an impossible length")
            }
            val data = plaintext.copyOfRange(pos, pos + length)
            pos += length

            when (fieldId) {
                Kdbx.INNER_END -> return InnerHeader(
                    streamId = streamId.takeIf { it >= 0 }
                        ?: throw KdbxException("Inner header does not name a field cipher"),
                    streamKey = streamKey
                        ?: throw KdbxException("Inner header is missing the field cipher key"),
                    poolIndex = poolIndex,
                    xmlOffset = pos,
                )

                Kdbx.INNER_RANDOM_STREAM_ID -> streamId = data.u32(0)

                Kdbx.INNER_RANDOM_STREAM_KEY -> streamKey = data

                Kdbx.INNER_BINARY -> {
                    // First byte is the "protect in memory" flag; the payload follows it.
                    if (length < 1) throw KdbxException("Inner header attachment record is empty")
                    poolIndex[poolIndex.size.toString()] = db.addBinary(data.copyOfRange(1, length))
                }

                else -> Unit
            }
        }
    }

    // --- KDBX 3 -------------------------------------------------------------------------------

    private fun readKdbx3(bytes: ByteArray, header: KdbxHeader, key: CompositeKey): KdbxDatabase {
        val params = header.params
        val transformed = Crypto.aesKdf(
            seed = header.transformSeed ?: throw KdbxException("Database header is missing the transform seed"),
            rounds = params.kdfRounds,
            key = key.rawKey(),
        )
        val masterKey = Crypto.sha256(header.masterSeed, transformed)
        Crypto.wipe(transformed)

        val ciphertext = bytes.copyOfRange(header.payloadOffset, bytes.size)
        if (ciphertext.isEmpty()) throw KdbxException("Database file has no encrypted contents")

        val plaintext = try {
            Crypto.decryptPayload(params.cipherUuid, masterKey, header.encryptionIv, ciphertext)
        } catch (e: KdbxException) {
            // KDBX 3 has no header MAC, so a padding failure is how a wrong key shows up.
            throw KdbxWrongCredentialsException("Wrong password or key file", e)
        } finally {
            Crypto.wipe(masterKey)
        }

        val startBytes = header.streamStartBytes!!
        if (plaintext.size < 32 || !Crypto.constantTimeEquals(plaintext.copyOfRange(0, 32), startBytes)) {
            throw KdbxWrongCredentialsException("Wrong password or key file")
        }

        val blocks = HashedBlockStream.decode(plaintext, 32)
        val xmlBytes = if (params.compression == Kdbx.COMPRESSION_GZIP) gunzip(blocks) else blocks

        val db = KdbxDatabase(params = params)
        val randomStream = InnerRandomStream(header.innerRandomStreamId, header.protectedStreamKey!!)
        val reader = XmlDocumentReader(randomStream, db, mutableMapOf())
        reader.parse(parseDocument(xmlBytes))

        val storedHeaderHash = reader.headerHash
        if (storedHeaderHash != null && !Crypto.constantTimeEquals(Crypto.sha256(header.rawBytes), storedHeaderHash)) {
            throw KdbxException("The database header does not match the checksum stored inside it — the file is corrupt")
        }

        db.root.relink()
        return db
    }

    // --- Variant map --------------------------------------------------------------------------

    internal fun readVariantMap(data: ByteArray): LinkedHashMap<String, VariantValue> {
        if (data.size < 2) throw KdbxException("Variant map is too short")
        val version = data.u16(0) and Kdbx.VARIANTMAP_CRITICAL_MASK
        if (version > (Kdbx.VARIANTMAP_VERSION and Kdbx.VARIANTMAP_CRITICAL_MASK)) {
            throw KdbxException("Unsupported KeePass variant map version 0x${version.toString(16)}")
        }

        val map = LinkedHashMap<String, VariantValue>()
        var pos = 2
        while (true) {
            if (pos >= data.size) throw KdbxException("Variant map is not terminated")
            val type = data[pos].toInt() and 0xFF
            pos++
            if (type == Kdbx.VM_END) return map

            val nameLength = data.u32(pos)
            pos += 4
            if (nameLength < 0 || pos + nameLength > data.size) throw KdbxException("Invalid variant map entry name")
            val name = String(data, pos, nameLength, Charsets.UTF_8)
            pos += nameLength

            val valueLength = data.u32(pos)
            pos += 4
            if (valueLength < 0 || pos + valueLength > data.size) {
                throw KdbxException("Invalid variant map entry value for \"$name\"")
            }
            val value = data.copyOfRange(pos, pos + valueLength)
            pos += valueLength

            map[name] = when (type) {
                Kdbx.VM_BOOL -> {
                    if (valueLength != 1) throw KdbxException("Variant map Bool \"$name\" has a bad length")
                    VariantValue.Bool(value[0].toInt() != 0)
                }

                Kdbx.VM_INT32 -> {
                    if (valueLength != 4) throw KdbxException("Variant map Int32 \"$name\" has a bad length")
                    VariantValue.I32(value.u32(0))
                }

                Kdbx.VM_UINT32 -> {
                    if (valueLength != 4) throw KdbxException("Variant map UInt32 \"$name\" has a bad length")
                    VariantValue.U32(value.u32(0))
                }

                Kdbx.VM_INT64 -> {
                    if (valueLength != 8) throw KdbxException("Variant map Int64 \"$name\" has a bad length")
                    VariantValue.I64(value.u64(0))
                }

                Kdbx.VM_UINT64 -> {
                    if (valueLength != 8) throw KdbxException("Variant map UInt64 \"$name\" has a bad length")
                    VariantValue.U64(value.u64(0))
                }

                Kdbx.VM_STRING -> VariantValue.Str(String(value, Charsets.UTF_8))
                Kdbx.VM_BYTEARRAY -> VariantValue.Bytes(value)
                else -> throw KdbxException("Unknown variant map entry type 0x${type.toString(16)} for \"$name\"")
            }
        }
    }

    /** Copies the KDF variant map into [params] and returns the KDF seed/salt. */
    private fun applyKdfParameters(map: Map<String, VariantValue>, params: KdbxParams): ByteArray {
        val uuidBytes = (map[Kdbx.KDF_PARAM_UUID] as? VariantValue.Bytes)?.value
            ?: throw KdbxException("KDF parameters do not name an algorithm")
        var kdfUuid = uuidFromRfc4122(uuidBytes)
        // KDBX 3's AES-KDF id is what every writer stores; it means the parameterised KDBX 4 KDF here.
        if (kdfUuid == Kdbx.KDF_AES_KDBX3) kdfUuid = Kdbx.KDF_AES_KDBX4
        params.kdfUuid = kdfUuid

        val seed = (map[Kdbx.KDF_PARAM_SEED] as? VariantValue.Bytes)?.value
            ?: throw KdbxException("KDF parameters do not carry a seed")

        when (kdfUuid) {
            Kdbx.KDF_AES_KDBX4 -> {
                params.kdfRounds = integerOf(map[Kdbx.KDF_PARAM_ROUNDS])
                    ?: throw KdbxException("AES-KDF parameters do not carry a round count")
            }

            Kdbx.KDF_ARGON2D, Kdbx.KDF_ARGON2ID -> {
                params.kdfRounds = integerOf(map[Kdbx.KDF_PARAM_ITERATIONS])
                    ?: throw KdbxException("Argon2 parameters do not carry an iteration count")
                params.kdfMemory = integerOf(map[Kdbx.KDF_PARAM_MEMORY])
                    ?: throw KdbxException("Argon2 parameters do not carry a memory size")
                params.kdfParallelism = integerOf(map[Kdbx.KDF_PARAM_PARALLELISM])?.toInt()
                    ?: throw KdbxException("Argon2 parameters do not carry a parallelism degree")
                params.kdfVersion = integerOf(map[Kdbx.KDF_PARAM_VERSION])?.toInt()
                    ?: throw KdbxException("Argon2 parameters do not carry a version")
            }

            else -> throw KdbxException("Unsupported key derivation function $kdfUuid")
        }

        val modelled = setOf(
            Kdbx.KDF_PARAM_UUID, Kdbx.KDF_PARAM_SEED, Kdbx.KDF_PARAM_ROUNDS, Kdbx.KDF_PARAM_ITERATIONS,
            Kdbx.KDF_PARAM_MEMORY, Kdbx.KDF_PARAM_PARALLELISM, Kdbx.KDF_PARAM_VERSION,
        )
        params.kdfExtras = map.filterKeys { it !in modelled }.toMutableMap()
        return seed
    }

    private fun integerOf(value: VariantValue?): Long? = when (value) {
        is VariantValue.U32 -> value.value.toLong() and 0xFFFFFFFFL
        is VariantValue.I32 -> value.value.toLong()
        is VariantValue.U64 -> value.value
        is VariantValue.I64 -> value.value
        else -> null
    }

    private fun versionName(version: Int): String =
        "${(version ushr 16) and 0xFFFF}.${version and 0xFFFF}"

    // --- XML ----------------------------------------------------------------------------------

    private fun parseDocument(xmlBytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // A database file is untrusted input: no DTDs, no entity expansion, no external lookups.
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        } catch (e: Exception) {
            throw KdbxException("The decrypted database XML could not be parsed: ${e.message}", e)
        }
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Exception) {
            // Older parsers do not know every hardening switch; the ones they do know still apply.
        }
    }
}

// --- DOM helpers ------------------------------------------------------------------------------

private fun Element.childElements(): List<Element> {
    val nodes = childNodes
    val out = ArrayList<Element>(nodes.length)
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) out.add(node as Element)
    }
    return out
}

private fun Element.rawText(): String = textContent ?: ""

private fun serializeNode(node: Node, sb: StringBuilder) {
    when (node.nodeType) {
        Node.ELEMENT_NODE -> {
            val element = node as Element
            sb.append('<').append(element.nodeName)
            val attributes = element.attributes
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i)
                sb.append(' ').append(attribute.nodeName).append("=\"")
                escapeXmlInto(attribute.nodeValue ?: "", sb, attribute = true)
                sb.append('"')
            }
            val children = element.childNodes
            if (children.length == 0) {
                sb.append("/>")
                return
            }
            sb.append('>')
            for (i in 0 until children.length) serializeNode(children.item(i), sb)
            sb.append("</").append(element.nodeName).append('>')
        }

        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> escapeXmlInto(node.nodeValue ?: "", sb, attribute = false)

        else -> Unit
    }
}

internal fun escapeXmlInto(text: String, sb: StringBuilder, attribute: Boolean) {
    for (ch in text) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> if (attribute) sb.append("&quot;") else sb.append(ch)
            '\n' -> if (attribute) sb.append("&#10;") else sb.append(ch)
            '\r' -> sb.append("&#13;")
            '\t' -> if (attribute) sb.append("&#9;") else sb.append(ch)
            else -> sb.append(ch)
        }
    }
}

private fun serializeElement(element: Element): String =
    StringBuilder().also { serializeNode(element, it) }.toString()

private val BASE64_PATTERN = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)?$")

/** Custom-data key KeePassXC used for "exclude from reports" before KDBX 4.1 gained QualityCheck. */
private const val LEGACY_EXCLUDE_FROM_REPORTS_KEY = "KnownBad"

/** Oldest format the reader accepts; 2.x predates [Kdbx.FILE_VERSION_3_1] but is structurally identical. */
private const val FILE_VERSION_2 = 0x00020000

/**
 * Walks the decrypted XML document into the model.
 *
 * Traversal is strictly in document order because the inner random stream is a single keystream:
 * decrypting protected values out of order would produce garbage. Unknown elements are captured
 * verbatim so a database written by a newer desktop build loses nothing on the way back out.
 */
private class XmlDocumentReader(
    private val randomStream: InnerRandomStream,
    private val db: KdbxDatabase,
    private val poolIndex: MutableMap<String, Int>,
) {
    /** KDBX 3.1 stores the outer header's SHA-256 inside Meta so the header can be verified. */
    var headerHash: ByteArray? = null
        private set

    fun parse(document: Document) {
        val root = document.documentElement
            ?: throw KdbxException("The database XML is empty")
        if (root.nodeName != "KeePassFile") {
            throw KdbxException("Unexpected root element \"${root.nodeName}\" in the database XML")
        }
        var rootGroupSeen = false
        for (child in root.childElements()) {
            when (child.nodeName) {
                "Meta" -> parseMeta(child)
                "Root" -> {
                    if (rootGroupSeen) throw KdbxException("The database XML contains more than one Root element")
                    parseRoot(child)
                    rootGroupSeen = true
                }
            }
        }
        if (!rootGroupSeen) throw KdbxException("The database XML has no root group")
    }

    // --- Meta ---------------------------------------------------------------------------------

    private fun parseMeta(element: Element) {
        val meta = db.meta
        for (child in element.childElements()) {
            when (child.nodeName) {
                "Generator" -> meta.generator = readString(child)
                "HeaderHash" -> headerHash = readBinary(child).takeIf { it.isNotEmpty() }
                "DatabaseName" -> meta.databaseName = readString(child)
                "DatabaseNameChanged" -> meta.databaseNameChanged = readDate(child)
                "DatabaseDescription" -> meta.databaseDescription = readString(child)
                "DatabaseDescriptionChanged" -> meta.databaseDescriptionChanged = readDate(child)
                "DefaultUserName" -> meta.defaultUserName = readString(child)
                "DefaultUserNameChanged" -> meta.defaultUserNameChanged = readDate(child)
                "MaintenanceHistoryDays" -> meta.maintenanceHistoryDays = readLong(child, 365L).toInt()
                "Color" -> meta.color = readString(child)
                "MasterKeyChanged" -> meta.masterKeyChanged = readDate(child)
                "MasterKeyChangeRec" -> meta.masterKeyChangeRec = readLong(child, -1L)
                "MasterKeyChangeForce" -> meta.masterKeyChangeForce = readLong(child, -1L)
                "MemoryProtection" -> parseMemoryProtection(child)
                "CustomIcons" -> parseCustomIcons(child)
                "RecycleBinEnabled" -> meta.recycleBinEnabled = readBool(child)
                "RecycleBinUUID" -> meta.recycleBinUuid = readUuid(child)
                "RecycleBinChanged" -> meta.recycleBinChanged = readDate(child)
                "EntryTemplatesGroup" -> meta.entryTemplatesGroup = readUuid(child)
                "EntryTemplatesGroupChanged" -> meta.entryTemplatesGroupChanged = readDate(child)
                "LastSelectedGroup" -> meta.lastSelectedGroup = readUuid(child)
                "LastTopVisibleGroup" -> meta.lastTopVisibleGroup = readUuid(child)
                "HistoryMaxItems" -> meta.historyMaxItems = readLong(child, 10L).toInt()
                "HistoryMaxSize" -> meta.historyMaxSize = readLong(child, 6L * 1024 * 1024)
                "SettingsChanged" -> meta.settingsChanged = readDate(child)
                "Binaries" -> parseBinaries(child)
                "CustomData" -> parseCustomData(child, meta.customData)
                else -> meta.unknownXml.add(serializeElement(child))
            }
        }
    }

    private fun parseMemoryProtection(element: Element) {
        val meta = db.meta
        for (child in element.childElements()) {
            when (child.nodeName) {
                "ProtectTitle" -> meta.protectTitle = readBool(child)
                "ProtectUserName" -> meta.protectUserName = readBool(child)
                "ProtectPassword" -> meta.protectPassword = readBool(child)
                "ProtectURL" -> meta.protectUrl = readBool(child)
                "ProtectNotes" -> meta.protectNotes = readBool(child)
            }
        }
    }

    private fun parseCustomIcons(element: Element) {
        for (child in element.childElements()) {
            if (child.nodeName != "Icon") continue
            var uuid: UUID? = null
            var data: ByteArray? = null
            var name: String? = null
            var lastModified: Long? = null
            for (field in child.childElements()) {
                when (field.nodeName) {
                    "UUID" -> uuid = readUuid(field)
                    "Data" -> data = readBinary(field)
                    "Name" -> name = readString(field).takeIf { it.isNotEmpty() }
                    "LastModificationTime" -> lastModified = readDate(field)
                }
            }
            if (uuid == null || data == null) {
                throw KdbxException("A custom icon in the database is missing its id or image data")
            }
            if (db.meta.customIcons.none { it.uuid == uuid }) {
                db.meta.customIcons.add(CustomIcon(uuid, data, name, lastModified))
            }
        }
    }

    /** KDBX 3 keeps the attachment pool in Meta; KDBX 4 moved it into the inner header. */
    private fun parseBinaries(element: Element) {
        for (child in element.childElements()) {
            if (child.nodeName != "Binary") continue
            val id = child.getAttribute("ID").ifEmpty { poolIndex.size.toString() }
            val raw = readBinary(child)
            val data = if (isTrue(child.getAttribute("Compressed")) && raw.isNotEmpty()) gunzip(raw) else raw
            poolIndex[id] = db.addBinary(data)
        }
    }

    private fun parseCustomData(element: Element, target: MutableMap<String, String>) {
        for (child in element.childElements()) {
            if (child.nodeName != "Item") continue
            var key: String? = null
            var value: String? = null
            for (field in child.childElements()) {
                when (field.nodeName) {
                    "Key" -> key = readString(field)
                    "Value" -> value = readString(field)
                }
            }
            if (key == null || value == null) {
                throw KdbxException("A custom data item in the database is missing its key or value")
            }
            target[key] = value
        }
    }

    // --- Root ---------------------------------------------------------------------------------

    private fun parseRoot(element: Element) {
        var groupSeen = false
        for (child in element.childElements()) {
            when (child.nodeName) {
                "Group" -> {
                    if (groupSeen) throw KdbxException("The database XML contains more than one root group")
                    db.root = parseGroup(child)
                    groupSeen = true
                }

                "DeletedObjects" -> parseDeletedObjects(child)
            }
        }
        if (!groupSeen) throw KdbxException("The database XML has no root group")
    }

    private fun parseDeletedObjects(element: Element) {
        for (child in element.childElements()) {
            if (child.nodeName != "DeletedObject") continue
            var uuid: UUID? = null
            var deletionTime: Long? = null
            for (field in child.childElements()) {
                when (field.nodeName) {
                    "UUID" -> uuid = readUuid(field)
                    "DeletionTime" -> deletionTime = readDate(field)
                }
            }
            if (uuid != null && deletionTime != null) {
                db.deletedObjects.add(DeletedObject(uuid, deletionTime))
            }
        }
    }

    private fun parseGroup(element: Element): Group {
        val group = Group()
        var uuidSeen = false
        for (child in element.childElements()) {
            when (child.nodeName) {
                "UUID" -> readUuid(child)?.let { group.uuid = it; uuidSeen = true }
                "Name" -> group.name = readString(child)
                "Notes" -> group.notes = readString(child)
                "Tags" -> group.tags = readString(child)
                "IconID" -> group.iconId = readLong(child, 48L).toInt().coerceAtLeast(0)
                "CustomIconUUID" -> group.customIconUuid = readUuid(child)
                "Times" -> group.times = parseTimes(child)
                "IsExpanded" -> group.isExpanded = readBool(child)
                "DefaultAutoTypeSequence" -> group.defaultAutoTypeSequence = readString(child).takeIf { it.isNotEmpty() }
                "EnableAutoType" -> group.enableAutoType = readTriState(child)
                "EnableSearching" -> group.enableSearching = readTriState(child)
                "LastTopVisibleEntry" -> group.lastTopVisibleEntry = readUuid(child)
                "CustomData" -> parseCustomData(child, group.customData)
                "PreviousParentGroup" -> group.previousParentGroup = readUuid(child)
                "Entry" -> group.addEntry(parseEntry(child, history = false))
                "Group" -> group.addGroup(parseGroup(child))
                else -> group.unknownXml.add(serializeElement(child))
            }
        }
        if (!uuidSeen) group.uuid = UUID.randomUUID()
        return group
    }

    private fun parseEntry(element: Element, history: Boolean): Entry {
        val entry = Entry()
        var uuidSeen = false
        for (child in element.childElements()) {
            when (child.nodeName) {
                "UUID" -> readUuid(child)?.let { entry.uuid = it; uuidSeen = true }
                "IconID" -> entry.iconId = readLong(child, 0L).toInt().coerceAtLeast(0)
                "CustomIconUUID" -> entry.customIconUuid = readUuid(child)
                "ForegroundColor" -> entry.foregroundColor = readString(child).takeIf { it.isNotEmpty() }
                "BackgroundColor" -> entry.backgroundColor = readString(child).takeIf { it.isNotEmpty() }
                "OverrideURL" -> entry.overrideUrl = readString(child).takeIf { it.isNotEmpty() }
                "Tags" -> entry.tags = readString(child)
                "Times" -> entry.times = parseTimes(child)
                "String" -> parseEntryString(child, entry)
                "Binary" -> parseEntryBinary(child, entry)
                "AutoType" -> entry.autoType = parseAutoType(child)
                "QualityCheck" -> entry.qualityCheck = readBool(child)
                "CustomData" -> parseCustomData(child, entry.customData)
                "PreviousParentGroup" -> entry.previousParentGroup = readUuid(child)
                "History" -> {
                    if (history) throw KdbxException("A history entry itself contains history — the file is corrupt")
                    for (item in child.childElements()) {
                        if (item.nodeName == "Entry") entry.history.add(parseEntry(item, history = true))
                    }
                }

                else -> entry.unknownXml.add(serializeElement(child))
            }
        }
        if (!uuidSeen) entry.uuid = UUID.randomUUID()
        // Pre-4.1 databases carried the report-exclusion flag as a "KnownBad" custom data item;
        // the desktop upgrades it to the QualityCheck element, so do the same and drop the item.
        entry.customData.remove(LEGACY_EXCLUDE_FROM_REPORTS_KEY)?.let { value ->
            entry.qualityCheck = !value.equals("true", ignoreCase = true)
        }
        return entry
    }

    private fun parseEntryString(element: Element, entry: Entry) {
        var key: String? = null
        var value: ProtectedValue? = null
        for (child in element.childElements()) {
            when (child.nodeName) {
                "Key" -> key = readString(child)
                "Value" -> value = readProtectedValue(child)
            }
        }
        if (key == null || value == null) {
            throw KdbxException("An entry field in the database is missing its key or value")
        }
        entry.fields[key] = value
    }

    private fun parseEntryBinary(element: Element, entry: Entry) {
        var key: String? = null
        var ref: Int? = null
        for (child in element.childElements()) {
            when (child.nodeName) {
                "Key" -> key = readString(child)
                "Value" -> {
                    val refAttribute = child.getAttribute("Ref")
                    ref = if (refAttribute.isNotEmpty()) {
                        // Unknown ids would silently drop the attachment, so keep a placeholder slot.
                        poolIndex[refAttribute] ?: db.addBinary(ByteArray(0)).also { poolIndex[refAttribute] = it }
                    } else {
                        db.addBinary(readBinary(child))
                    }
                }
            }
        }
        if (key == null || ref == null) {
            throw KdbxException("An attachment in the database is missing its name or contents")
        }
        entry.attachments.add(Attachment(key, ref))
    }

    private fun parseAutoType(element: Element): AutoType {
        val autoType = AutoType()
        for (child in element.childElements()) {
            when (child.nodeName) {
                "Enabled" -> autoType.enabled = readBool(child)
                "DataTransferObfuscation" -> autoType.dataTransferObfuscation = readLong(child, 0L).toInt()
                "DefaultSequence" -> autoType.defaultSequence = readString(child).takeIf { it.isNotEmpty() }
                "Association" -> {
                    var window: String? = null
                    var sequence: String? = null
                    for (field in child.childElements()) {
                        when (field.nodeName) {
                            "Window" -> window = readString(field)
                            "KeystrokeSequence" -> sequence = readString(field)
                        }
                    }
                    if (window == null || sequence == null) {
                        throw KdbxException("An auto-type association is missing its window or key sequence")
                    }
                    autoType.associations.add(AutoTypeAssociation(window, sequence))
                }
            }
        }
        return autoType
    }

    private fun parseTimes(element: Element): Times {
        val times = Times()
        for (child in element.childElements()) {
            when (child.nodeName) {
                "LastModificationTime" -> readDate(child)?.let { times.lastModificationTime = it }
                "CreationTime" -> readDate(child)?.let { times.creationTime = it }
                "LastAccessTime" -> readDate(child)?.let { times.lastAccessTime = it }
                "ExpiryTime" -> times.expiryTime = readDate(child)
                "Expires" -> times.expires = readBool(child)
                "UsageCount" -> times.usageCount = readLong(child, 0L).toInt()
                "LocationChanged" -> readDate(child)?.let { times.locationChanged = it }
            }
        }
        return times
    }

    // --- Leaf readers -------------------------------------------------------------------------

    /**
     * Reads element text, decrypting it through the inner stream when the writer protected it.
     * Empty protected values consume no keystream, exactly as the desktop reader behaves.
     */
    private fun readProtectedValue(element: Element): ProtectedValue {
        val isProtected = isTrue(element.getAttribute("Protected"))
        val protectInMemory = isTrue(element.getAttribute("ProtectInMemory"))
        val raw = element.rawText()
        if (isProtected && raw.isNotEmpty()) {
            val plain = randomStream.process(base64Decode(raw))
            return ProtectedValue(String(plain, Charsets.UTF_8), true)
        }
        return ProtectedValue(raw, isProtected || protectInMemory)
    }

    private fun readString(element: Element): String = readProtectedValue(element).value

    private fun readBinary(element: Element): ByteArray {
        val isProtected = isTrue(element.getAttribute("Protected"))
        val raw = element.rawText()
        if (raw.isBlank()) return ByteArray(0)
        val data = base64Decode(raw)
        return if (isProtected && data.isNotEmpty()) randomStream.process(data) else data
    }

    private fun readBool(element: Element): Boolean {
        val text = readString(element).trim()
        return text.equals("true", ignoreCase = true) || text == "1"
    }

    /** KDBX tri-state: "null" means "inherit from the parent group". */
    private fun readTriState(element: Element): Boolean? {
        val text = readString(element).trim()
        return when {
            text.equals("true", ignoreCase = true) -> true
            text.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

    private fun readLong(element: Element, fallback: Long): Long =
        readString(element).trim().toLongOrNull() ?: fallback

    private fun readUuid(element: Element): UUID? {
        val data = readBinary(element)
        if (data.isEmpty()) return null
        if (data.size != 16) throw KdbxException("A UUID in the database is ${data.size} bytes long, expected 16")
        val uuid = uuidFromRfc4122(data)
        return if (uuid == NULL_UUID) null else uuid
    }

    /**
     * KDBX 4 stores timestamps as base64 little-endian seconds since 0001-01-01; KDBX 3.1 and
     * older writers store ISO-8601 text. Both appear in the wild, so detect rather than assume.
     */
    private fun readDate(element: Element): Long? {
        val text = readString(element).trim()
        if (text.isEmpty()) return null
        if (BASE64_PATTERN.matches(text)) {
            val raw = base64Decode(text)
            if (raw.isEmpty()) return null
            val padded = if (raw.size >= 8) raw else ByteArray(8).also { raw.copyInto(it) }
            return padded.u64(0) - KDBX_EPOCH_OFFSET_SECONDS
        }
        return parseIsoDate(text)
            ?: throw KdbxException("Unreadable timestamp \"$text\" in element ${element.nodeName}")
    }

    private fun parseIsoDate(text: String): Long? {
        try {
            return Instant.parse(text).epochSecond
        } catch (_: DateTimeParseException) {
        }
        try {
            return OffsetDateTime.parse(text).toEpochSecond()
        } catch (_: DateTimeParseException) {
        }
        return try {
            LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun isTrue(value: String?): Boolean =
        value != null && (value.equals("true", ignoreCase = true) || value == "1")
}
