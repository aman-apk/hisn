package org.hisn.app.kdbx

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Writes KDBX 3.1 and KDBX 4.0/4.1 files that KeePassXC opens without complaint.
 *
 * The writer is the exact mirror of [KdbxReader]: the format version, cipher and KDF settings come
 * from [KdbxDatabase.params], while every piece of randomness — master seed, encryption IV, KDF
 * seed and inner stream key — is generated fresh on each save, as the desktop does.
 */
object KdbxWriter {

    private val ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val END_OF_HEADER = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

    fun write(db: KdbxDatabase, key: CompositeKey): ByteArray {
        // 3.1 is the oldest format anything still writes, so a legacy 2.x/3.0 file is upgraded on
        // save — the same floor the desktop applies. params is updated so it describes the result.
        if (!db.params.isKdbx4 && db.params.version < Kdbx.FILE_VERSION_3_1) {
            db.params.version = Kdbx.FILE_VERSION_3_1
        }
        return if (db.params.isKdbx4) writeKdbx4(db, key) else writeKdbx3(db, key)
    }

    // --- KDBX 4 -------------------------------------------------------------------------------

    private fun writeKdbx4(db: KdbxDatabase, key: CompositeKey): ByteArray {
        val params = db.params
        val cipherUuid = params.cipherUuid
        val masterSeed = Crypto.random(32)
        val encryptionIv = Crypto.random(Crypto.ivSizeFor(cipherUuid))
        val protectedStreamKey = Crypto.random(64)
        val kdfSeed = Crypto.random(32)

        val transformed = Crypto.transformKey(
            kdfUuid = params.kdfUuid,
            rawKey = key.rawKey(),
            seed = kdfSeed,
            rounds = params.kdfRounds,
            memoryBytes = params.kdfMemory,
            parallelism = params.kdfParallelism,
            argonVersion = params.kdfVersion,
        )
        val masterKey = Crypto.sha256(masterSeed, transformed)
        val hmacKey = Crypto.sha512(masterSeed, transformed, byteArrayOf(0x01))
        Crypto.wipe(transformed)

        val header = buildHeaderV4(params, masterSeed, encryptionIv, kdfSeed)
        val headerSha = Crypto.sha256(header)
        val headerHmac = HmacBlockStream.headerMac(header, hmacKey)

        val pool = BinaryPool(db)
        val innerHeader = buildInnerHeader(protectedStreamKey, pool)
        val xml = XmlDatabaseWriter(
            db = db,
            version = params.version,
            randomStream = InnerRandomStream(Kdbx.STREAM_CHACHA20, protectedStreamKey),
            pool = pool,
            headerHash = null,
        ).build()

        val plaintext = innerHeader + xml
        val payload = if (params.compression == Kdbx.COMPRESSION_GZIP) gzip(plaintext) else plaintext
        val ciphertext = Crypto.encryptPayload(cipherUuid, masterKey, encryptionIv, payload)
        val blocks = HmacBlockStream.encode(ciphertext, hmacKey)
        Crypto.wipe(masterKey, hmacKey)

        val out = ByteArrayOutputStream(header.size + 64 + blocks.size)
        out.write(header)
        out.write(headerSha)
        out.write(headerHmac)
        out.write(blocks)
        return out.toByteArray()
    }

    private fun buildHeaderV4(
        params: KdbxParams,
        masterSeed: ByteArray,
        encryptionIv: ByteArray,
        kdfSeed: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(256)
        out.write(le32(Kdbx.SIGNATURE_1))
        out.write(le32(Kdbx.SIGNATURE_2))
        out.write(le32(params.version))

        writeHeaderField(out, Kdbx.HDR_CIPHER_ID, params.cipherUuid.toRfc4122(), wide = true)
        writeHeaderField(out, Kdbx.HDR_COMPRESSION_FLAGS, le32(params.compression), wide = true)
        writeHeaderField(out, Kdbx.HDR_MASTER_SEED, masterSeed, wide = true)
        writeHeaderField(out, Kdbx.HDR_ENCRYPTION_IV, encryptionIv, wide = true)
        writeHeaderField(out, Kdbx.HDR_KDF_PARAMETERS, serializeVariantMap(kdfParameters(params, kdfSeed)), wide = true)
        if (params.publicCustomData.isNotEmpty()) {
            writeHeaderField(
                out,
                Kdbx.HDR_PUBLIC_CUSTOM_DATA,
                serializeVariantMap(params.publicCustomData),
                wide = true,
            )
        }
        writeHeaderField(out, Kdbx.HDR_END, END_OF_HEADER, wide = true)
        return out.toByteArray()
    }

