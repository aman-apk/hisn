package org.hisn.app.kdbx

import java.util.UUID

/**
 * Two-way synchronisation of two databases, mirroring the desktop Merger's semantics so a
 * database merged on the phone and one merged on the laptop converge to the same result.
 *
 * Rules (same as desktop Synchronize mode):
 *  - objects are matched by UUID anywhere in the tree, never by name or position
 *  - on conflict the newer lastModificationTime wins; the loser is kept in history
 *  - deletions propagate via tombstones, and a deletion only applies if the tombstone is
 *    newer than the surviving object's last modification
 *  - timestamps compare at second precision, because that is all KDBX stores
 */
class Merger(private val source: KdbxDatabase, private val target: KdbxDatabase) {

    data class Change(val kind: Kind, val description: String) {
        enum class Kind { EntryAdded, EntryUpdated, EntryDeleted, GroupAdded, GroupUpdated, GroupDeleted, Moved }
    }

    private val changes = mutableListOf<Change>()

    fun merge(): List<Change> {
        changes.clear()
        target.root.relink()
        source.root.relink()

        mergeGroupInto(source.root, target.root)
        mergeDeletions()
        mergeMeta()

        target.root.relink()
        return changes.toList()
    }

    private fun mergeGroupInto(sourceGroup: Group, targetGroup: Group) {
        for (sourceEntry in sourceGroup.entries) {
            val existing = target.root.findEntry(sourceEntry.uuid)
            if (existing == null) {
                if (isDeletedNewerThan(sourceEntry.uuid, sourceEntry.times.lastModificationTime, target)) continue
                val clone = sourceEntry.deepCopy()
                val destination = target.root.findGroup(sourceGroup.uuid) ?: targetGroup
                destination.addEntry(clone)
                changes += Change(Change.Kind.EntryAdded, clone.title)
            } else {
                resolveEntryConflict(sourceEntry, existing)
                relocateIfNeeded(sourceEntry, existing)
            }
        }

        for (sourceChild in sourceGroup.groups) {
            val existing = target.root.findGroup(sourceChild.uuid)
            val targetChild: Group
            if (existing == null) {
                if (isDeletedNewerThan(sourceChild.uuid, sourceChild.times.lastModificationTime, target)) continue
                targetChild = Group(
                    uuid = sourceChild.uuid,
                    name = sourceChild.name,
                    notes = sourceChild.notes,
                    iconId = sourceChild.iconId,
                    customIconUuid = sourceChild.customIconUuid,
                    times = sourceChild.times.copyOf(),
                    isExpanded = sourceChild.isExpanded,
                    defaultAutoTypeSequence = sourceChild.defaultAutoTypeSequence,
                    enableAutoType = sourceChild.enableAutoType,
                    enableSearching = sourceChild.enableSearching,
                    customData = LinkedHashMap(sourceChild.customData),
                    tags = sourceChild.tags,
                )
                val destination = target.root.findGroup(sourceGroup.uuid) ?: targetGroup
                destination.addGroup(targetChild)
                changes += Change(Change.Kind.GroupAdded, targetChild.name)
            } else {
                targetChild = existing
                if (sourceChild.times.lastModificationTime > existing.times.lastModificationTime) {
                    existing.name = sourceChild.name
                    existing.notes = sourceChild.notes
                    existing.iconId = sourceChild.iconId
                    existing.customIconUuid = sourceChild.customIconUuid
                    existing.times = sourceChild.times.copyOf()
                    existing.enableAutoType = sourceChild.enableAutoType
                    existing.enableSearching = sourceChild.enableSearching
                    existing.tags = sourceChild.tags
                    sourceChild.customData.forEach { (k, v) -> existing.customData[k] = v }
                    changes += Change(Change.Kind.GroupUpdated, existing.name)
                }
                // A group moved on the source side moves here too, if that move is the newer fact.
                val sourceParentUuid = sourceChild.parent?.uuid
                val targetParentUuid = existing.parent?.uuid
                if (sourceParentUuid != null && sourceParentUuid != targetParentUuid &&
                    sourceChild.times.locationChanged > existing.times.locationChanged
                ) {
                    target.root.findGroup(sourceParentUuid)?.let { newParent ->
                        if (!isAncestor(existing, newParent)) {
                            existing.parent?.groups?.remove(existing)
                            newParent.addGroup(existing)
                            existing.times.locationChanged = sourceChild.times.locationChanged
                            changes += Change(Change.Kind.Moved, existing.name)
                        }
                    }
                }
            }
            mergeGroupInto(sourceChild, targetChild)
        }
    }

    /** Guard against reparenting a group beneath itself, which would detach the subtree. */
    private fun isAncestor(candidate: Group, node: Group): Boolean {
        var g: Group? = node
        while (g != null) {
            if (g === candidate) return true
            g = g.parent
        }
        return false
    }

    private fun relocateIfNeeded(sourceEntry: Entry, targetEntry: Entry) {
        val sourceParent = sourceEntry.parent?.uuid ?: return
        val targetParent = targetEntry.parent?.uuid
        if (sourceParent == targetParent) return
        if (sourceEntry.times.locationChanged <= targetEntry.times.locationChanged) return
        val newParent = target.root.findGroup(sourceParent) ?: return
        targetEntry.parent?.removeEntry(targetEntry)
        newParent.addEntry(targetEntry)
        targetEntry.times.locationChanged = sourceEntry.times.locationChanged
        changes += Change(Change.Kind.Moved, targetEntry.title)
    }

