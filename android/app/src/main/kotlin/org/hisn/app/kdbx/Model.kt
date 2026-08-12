package org.hisn.app.kdbx

import java.util.UUID

/**
 * In-memory model of a KDBX database. Mirrors the desktop core model closely enough that
 * entries survive a round trip through either implementation without losing fields.
 *
 * Unknown-but-present XML is preserved verbatim per node in [Entry.unknownXml] / [Group.unknownXml]
 * so writing a database read from the desktop app never silently drops data we do not model.
 */

/** Value of an entry field. Protected values are the ones the format encrypts in the inner stream. */
data class ProtectedValue(val value: String, val protected: Boolean = false)

data class Times(
    var lastModificationTime: Long = nowSeconds(),
    var creationTime: Long = nowSeconds(),
    var lastAccessTime: Long = nowSeconds(),
    var expiryTime: Long? = null,
    var expires: Boolean = false,
    var usageCount: Int = 0,
    var locationChanged: Long = nowSeconds(),
) {
    fun copyOf() = copy()

    companion object {
        /** KDBX stores second precision; merge comparisons depend on this truncation. */
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
    }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

data class Attachment(val name: String, val ref: Int)

data class AutoTypeAssociation(val window: String, val sequence: String)

data class AutoType(
    var enabled: Boolean = true,
    var dataTransferObfuscation: Int = 0,
    var defaultSequence: String? = null,
    val associations: MutableList<AutoTypeAssociation> = mutableListOf(),
)

class Entry(
    var uuid: UUID = UUID.randomUUID(),
    var iconId: Int = 0,
    var customIconUuid: UUID? = null,
    var foregroundColor: String? = null,
    var backgroundColor: String? = null,
    var overrideUrl: String? = null,
    var tags: String = "",
    var times: Times = Times(),
    val fields: MutableMap<String, ProtectedValue> = linkedMapOf(),
    val attachments: MutableList<Attachment> = mutableListOf(),
    var autoType: AutoType? = null,
    val customData: MutableMap<String, String> = linkedMapOf(),
    /** Previous versions, oldest first, exactly as KDBX stores History/Entry. */
    val history: MutableList<Entry> = mutableListOf(),
    var previousParentGroup: UUID? = null,
    var qualityCheck: Boolean? = null,
    var unknownXml: MutableList<String> = mutableListOf(),
) {
    var parent: Group? = null

    val title: String get() = fields[TITLE]?.value.orEmpty()
    val username: String get() = fields[USERNAME]?.value.orEmpty()
    val password: String get() = fields[PASSWORD]?.value.orEmpty()
    val url: String get() = fields[URL]?.value.orEmpty()
    val notes: String get() = fields[NOTES]?.value.orEmpty()

    /** The otp/TOTP seed as stored by KeePassXC ("otp" attribute) or KeeOtp ("TOTP Seed"). */
    val totpField: String?
        get() = fields[OTP]?.value?.takeIf { it.isNotBlank() }
            ?: fields["TOTP Seed"]?.value?.takeIf { it.isNotBlank() }

    val hasTotp: Boolean get() = totpField != null

    fun set(key: String, value: String, protected: Boolean = false) {
        fields[key] = ProtectedValue(value, protected)
    }

    /** Custom (non-standard) attribute keys, in insertion order. */
    fun customFieldKeys(): List<String> = fields.keys.filter { it !in DEFAULT_FIELDS }

    fun deepCopy(): Entry {
        val e = Entry(
            uuid = uuid,
            iconId = iconId,
            customIconUuid = customIconUuid,
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            tags = tags,
            times = times.copyOf(),
            fields = LinkedHashMap(fields),
            attachments = attachments.toMutableList(),
            autoType = autoType?.let { at ->
                AutoType(at.enabled, at.dataTransferObfuscation, at.defaultSequence, at.associations.toMutableList())
            },
            customData = LinkedHashMap(customData),
            history = history.mapTo(mutableListOf()) { it.deepCopy() },
            previousParentGroup = previousParentGroup,
            qualityCheck = qualityCheck,
            unknownXml = unknownXml.toMutableList(),
        )
        e.parent = parent
        return e
    }

    /** True when every user-visible field matches; used to skip no-op history entries. */
    fun contentEquals(other: Entry): Boolean =
        fields == other.fields &&
            iconId == other.iconId &&
            customIconUuid == other.customIconUuid &&
            tags == other.tags &&
            attachments == other.attachments &&
            customData == other.customData &&
            times.expires == other.times.expires &&
            times.expiryTime == other.times.expiryTime

    companion object {
        const val TITLE = "Title"
        const val USERNAME = "UserName"
        const val PASSWORD = "Password"
        const val URL = "URL"
        const val NOTES = "Notes"
        const val OTP = "otp"
        val DEFAULT_FIELDS = setOf(TITLE, USERNAME, PASSWORD, URL, NOTES)
    }
}

class Group(
    var uuid: UUID = UUID.randomUUID(),
    var name: String = "",
    var notes: String = "",
    var iconId: Int = 48,
    var customIconUuid: UUID? = null,
    var times: Times = Times(),
    var isExpanded: Boolean = true,
    var defaultAutoTypeSequence: String? = null,
    var enableAutoType: Boolean? = null,
    var enableSearching: Boolean? = null,
    var lastTopVisibleEntry: UUID? = null,
    val groups: MutableList<Group> = mutableListOf(),
    val entries: MutableList<Entry> = mutableListOf(),
    val customData: MutableMap<String, String> = linkedMapOf(),
    var previousParentGroup: UUID? = null,
    var tags: String = "",
    var unknownXml: MutableList<String> = mutableListOf(),
) {
    var parent: Group? = null

    fun addEntry(entry: Entry) {
        entry.parent = this
        entries.add(entry)
    }

    fun addGroup(group: Group) {
        group.parent = this
        groups.add(group)
    }

    fun removeEntry(entry: Entry) {
        if (entries.remove(entry)) entry.parent = null
    }

    /** All entries in this subtree, this group first, depth-first. */
    fun entriesRecursive(includeHistory: Boolean = false): List<Entry> {
        val out = mutableListOf<Entry>()
        fun walk(g: Group) {
            out.addAll(g.entries)
            if (includeHistory) g.entries.forEach { out.addAll(it.history) }
            g.groups.forEach(::walk)
        }
        walk(this)
        return out
    }

    fun groupsRecursive(includeSelf: Boolean = true): List<Group> {
        val out = mutableListOf<Group>()
        fun walk(g: Group) {
            out.add(g)
            g.groups.forEach(::walk)
        }
        if (includeSelf) walk(this) else groups.forEach(::walk)
        return out
    }

    fun findEntry(uuid: UUID): Entry? = entriesRecursive().firstOrNull { it.uuid == uuid }

    fun findGroup(uuid: UUID): Group? = groupsRecursive().firstOrNull { it.uuid == uuid }

    /** Path from the root, e.g. "Root/Banking". */
    fun path(): String {
        val parts = mutableListOf<String>()
        var g: Group? = this
        while (g != null) {
            parts.add(0, g.name)
            g = g.parent
        }
        return parts.joinToString("/")
    }

    fun relink() {
        entries.forEach { it.parent = this }
        groups.forEach {
            it.parent = this
            it.relink()
        }
    }
}

data class DeletedObject(val uuid: UUID, val deletionTime: Long)

data class CustomIcon(val uuid: UUID, val data: ByteArray, val name: String? = null, val lastModified: Long? = null) {
    override fun equals(other: Any?) = other is CustomIcon && other.uuid == uuid
    override fun hashCode() = uuid.hashCode()
}

class Meta(
    var generator: String = "Hisn",
    var databaseName: String = "",
    var databaseNameChanged: Long? = null,
    var databaseDescription: String = "",
    var databaseDescriptionChanged: Long? = null,
    var defaultUserName: String = "",
    var defaultUserNameChanged: Long? = null,
    var maintenanceHistoryDays: Int = 365,
    var color: String = "",
    var masterKeyChanged: Long? = null,
    var masterKeyChangeRec: Long = -1,
    var masterKeyChangeForce: Long = -1,
    var recycleBinEnabled: Boolean = true,
    var recycleBinUuid: UUID? = null,
    var recycleBinChanged: Long? = null,
    var entryTemplatesGroup: UUID? = null,
    var entryTemplatesGroupChanged: Long? = null,
    var historyMaxItems: Int = 10,
    var historyMaxSize: Long = 6 * 1024 * 1024,
    var lastSelectedGroup: UUID? = null,
    var lastTopVisibleGroup: UUID? = null,
    var settingsChanged: Long? = null,
    var protectTitle: Boolean = false,
    var protectUserName: Boolean = false,
    var protectPassword: Boolean = true,
    var protectUrl: Boolean = false,
    var protectNotes: Boolean = false,
    val customIcons: MutableList<CustomIcon> = mutableListOf(),
    val customData: MutableMap<String, String> = linkedMapOf(),
    var unknownXml: MutableList<String> = mutableListOf(),
)

/**
 * A full database: metadata, the group tree, deletion tombstones and the binary pool
 * (attachment payloads, indexed by the Ref ids entries carry).
 */
class KdbxDatabase(
    var meta: Meta = Meta(),
    var root: Group = Group(name = "Root"),
    val deletedObjects: MutableList<DeletedObject> = mutableListOf(),
    val binaries: MutableList<ByteArray> = mutableListOf(),
    /** Header parameters preserved so re-encryption keeps the user's chosen KDF/cipher. */
    var params: KdbxParams = KdbxParams(),
) {
    fun allEntries(): List<Entry> = root.entriesRecursive()

    fun findEntry(uuid: UUID): Entry? = root.findEntry(uuid)

    fun findGroup(uuid: UUID): Group? = root.findGroup(uuid)

    val recycleBin: Group?
        get() = meta.recycleBinUuid?.let { root.findGroup(it) }

    fun isInRecycleBin(group: Group?): Boolean {
        val binUuid = meta.recycleBinUuid ?: return false
        var g = group
        while (g != null) {
            if (g.uuid == binUuid) return true
            g = g.parent
        }
        return false
    }

    fun isInRecycleBin(entry: Entry): Boolean = isInRecycleBin(entry.parent)

    /** Entries excluding anything inside the recycle bin — what the UI lists by default. */
    fun visibleEntries(): List<Entry> = allEntries().filterNot { isInRecycleBin(it) }

    fun addDeletedObject(uuid: UUID) {
        deletedObjects.removeAll { it.uuid == uuid }
        deletedObjects.add(DeletedObject(uuid, Times.nowSeconds()))
    }

    fun binaryFor(ref: Int): ByteArray? = binaries.getOrNull(ref)

    /** Adds a payload to the pool, reusing an identical one, and returns its ref index. */
    fun addBinary(data: ByteArray): Int {
        val existing = binaries.indexOfFirst { it.contentEquals(data) }
        if (existing >= 0) return existing
        binaries.add(data)
        return binaries.size - 1
    }
}