    private fun kdfParameters(params: KdbxParams, seed: ByteArray): Map<String, VariantValue> {
        val map = LinkedHashMap<String, VariantValue>()
        when (params.kdfUuid) {
            Kdbx.KDF_AES_KDBX3, Kdbx.KDF_AES_KDBX4 -> {
                // Other implementations only recognise the legacy AES-KDF id, so always write that one.
                map[Kdbx.KDF_PARAM_UUID] = VariantValue.Bytes(Kdbx.KDF_AES_KDBX3.toRfc4122())
                map[Kdbx.KDF_PARAM_ROUNDS] = VariantValue.U64(params.kdfRounds)
                map[Kdbx.KDF_PARAM_SEED] = VariantValue.Bytes(seed)
            }

            Kdbx.KDF_ARGON2D, Kdbx.KDF_ARGON2ID -> {
                map[Kdbx.KDF_PARAM_UUID] = VariantValue.Bytes(params.kdfUuid.toRfc4122())
                map[Kdbx.KDF_PARAM_VERSION] = VariantValue.U32(params.kdfVersion)
                map[Kdbx.KDF_PARAM_PARALLELISM] = VariantValue.U32(params.kdfParallelism)
                map[Kdbx.KDF_PARAM_MEMORY] = VariantValue.U64(params.kdfMemory)
                map[Kdbx.KDF_PARAM_ITERATIONS] = VariantValue.U64(params.kdfRounds)
                map[Kdbx.KDF_PARAM_SEED] = VariantValue.Bytes(seed)
            }

            else -> throw KdbxException("Cannot save with unsupported key derivation function ${params.kdfUuid}")
        }
        for ((name, value) in params.kdfExtras) if (name !in map) map[name] = value
        return map
    }

    private fun buildInnerHeader(protectedStreamKey: ByteArray, pool: BinaryPool): ByteArray {
        val out = ByteArrayOutputStream(protectedStreamKey.size + 64)
        writeInnerHeaderField(out, Kdbx.INNER_RANDOM_STREAM_ID, le32(Kdbx.STREAM_CHACHA20))
        writeInnerHeaderField(out, Kdbx.INNER_RANDOM_STREAM_KEY, protectedStreamKey)
        for (attachment in pool.data) {
            // Leading flag byte marks the payload as "protect in memory", matching the desktop.
            val record = ByteArray(attachment.size + 1)
            record[0] = 0x01
            attachment.copyInto(record, 1)
            writeInnerHeaderField(out, Kdbx.INNER_BINARY, record)
        }
        writeInnerHeaderField(out, Kdbx.INNER_END, ByteArray(0))
        return out.toByteArray()
    }

    // --- KDBX 3 -------------------------------------------------------------------------------