    private fun resolveEntryConflict(sourceEntry: Entry, targetEntry: Entry) {
        val sourceTime = sourceEntry.times.lastModificationTime
        val targetTime = targetEntry.times.lastModificationTime
        if (sourceTime == targetTime && sourceEntry.contentEquals(targetEntry)) {
            mergeHistory(sourceEntry, targetEntry)
            return
        }
        if (sourceTime > targetTime) {
            // The source is newer: keep our current state as history, then take the source's fields.
            val ours = targetEntry.deepCopy().also { it.history.clear() }
            applyFields(from = sourceEntry, to = targetEntry)
            mergeHistory(sourceEntry, targetEntry)
            if (targetEntry.history.none { it.times.lastModificationTime == ours.times.lastModificationTime }) {
                targetEntry.history.add(ours)
            }
            targetEntry.history.sortBy { it.times.lastModificationTime }
            trimHistory(targetEntry)
            changes += Change(Change.Kind.EntryUpdated, targetEntry.title)
        } else {
            // We are newer: the source's version becomes a history record so nothing is lost.
            mergeHistory(sourceEntry, targetEntry)
            val theirs = sourceEntry.deepCopy().also { it.history.clear() }
            if (targetEntry.history.none { it.times.lastModificationTime == theirs.times.lastModificationTime }) {
                targetEntry.history.add(theirs)
                targetEntry.history.sortBy { it.times.lastModificationTime }
                trimHistory(targetEntry)
            }
        }
    }

    private fun applyFields(from: Entry, to: Entry) {
        to.fields.clear()
        to.fields.putAll(from.fields)
        to.iconId = from.iconId
        to.customIconUuid = from.customIconUuid
        to.foregroundColor = from.foregroundColor
        to.backgroundColor = from.backgroundColor
        to.overrideUrl = from.overrideUrl
        to.tags = from.tags
        to.times = from.times.copyOf()
        to.attachments.clear()
        to.attachments.addAll(from.attachments)
        to.customData.clear()
        to.customData.putAll(from.customData)
        to.autoType = from.autoType
        to.qualityCheck = from.qualityCheck
    }

    /** Union of both history lists keyed on modification time, oldest first. */
    private fun mergeHistory(sourceEntry: Entry, targetEntry: Entry) {
        val seen = targetEntry.history.mapTo(mutableSetOf()) { it.times.lastModificationTime }
        for (item in sourceEntry.history) {
            if (seen.add(item.times.lastModificationTime)) {
                targetEntry.history.add(item.deepCopy())
            }
        }
        targetEntry.history.sortBy { it.times.lastModificationTime }
        trimHistory(targetEntry)
    }

    private fun trimHistory(entry: Entry) {
        val max = target.meta.historyMaxItems
        if (max >= 0 && entry.history.size > max) {
            val excess = entry.history.size - max
            repeat(excess) { entry.history.removeAt(0) }
        }
    }

    private fun mergeDeletions() {
        val sourceTombstones = source.deletedObjects
        for (tombstone in sourceTombstones) {
            val existingTomb = target.deletedObjects.firstOrNull { it.uuid == tombstone.uuid }
            if (existingTomb == null) {
                target.deletedObjects.add(tombstone)
            } else if (tombstone.deletionTime < existingTomb.deletionTime) {
                // Keep the earliest tombstone, matching desktop behaviour.
                target.deletedObjects.remove(existingTomb)
                target.deletedObjects.add(tombstone)
            }

            val entry = target.root.findEntry(tombstone.uuid)
            if (entry != null) {
                if (entry.times.lastModificationTime <= tombstone.deletionTime) {
                    entry.parent?.removeEntry(entry)
                    changes += Change(Change.Kind.EntryDeleted, entry.title)
                } else {
                    // The object was edited after it was deleted elsewhere: the edit wins,
                    // so drop the tombstone rather than losing the newer data.
                    target.deletedObjects.removeAll { it.uuid == tombstone.uuid }
                }
                continue
            }
            val group = target.root.findGroup(tombstone.uuid)
            if (group != null && group !== target.root) {
                val empty = group.entries.isEmpty() && group.groups.isEmpty()
                if (empty && group.times.lastModificationTime <= tombstone.deletionTime) {
                    group.parent?.groups?.remove(group)
                    changes += Change(Change.Kind.GroupDeleted, group.name)
                } else if (!empty) {
                    target.deletedObjects.removeAll { it.uuid == tombstone.uuid }
                }
            }
        }
    }

    private fun isDeletedNewerThan(uuid: UUID, modified: Long, db: KdbxDatabase): Boolean =
        db.deletedObjects.firstOrNull { it.uuid == uuid }?.let { it.deletionTime >= modified } ?: false

    private fun mergeMeta() {
        if ((source.meta.databaseNameChanged ?: 0) > (target.meta.databaseNameChanged ?: 0)) {
            target.meta.databaseName = source.meta.databaseName
            target.meta.databaseNameChanged = source.meta.databaseNameChanged
        }
        if ((source.meta.databaseDescriptionChanged ?: 0) > (target.meta.databaseDescriptionChanged ?: 0)) {
            target.meta.databaseDescription = source.meta.databaseDescription
            target.meta.databaseDescriptionChanged = source.meta.databaseDescriptionChanged
        }
        for (icon in source.meta.customIcons) {
            if (target.meta.customIcons.none { it.uuid == icon.uuid }) target.meta.customIcons.add(icon)
        }
        source.meta.customData.forEach { (k, v) -> target.meta.customData.putIfAbsent(k, v) }
    }
}