    private fun writeKdbx3(db: KdbxDatabase, key: CompositeKey): ByteArray {
        val params = db.params
        val cipherUuid = params.cipherUuid
        val masterSeed = Crypto.random(32)
        val encryptionIv = Crypto.random(Crypto.ivSizeFor(cipherUuid))
        val protectedStreamKey = Crypto.random(32)
        val streamStartBytes = Crypto.random(32)
        val transformSeed = Crypto.random(32)
        // KDBX 3 has no KDF parameter block: AES-KDF is the only option the format can express.
        val rounds = if (params.kdfRounds in 1..Int.MAX_VALUE.toLong()) params.kdfRounds else 60_000L

        val transformed = Crypto.aesKdf(transformSeed, rounds, key.rawKey())
        val masterKey = Crypto.sha256(masterSeed, transformed)
        Crypto.wipe(transformed)

        val header = buildHeaderV3(
            params, masterSeed, transformSeed, rounds, encryptionIv, protectedStreamKey, streamStartBytes
        )
        val headerSha = Crypto.sha256(header)

        val pool = BinaryPool(db)
        val xml = XmlDatabaseWriter(
            db = db,
            version = params.version,
            randomStream = InnerRandomStream(Kdbx.STREAM_SALSA20, protectedStreamKey),
            pool = pool,
            headerHash = headerSha,
        ).build()

        val body = if (params.compression == Kdbx.COMPRESSION_GZIP) gzip(xml) else xml
        val plaintext = streamStartBytes + HashedBlockStream.encode(body)
        val ciphertext = Crypto.encryptPayload(cipherUuid, masterKey, encryptionIv, plaintext)
        Crypto.wipe(masterKey)

        val out = ByteArrayOutputStream(header.size + ciphertext.size)
        out.write(header)
        out.write(ciphertext)
        return out.toByteArray()
    }

    private fun buildHeaderV3(
        params: KdbxParams,
        masterSeed: ByteArray,
        transformSeed: ByteArray,
        rounds: Long,
        encryptionIv: ByteArray,
        protectedStreamKey: ByteArray,
        streamStartBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(256)
        out.write(le32(Kdbx.SIGNATURE_1))
        out.write(le32(Kdbx.SIGNATURE_2))
        out.write(le32(params.version))

        writeHeaderField(out, Kdbx.HDR_CIPHER_ID, params.cipherUuid.toRfc4122(), wide = false)
        writeHeaderField(out, Kdbx.HDR_COMPRESSION_FLAGS, le32(params.compression), wide = false)
        writeHeaderField(out, Kdbx.HDR_MASTER_SEED, masterSeed, wide = false)
        writeHeaderField(out, Kdbx.HDR_TRANSFORM_SEED, transformSeed, wide = false)
        writeHeaderField(out, Kdbx.HDR_TRANSFORM_ROUNDS, le64(rounds), wide = false)
        writeHeaderField(out, Kdbx.HDR_ENCRYPTION_IV, encryptionIv, wide = false)
        writeHeaderField(out, Kdbx.HDR_PROTECTED_STREAM_KEY, protectedStreamKey, wide = false)
        writeHeaderField(out, Kdbx.HDR_STREAM_START_BYTES, streamStartBytes, wide = false)
        writeHeaderField(out, Kdbx.HDR_INNER_RANDOM_STREAM_ID, le32(Kdbx.STREAM_SALSA20), wide = false)
        writeHeaderField(out, Kdbx.HDR_END, END_OF_HEADER, wide = false)
        return out.toByteArray()
    }

    // --- Serialisation helpers ------------------------------------------------------------------

    /** KDBX 4 sizes header fields with a 32-bit length, KDBX 3 with a 16-bit one. */
    private fun writeHeaderField(out: ByteArrayOutputStream, fieldId: Int, data: ByteArray, wide: Boolean) {
        out.write(fieldId)
        if (wide) {
            out.write(le32(data.size))
        } else {
            if (data.size > 0xFFFF) throw KdbxException("Header field $fieldId is too large for a KDBX 3 file")
            out.write(le16(data.size))
        }
        out.write(data)
    }

    private fun writeInnerHeaderField(out: ByteArrayOutputStream, fieldId: Int, data: ByteArray) {
        out.write(fieldId)
        out.write(le32(data.size))
        out.write(data)
    }

    internal fun serializeVariantMap(map: Map<String, VariantValue>): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.write(le16(Kdbx.VARIANTMAP_VERSION))
        // Sorted so a saved file is byte-stable for identical settings, as the desktop's QMap is.
        for (name in map.keys.sorted()) {
            val value = map.getValue(name)
            val (type, data) = when (value) {
                is VariantValue.U32 -> Kdbx.VM_UINT32 to le32(value.value)
                is VariantValue.I32 -> Kdbx.VM_INT32 to le32(value.value)
                is VariantValue.U64 -> Kdbx.VM_UINT64 to le64(value.value)
                is VariantValue.I64 -> Kdbx.VM_INT64 to le64(value.value)
                is VariantValue.Bool -> Kdbx.VM_BOOL to byteArrayOf(if (value.value) 1 else 0)
                is VariantValue.Str -> Kdbx.VM_STRING to value.value.toByteArray(Charsets.UTF_8)
                is VariantValue.Bytes -> Kdbx.VM_BYTEARRAY to value.value
            }
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.write(type)
            out.write(le32(nameBytes.size))
            out.write(nameBytes)
            out.write(le32(data.size))
            out.write(data)
        }
        out.write(Kdbx.VM_END)
        return out.toByteArray()
    }

    internal fun formatDate(unixSeconds: Long, kdbx4: Boolean): String =
        if (kdbx4) base64Encode(le64(unixSeconds + KDBX_EPOCH_OFFSET_SECONDS))
        else ISO_DATE.format(Instant.ofEpochSecond(unixSeconds))
}

/**
 * Rebuilds the attachment pool for a save: entries keep their payloads, identical payloads collapse
 * onto one index, and every [Attachment.ref] is rewritten to the new index so the file stays
 * self-consistent even after entries have been deleted or merged.
 */
private class BinaryPool(private val db: KdbxDatabase) {

    val data = mutableListOf<ByteArray>()
    private val byOldRef = HashMap<Int, Int>()
    private val byContent = HashMap<String, Int>()

    init {
        for (entry in entriesInWriteOrder(db.root)) {
            for (attachment in entry.attachments) refFor(attachment.ref)
        }
    }

    fun refFor(oldRef: Int): Int = byOldRef.getOrPut(oldRef) {
        val payload = db.binaryFor(oldRef) ?: ByteArray(0)
        byContent.getOrPut(base64Encode(Crypto.sha256(payload))) {
            data.add(payload)
            data.size - 1
        }
    }

    companion object {
        /** Same traversal the XML writer uses, so indices are assigned before they are referenced. */
        fun entriesInWriteOrder(root: Group): List<Entry> {
            val out = mutableListOf<Entry>()
            fun walk(group: Group) {
                for (entry in group.entries) {
                    out.add(entry)
                    out.addAll(entry.history)
                }
                group.groups.forEach(::walk)
            }
            walk(root)
            return out
        }
    }
}

/** Minimal, tab-indented XML emitter — enough for KDBX and free of platform serialiser surprises. */
private class XmlBuilder {

    private val sb = StringBuilder(64 * 1024)
    private var depth = 0

    init {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
    }

    fun open(name: String) {
        indent()
        sb.append('<').append(name).append(">\n")
        depth++
    }

    fun close(name: String) {
        depth--
        indent()
        sb.append("</").append(name).append(">\n")
    }

    fun leaf(name: String, text: String, attributes: List<Pair<String, String>> = emptyList()) {
        indent()
        sb.append('<').append(name)
        for ((key, value) in attributes) {
            sb.append(' ').append(key).append("=\"")
            escapeXmlInto(value, sb, attribute = true)
            sb.append('"')
        }
        if (text.isEmpty()) {
            sb.append("/>\n")
            return
        }
        sb.append('>')
        escapeXmlInto(text, sb, attribute = false)
        sb.append("</").append(name).append(">\n")
    }

    /** Emits a preserved unknown element exactly as it was read. */
    fun raw(xml: String) {
        indent()
        sb.append(xml).append('\n')
    }

    fun toBytes(): ByteArray = sb.toString().toByteArray(Charsets.UTF_8)

    private fun indent() {
        repeat(depth) { sb.append('\t') }
    }
}

/**
 * Serialises the model back to the KeePass XML document.
 *
 * Protected values are encrypted through [randomStream] in document order, which is the only order
 * the reader on the other side can decrypt them in.
 */
private class XmlDatabaseWriter(
    private val db: KdbxDatabase,
    private val version: Int,
    private val randomStream: InnerRandomStream,
    private val pool: BinaryPool,
    /** KDBX 3.1 stores the outer header hash inside Meta; KDBX 4 authenticates the header instead. */
    private val headerHash: ByteArray?,
) {
    private val xml = XmlBuilder()
    private val isKdbx4 = (version and Kdbx.FILE_VERSION_CRITICAL_MASK) >= Kdbx.FILE_VERSION_4
    private val isKdbx41 = version >= Kdbx.FILE_VERSION_4_1

    fun build(): ByteArray {
        xml.open("KeePassFile")
        writeMeta()
        writeRoot()
        xml.close("KeePassFile")
        return xml.toBytes()
    }

    // --- Meta ---------------------------------------------------------------------------------

    private fun writeMeta() {
        val meta = db.meta
        xml.open("Meta")
        text("Generator", meta.generator)
        if (!isKdbx4 && headerHash != null) text("HeaderHash", base64Encode(headerHash))
        text("DatabaseName", meta.databaseName)
        date("DatabaseNameChanged", meta.databaseNameChanged)
        text("DatabaseDescription", meta.databaseDescription)
        date("DatabaseDescriptionChanged", meta.databaseDescriptionChanged)
        text("DefaultUserName", meta.defaultUserName)
        date("DefaultUserNameChanged", meta.defaultUserNameChanged)
        text("MaintenanceHistoryDays", meta.maintenanceHistoryDays.toString())
        text("Color", meta.color)
        date("MasterKeyChanged", meta.masterKeyChanged)
        text("MasterKeyChangeRec", meta.masterKeyChangeRec.toString())
        text("MasterKeyChangeForce", meta.masterKeyChangeForce.toString())

        xml.open("MemoryProtection")
        bool("ProtectTitle", meta.protectTitle)
        bool("ProtectUserName", meta.protectUserName)
        bool("ProtectPassword", meta.protectPassword)
        bool("ProtectURL", meta.protectUrl)
        bool("ProtectNotes", meta.protectNotes)
        xml.close("MemoryProtection")

        writeCustomIcons()

        bool("RecycleBinEnabled", meta.recycleBinEnabled)
        uuid("RecycleBinUUID", meta.recycleBinUuid)
        date("RecycleBinChanged", meta.recycleBinChanged)
        uuid("EntryTemplatesGroup", meta.entryTemplatesGroup)
        date("EntryTemplatesGroupChanged", meta.entryTemplatesGroupChanged)
        uuid("LastSelectedGroup", meta.lastSelectedGroup)
        uuid("LastTopVisibleGroup", meta.lastTopVisibleGroup)
        text("HistoryMaxItems", meta.historyMaxItems.toString())
        text("HistoryMaxSize", meta.historyMaxSize.toString())
        if (isKdbx4) date("SettingsChanged", meta.settingsChanged)
        if (!isKdbx4) writeBinariesPool()
        writeCustomData(meta.customData)
        meta.unknownXml.forEach(xml::raw)

        xml.close("Meta")
    }

    private fun writeCustomIcons() {
        xml.open("CustomIcons")
        for (icon in db.meta.customIcons) {
            xml.open("Icon")
            uuid("UUID", icon.uuid)
            if (isKdbx41) {
                icon.name?.takeIf { it.isNotEmpty() }?.let { text("Name", it) }
                icon.lastModified?.let { date("LastModificationTime", it) }
            }
            text("Data", base64Encode(icon.data))
            xml.close("Icon")
        }
        xml.close("CustomIcons")
    }

    /** KDBX 3 keeps attachments in Meta, gzip-compressed when the database itself is compressed. */
    private fun writeBinariesPool() {
        xml.open("Binaries")
        val compress = db.params.compression == Kdbx.COMPRESSION_GZIP
        pool.data.forEachIndexed { index, payload ->
            val stored = if (compress && payload.isNotEmpty()) gzip(payload) else payload
            val attributes = mutableListOf("ID" to index.toString())
            if (compress) attributes.add("Compressed" to "True")
            xml.leaf("Binary", base64Encode(stored), attributes)
        }
        xml.close("Binaries")
    }

    private fun writeCustomData(customData: Map<String, String>) {
        if (customData.isEmpty()) return
        xml.open("CustomData")
        for ((key, value) in customData) {
            xml.open("Item")
            text("Key", key)
            text("Value", value)
            xml.close("Item")
        }
        xml.close("CustomData")
    }

    // --- Root ---------------------------------------------------------------------------------

    private fun writeRoot() {
        xml.open("Root")
        writeGroup(db.root)
        xml.open("DeletedObjects")
        for (deleted in db.deletedObjects) {
            xml.open("DeletedObject")
            uuid("UUID", deleted.uuid)
            date("DeletionTime", deleted.deletionTime)
            xml.close("DeletedObject")
        }
        xml.close("DeletedObjects")
        xml.close("Root")
    }

    private fun writeGroup(group: Group) {
        xml.open("Group")
        uuid("UUID", group.uuid)
        text("Name", group.name)
        text("Notes", group.notes)
        if (group.tags.isNotEmpty()) text("Tags", group.tags)
        text("IconID", group.iconId.toString())
        group.customIconUuid?.let { uuid("CustomIconUUID", it) }
        writeTimes(group.times)
        bool("IsExpanded", group.isExpanded)
        text("DefaultAutoTypeSequence", group.defaultAutoTypeSequence.orEmpty())
        triState("EnableAutoType", group.enableAutoType)
        triState("EnableSearching", group.enableSearching)
        uuid("LastTopVisibleEntry", group.lastTopVisibleEntry)
        if (isKdbx4) writeCustomData(group.customData)
        if (isKdbx41) group.previousParentGroup?.let { uuid("PreviousParentGroup", it) }

        for (entry in group.entries) writeEntry(entry, history = false)
        for (child in group.groups) writeGroup(child)
        group.unknownXml.forEach(xml::raw)

        xml.close("Group")
    }

    private fun writeEntry(entry: Entry, history: Boolean) {
        xml.open("Entry")
        uuid("UUID", entry.uuid)
        text("IconID", entry.iconId.toString())
        entry.customIconUuid?.let { uuid("CustomIconUUID", it) }
        text("ForegroundColor", entry.foregroundColor.orEmpty())
        text("BackgroundColor", entry.backgroundColor.orEmpty())
        text("OverrideURL", entry.overrideUrl.orEmpty())
        text("Tags", entry.tags)
        writeTimes(entry.times)

        if (isKdbx41) {
            entry.qualityCheck?.let { bool("QualityCheck", it) }
            entry.previousParentGroup?.let { uuid("PreviousParentGroup", it) }
        }

        for ((key, value) in entry.fields) writeEntryField(key, value)

        for (attachment in entry.attachments) {
            xml.open("Binary")
            text("Key", attachment.name)
            xml.leaf("Value", "", listOf("Ref" to pool.refFor(attachment.ref).toString()))
            xml.close("Binary")
        }

        writeAutoType(entry.autoType)

        if (isKdbx4) writeCustomData(entry.customData)

        if (!history) {
            xml.open("History")
            for (item in entry.history) writeEntry(item, history = true)
            xml.close("History")
        }

        entry.unknownXml.forEach(xml::raw)
        xml.close("Entry")
    }

    private fun writeEntryField(key: String, value: ProtectedValue) {
        val meta = db.meta
        val protect = value.protected ||
            (key == Entry.TITLE && meta.protectTitle) ||
            (key == Entry.USERNAME && meta.protectUserName) ||
            (key == Entry.PASSWORD && meta.protectPassword) ||
            (key == Entry.URL && meta.protectUrl) ||
            (key == Entry.NOTES && meta.protectNotes)

        xml.open("String")
        text("Key", key)
        if (protect) {
            // A protected value is secret material: silently dropping characters from it would
            // corrupt the secret without a trace, so an unstorable character is a hard error.
            val cipherText = randomStream.process(requireXmlStorable(value.value).toByteArray(Charsets.UTF_8))
            xml.leaf("Value", base64Encode(cipherText), listOf("Protected" to "True"))
        } else {
            text("Value", value.value)
        }
        xml.close("String")
    }

    /** Always emitted, as every other implementation does; an absent block becomes the defaults. */
    private fun writeAutoType(autoType: AutoType?) {
        val effective = autoType ?: AutoType()
        xml.open("AutoType")
        bool("Enabled", effective.enabled)
        text("DataTransferObfuscation", effective.dataTransferObfuscation.toString())
        text("DefaultSequence", effective.defaultSequence.orEmpty())
        for (association in effective.associations) {
            xml.open("Association")
            text("Window", association.window)
            text("KeystrokeSequence", association.sequence)
            xml.close("Association")
        }
        xml.close("AutoType")
    }

    private fun writeTimes(times: Times) {
        xml.open("Times")
        date("LastModificationTime", times.lastModificationTime)
        date("CreationTime", times.creationTime)
        date("LastAccessTime", times.lastAccessTime)
        // The format cannot express "no expiry date", and every other implementation writes one
        // unconditionally. Falling back to the creation time keeps the element present; Expires
        // is what actually decides whether the date means anything.
        date("ExpiryTime", times.expiryTime ?: times.creationTime)
        bool("Expires", times.expires)
        text("UsageCount", times.usageCount.toString())
        date("LocationChanged", times.locationChanged)
        xml.close("Times")
    }

    // --- Leaf writers -------------------------------------------------------------------------

    private fun text(name: String, value: String) = xml.leaf(name, sanitizeXmlText(value))

    private fun bool(name: String, value: Boolean) = xml.leaf(name, if (value) "True" else "False")

    private fun triState(name: String, value: Boolean?) =
        xml.leaf(name, if (value == null) "null" else value.toString())

    private fun date(name: String, unixSeconds: Long?) {
        if (unixSeconds == null) return
        xml.leaf(name, KdbxWriter.formatDate(unixSeconds, isKdbx4))
    }

    private fun uuid(name: String, value: UUID?) =
        xml.leaf(name, base64Encode((value ?: NULL_UUID).toRfc4122()))
}

/**
 * Removes codepoints XML 1.0 cannot represent. Passwords and notes pasted from other tools
 * regularly contain stray control characters, and an unescapable byte would make the whole file
 * unreadable rather than just that one field.
 */
private fun sanitizeXmlText(input: String): String {
    var needsStripping = false
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) {
            i += 2
            continue
        }
        if (isInvalidXmlChar(ch)) {
            needsStripping = true
            break
        }
        i++
    }
    if (!needsStripping) return input

    val sb = StringBuilder(input.length)
    var j = 0
    while (j < input.length) {
        val ch = input[j]
        if (ch.isHighSurrogate() && j + 1 < input.length && input[j + 1].isLowSurrogate()) {
            sb.append(ch).append(input[j + 1])
            j += 2
            continue
        }
        if (!isInvalidXmlChar(ch)) sb.append(ch)
        j++
    }
    return sb.toString()
}

/**
 * Verifies a protected value carries only codepoints XML 1.0 can represent. Unlike
 * [sanitizeXmlText] it never mutates: a password with a stray control character must fail
 * loudly rather than be written back with characters silently removed.
 */
private fun requireXmlStorable(input: String): String {
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) {
            i += 2
            continue
        }
        if (isInvalidXmlChar(ch)) {
            throw KdbxException(
                "A protected value contains a control character that cannot be stored in the database XML"
            )
        }
        i++
    }
    return input
}

private fun isInvalidXmlChar(ch: Char): Boolean {
    val code = ch.code
    return (code < 0x20 && code != 0x09 && code != 0x0A && code != 0x0D) ||
        code in 0x7F..0x84 ||
        code in 0x86..0x9F ||
        code > 0xFFFD ||
        ch.isHighSurrogate() ||
        ch.isLowSurrogate()
}
