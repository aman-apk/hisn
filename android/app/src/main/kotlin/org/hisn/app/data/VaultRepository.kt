package org.hisn.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.hisn.app.kdbx.CompositeKey
import org.hisn.app.kdbx.Entry
import org.hisn.app.kdbx.Group
import org.hisn.app.kdbx.Kdbx
import org.hisn.app.kdbx.KdbxDatabase
import org.hisn.app.kdbx.KdbxException
import org.hisn.app.kdbx.KdbxReader
import org.hisn.app.kdbx.KdbxWriter
import org.hisn.app.kdbx.Merger
import org.hisn.app.kdbx.Meta
import org.hisn.app.kdbx.Times
import org.hisn.app.support.SupportReminder
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

enum class VaultState { Locked, Unlocked, NoDatabase }

data class VaultStatus(
    val state: VaultState,
    val databaseName: String?,
    val filePath: String?,
    val hasBiometric: Boolean,
    val dirty: Boolean,
)

/**
 * Why an operation failed, so the UI can pick an Arabic message instead of showing a raw
 * exception. The English [Throwable.message] stays useful for logs and bug reports.
 */
enum class VaultError {
    NoDatabase,
    Locked,
    InvalidCredentials,
    CorruptFile,
    NotFound,
    /** The request itself does not make sense, e.g. deleting the root group. */
    Invalid,
    Io,
    SaveFailed,
    MergeFailed,
    Biometric,
}

class VaultException(val error: VaultError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Owns the one database this app manages: its file on disk, the in-memory model, and the key
 * material that ties them together.
 *
 * Storage layout under `filesDir/vault/`:
 *   hisn.kdbx       the database — the only authoritative copy
 *   hisn.kdbx.bak   the previous good version, replaced on every successful save
 *   hisn.kdbx.tmp   scratch file for the write-then-rename dance; never read
 *   keyfile.bin     a copy of the key file, if the vault uses one
 *   vault.json      display name and import time; nothing secret
 *
 * Every mutation persists immediately: a crash between edit and save would lose data that the
 * user believes is stored. [VaultStatus.dirty] therefore only stays true when a save actually
 * failed, and the UI can surface a retry.
 */
class VaultRepository(context: Context) {

    private val appContext = context.applicationContext
    private val vaultDir = File(appContext.filesDir, "vault")
    private val dbFile = File(vaultDir, DB_NAME)
    private val bakFile = File(vaultDir, "$DB_NAME.bak")
    private val tmpFile = File(vaultDir, "$DB_NAME.tmp")
    private val keyFileCopy = File(vaultDir, "keyfile.bin")
    private val metaFile = File(vaultDir, "vault.json")

    /** Wrapped-password storage for biometric unlock; the UI needs it to build the Cipher. */
    val secureStore = SecureStore(appContext)

    /** Shared settings store — auto-lock timeout, clipboard timeout, theme. */
    val prefs = Prefs(appContext)

    private val mutex = Mutex()

    private val _database = MutableStateFlow<KdbxDatabase?>(null)
    val database: StateFlow<KdbxDatabase?> = _database.asStateFlow()

    private val _status = MutableStateFlow(
        VaultStatus(
            state = if (dbFile.isFile) VaultState.Locked else VaultState.NoDatabase,
            databaseName = readStoredName(),
            filePath = dbFile.takeIf { it.isFile }?.absolutePath,
            hasBiometric = secureStore.isEnrolled,
            dirty = false,
        )
    )
    val status: StateFlow<VaultStatus> = _status.asStateFlow()

    private var compositeKey: CompositeKey? = null

    /**
     * The last persisted state of every entry, keyed by UUID. [updateEntry] needs the previous
     * version to build a history record, and it cannot rely on the caller having kept one:
     * screens routinely edit the live object in place.
     */
    private val lastSaved = HashMap<UUID, Entry>()

    private var lastActivityElapsed = SystemClock.elapsedRealtime()

    /**
     * Repository-owned scope for the auto-lock watcher. It must not be a viewModelScope: the
     * Activity — and every ViewModel with it — can be destroyed while the app sits in the
     * background, and the vault must still lock when its deadline passes. The repository is a
     * process-scoped singleton, so this scope lives exactly as long as the decrypted state does.
     */
    private val lockScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The armed background-lock watcher; see [armBackgroundLock]. */
    private var relockJob: Job? = null

    /**
     * Mirrors [HisnSettings.searchIncludesRecycleBin]. [search] is synchronous, so the collector
     * that watches the settings flow pushes the value here instead of reading DataStore per query.
     */
    @Volatile
    var searchIncludesRecycleBin: Boolean = false

    val biometricEnabled: Boolean get() = secureStore.isEnrolled

    // ------------------------------------------------------------------ import

    /**
     * Copies a .kdbx chosen by the user into app-private storage. The file is validated as a
     * KDBX container first so an obviously wrong pick fails immediately rather than at unlock.
     * Any previous vault is kept as the .bak, and biometric enrolment is dropped because the
     * new database almost certainly has a different password.
     */
    suspend fun importDatabase(uri: Uri): Result<Unit> = io {
        mutex.withLock {
            val bytes = try {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw VaultException(VaultError.Io, "The selected file could not be opened")
            } catch (e: SecurityException) {
                throw VaultException(VaultError.Io, "Permission to read the selected file was denied", e)
            } catch (e: IOException) {
                throw VaultException(VaultError.Io, "The selected file could not be read: ${e.message}", e)
            }
            validateContainer(bytes)

            vaultDir.mkdirs()
            // The import happens on the *locked* screen, without proof the user can open the
            // current vault, so that vault is never destroyed by a replacement: it is kept
            // aside under a timestamped name the .bak rotation never touches.
            if (dbFile.isFile) {
                copyWithSync(dbFile, File(vaultDir, "$DB_NAME.replaced-${System.currentTimeMillis() / 1000L}"))
            }
            writeAtomically(bytes)

            lockInternal()
            secureStore.clear()
            prefs.setBiometricUnlock(false)
            keyFileCopy.delete()
            writeStoredMeta(displayNameOf(uri) ?: dbFile.name, hasKeyFile = false)

            _status.value = VaultStatus(
                state = VaultState.Locked,
                databaseName = readStoredName(),
                filePath = dbFile.absolutePath,
                hasBiometric = false,
                dirty = false,
            )
        }
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a brand-new database on the device, for the user who has no .kdbx to import.
     *
     * The file is KDBX4 with the writer's default parameters ([org.hisn.app.kdbx.KdbxParams]:
     * 4.1, AES-256, Argon2d) and a root group named like the vault, so KeePassXC opens it as
     * if the desktop had created it. Written with the same scratch-then-rename dance as every
     * other save; any previous database is kept as the .bak, exactly like [importDatabase].
     *
     * The vault is left open on success — asking the user to retype the password they chose
     * seconds ago helps no-one — so the caller can navigate straight to the entry list.
     */
    suspend fun createNew(name: String, password: String): Result<Unit> = io {
        mutex.withLock {
            val displayName = name.trim()
            if (displayName.isEmpty()) {
                throw VaultException(VaultError.Invalid, "The new database needs a name")
            }
            if (password.isEmpty()) {
                throw VaultException(VaultError.InvalidCredentials, "The new database needs a master password")
            }

            val db = KdbxDatabase(
                meta = Meta(databaseName = displayName, databaseNameChanged = Times.nowSeconds()),
                root = Group(name = displayName),
            )
            val key = CompositeKey.build(password)

            val bytes = try {
                KdbxWriter.write(db, key)
            } catch (e: KdbxException) {
                throw VaultException(VaultError.SaveFailed, "The new database could not be encrypted: ${e.message}", e)
            }
            if (bytes.size < MIN_DB_BYTES) {
                throw VaultException(VaultError.SaveFailed, "Refusing to write a suspiciously small database")
            }

            try {
                vaultDir.mkdirs()
                writeAtomically(bytes)
            } catch (e: IOException) {
                throw VaultException(VaultError.Io, "The new database could not be written: ${e.message}", e)
            }

            // A new vault means a new key: whatever biometric enrolment or key-file copy the
            // previous database had cannot apply to this one.
            lockInternal()
            secureStore.clear()
            prefs.setBiometricUnlock(false)
            keyFileCopy.delete()
            writeStoredMeta(displayName, hasKeyFile = false)

            compositeKey = key
            _database.value = db
            rebuildSnapshot(db)
            noteActivity()
            _status.value = VaultStatus(
                state = VaultState.Unlocked,
                databaseName = displayName,
                filePath = dbFile.absolutePath,
                hasBiometric = false,
                dirty = false,
            )
            // Creating a vault leaves it open — that counts as the first successful unlock.
            SupportReminder.noteUnlocked(appContext)
        }
    }

    // ------------------------------------------------------------------ unlock

    /**
     * Opens the vault. A key file supplied here is copied into app-private storage so later
     * unlocks — including biometric ones, which only recover the password — keep working
     * without asking the user to find the file again.
     */
    suspend fun unlock(password: String, keyFileUri: Uri?): Result<Unit> = io {
        mutex.withLock {
            if (!dbFile.isFile) throw VaultException(VaultError.NoDatabase, "No database has been imported yet")

            val suppliedKeyFile = keyFileUri?.let { readKeyFile(it) }
            val keyFileBytes = suppliedKeyFile ?: keyFileCopy.takeIf { it.isFile }?.readBytes()
            if (password.isEmpty() && keyFileBytes == null) {
                throw VaultException(VaultError.InvalidCredentials, "Enter a password or choose a key file")
            }

            val key = CompositeKey.build(password.takeIf { it.isNotEmpty() }, keyFileBytes)
            val db = openWith(dbFile.readBytes(), key)

            compositeKey = key
            _database.value = db
            rebuildSnapshot(db)
            if (suppliedKeyFile != null) {
                keyFileCopy.writeBytes(suppliedKeyFile)
                writeStoredMeta(readStoredName() ?: dbFile.name, hasKeyFile = true)
            }
            noteActivity()
            _status.value = _status.value.copy(
                state = VaultState.Unlocked,
                databaseName = db.meta.databaseName.ifBlank { readStoredName() ?: dbFile.name },
                filePath = dbFile.absolutePath,
                hasBiometric = secureStore.isEnrolled,
                dirty = false,
            )
            // The support reminder never posts before the vault has been opened once.
            SupportReminder.noteUnlocked(appContext)
        }
    }

    /**
     * Unlocks with the master password recovered from the Keystore.
     *
     * @param cipherResult the plaintext produced by `SecureStore.unwrap(cipher)` after
     *        BiometricPrompt authorised the Cipher. The array is zeroed before returning.
     */
    suspend fun unlockWithBiometric(cipherResult: ByteArray): Result<Unit> {
        val password = try {
            String(cipherResult, Charsets.UTF_8)
        } finally {
            SecureStore.wipe(cipherResult)
        }
        if (password.isEmpty()) {
            return Result.failure(
                VaultException(VaultError.Biometric, "The stored master password was empty; enable biometric unlock again")
            )
        }
        return unlock(password, null)
    }

    /**
     * Drops the key material and the decrypted model.
     *
     * Kotlin strings cannot be zeroed, so passwords that passed through [String] live until the
     * garbage collector reclaims them; what we can do — and do here — is release every reference
     * so nothing keeps them alive, and zero the arrays we own.
     */
    fun lock() {
        lockInternal()
        _status.value = _status.value.copy(
            state = if (dbFile.isFile) VaultState.Locked else VaultState.NoDatabase,
            hasBiometric = secureStore.isEnrolled,
            dirty = false,
        )
    }

    private fun lockInternal() {
        compositeKey = null
        _database.value = null
        lastSaved.clear()
    }

    // -------------------------------------------------------------------- save

    /**
     * Encrypts the model and replaces the database file atomically: write scratch file, fsync,
     * copy the current database to .bak, then rename. A power cut can leave a stale .kdbx or a
     * stale .tmp, never a truncated .kdbx.
     */
    suspend fun save(): Result<Unit> = io { mutex.withLock { saveLocked() } }

    private fun saveLocked() {
        val key = compositeKey ?: throw VaultException(VaultError.Locked, "The vault is locked")
        val db = _database.value ?: throw VaultException(VaultError.Locked, "The vault is locked")

        val bytes = try {
            KdbxWriter.write(db, key)
        } catch (e: KdbxException) {
            markDirty()
            throw VaultException(VaultError.SaveFailed, "The database could not be encrypted: ${e.message}", e)
        }
        if (bytes.size < MIN_DB_BYTES) {
            markDirty()
            throw VaultException(VaultError.SaveFailed, "Refusing to write a suspiciously small database")
        }

        try {
            vaultDir.mkdirs()
            writeAtomically(bytes)
        } catch (e: IOException) {
            markDirty()
            throw VaultException(VaultError.SaveFailed, "The database could not be written: ${e.message}", e)
        }

        rebuildSnapshot(db)
        _status.value = _status.value.copy(
            databaseName = db.meta.databaseName.ifBlank { readStoredName() ?: dbFile.name },
            filePath = dbFile.absolutePath,
            dirty = false,
        )
    }

    /** Writes [bytes] to the scratch file, keeps the current database as .bak, then renames. */
    private fun writeAtomically(bytes: ByteArray) {
        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (dbFile.isFile) copyWithSync(dbFile, bakFile)
        try {
            Files.move(
                tmpFile.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (!tmpFile.renameTo(dbFile)) {
                throw IOException("Could not replace ${dbFile.name}")
            }
        }
        // The rename itself lives in the directory, not the file: without a directory fsync a
        // power cut can roll the directory entry back to the old inode after the data fsync.
        fsyncDirectory(vaultDir)
    }

    /** Copies [source] over [dest] and fsyncs the copy — a backup that may be lost is no backup. */
    private fun copyWithSync(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { out ->
                input.copyTo(out)
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun fsyncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Some filesystems refuse to fsync a directory; the data fsyncs above still hold.
        }
    }

    // ---------------------------------------------------------------- mutations

    /** Adds [entry] to [groupUuid] (the root group when null) and saves. */
    suspend fun addEntry(groupUuid: UUID?, entry: Entry): Result<Unit> = io {
        mutex.withLock {
            val db = requireDatabase()
            if (db.findEntry(entry.uuid) != null) entry.uuid = UUID.randomUUID()
            val group = groupUuid?.let { db.findGroup(it) }
                ?: db.root
            val now = Times.nowSeconds()
            entry.times.creationTime = now
            entry.times.lastModificationTime = now
            entry.times.lastAccessTime = now
            entry.times.locationChanged = now
            group.addEntry(entry)
            noteActivity()
            saveLocked()
        }
    }

    /**
     * Applies [entry] over the stored version of the same UUID, keeping the previous version in
     * `history` exactly as KDBX expects. Works whether the caller edited a copy or the live
     * object, because the previous state comes from the repository's own snapshot.
     */
    suspend fun updateEntry(entry: Entry): Result<Unit> = io {
        mutex.withLock {
            val db = requireDatabase()
            val stored = db.findEntry(entry.uuid)
                ?: throw VaultException(VaultError.NotFound, "That entry is no longer in the database")
            val previous = lastSaved[entry.uuid] ?: stored.deepCopy()

            if (!previous.contentEquals(entry)) {
                val snapshot = previous.deepCopy().also { it.history.clear() }
                if (stored !== entry) copyInto(entry, stored)
                stored.history.add(snapshot)
                stored.history.sortBy { it.times.lastModificationTime }
                trimHistory(db, stored)
                stored.times.lastModificationTime = Times.nowSeconds()
            } else if (stored !== entry) {
                copyInto(entry, stored)
            }
            stored.times.lastAccessTime = Times.nowSeconds()
            noteActivity()
            saveLocked()
        }
    }

    /**
     * Moves the entry to the recycle bin, or removes it and records a tombstone when the bin is
     * disabled or the entry is already in it. Tombstones are what make deletions survive a merge.
     */
    suspend fun deleteEntry(uuid: UUID): Result<Unit> = io {
        mutex.withLock {
            val db = requireDatabase()
            val entry = db.findEntry(uuid)
                ?: throw VaultException(VaultError.NotFound, "That entry is no longer in the database")

            if (db.meta.recycleBinEnabled && !db.isInRecycleBin(entry)) {
                val bin = recycleBin(db)
                entry.previousParentGroup = entry.parent?.uuid
                entry.parent?.removeEntry(entry)
                bin.addEntry(entry)
                entry.times.locationChanged = Times.nowSeconds()
            } else {
                entry.parent?.removeEntry(entry)
                lastSaved.remove(uuid)
                db.addDeletedObject(uuid)
            }
            noteActivity()
            saveLocked()
        }
    }

    /** Creates a group under [parentUuid] (root when null) and saves. */
    suspend fun addGroup(parentUuid: UUID?, name: String): Result<UUID> = io {
        mutex.withLock {
            val db = requireDatabase()
            val parent = parentUuid?.let { db.findGroup(it) } ?: db.root
            val group = Group(name = name.trim())
            parent.addGroup(group)
            noteActivity()
            saveLocked()
            group.uuid
        }
    }

    /**
     * Deletes a group and everything inside it, recording tombstones for every removed object so
     * the deletion propagates on the next sync.
     */
    suspend fun deleteGroup(uuid: UUID): Result<Unit> = io {
        mutex.withLock {
            val db = requireDatabase()
            val group = db.findGroup(uuid)
                ?: throw VaultException(VaultError.NotFound, "That group is no longer in the database")
            if (group === db.root) throw VaultException(VaultError.Invalid, "The root group cannot be deleted")

            val bin = if (db.meta.recycleBinEnabled) recycleBin(db) else null
            // Moving a group that contains the bin into the bin would detach the subtree, so
            // those groups are deleted outright instead.
            val wouldEnclose = bin != null && group.groupsRecursive().any { it.uuid == bin.uuid }

            if (bin != null && !wouldEnclose && !db.isInRecycleBin(group)) {
                group.previousParentGroup = group.parent?.uuid
                group.parent?.groups?.remove(group)
                bin.addGroup(group)
                group.times.locationChanged = Times.nowSeconds()
            } else {
                val removed = group.groupsRecursive()
                removed.forEach { g ->
                    g.entries.forEach { e ->
                        lastSaved.remove(e.uuid)
                        db.addDeletedObject(e.uuid)
                    }
                    db.addDeletedObject(g.uuid)
                }
                group.parent?.groups?.remove(group)
                if (removed.any { it.uuid == db.meta.recycleBinUuid }) {
                    db.meta.recycleBinUuid = null
                    db.meta.recycleBinChanged = Times.nowSeconds()
                }
            }
            noteActivity()
            saveLocked()
        }
    }

    private fun recycleBin(db: KdbxDatabase): Group {
        db.recycleBin?.let { return it }
        // The name is the English one KeePassXC writes, so a database shared with the desktop
        // keeps a single bin; the UI shows its own translated label for this group.
        val bin = Group(name = "Recycle Bin", iconId = RECYCLE_BIN_ICON, enableAutoType = false, enableSearching = false)
        db.root.addGroup(bin)
        db.meta.recycleBinUuid = bin.uuid
        db.meta.recycleBinChanged = Times.nowSeconds()
        return bin
    }

    private fun trimHistory(db: KdbxDatabase, entry: Entry) {
        val max = db.meta.historyMaxItems
        if (max >= 0 && entry.history.size > max) {
            repeat(entry.history.size - max) { entry.history.removeAt(0) }
        }
    }

    /** Copies every user-visible field of [from] onto [to], leaving [to]'s history alone. */
    private fun copyInto(from: Entry, to: Entry) {
        to.fields.clear()
        to.fields.putAll(from.fields)
        to.iconId = from.iconId
        to.customIconUuid = from.customIconUuid
        to.foregroundColor = from.foregroundColor
        to.backgroundColor = from.backgroundColor
        to.overrideUrl = from.overrideUrl
        to.tags = from.tags
        to.attachments.clear()
        to.attachments.addAll(from.attachments)
        to.customData.clear()
        to.customData.putAll(from.customData)
        to.autoType = from.autoType
        to.qualityCheck = from.qualityCheck
        to.previousParentGroup = from.previousParentGroup
        to.unknownXml = from.unknownXml.toMutableList()
        to.times.expires = from.times.expires
        to.times.expiryTime = from.times.expiryTime
        to.times.usageCount = from.times.usageCount
    }

    // ------------------------------------------------------------------ search

    /**
     * Case-insensitive search over title, user name, URL, notes and tags. All whitespace-separated
     * terms must match somewhere in the entry.
     *
     * Arabic text is normalised first — diacritics and tatweel are removed and the alef/ya/ta-marbuta
     * variants are folded — so "احمد" finds "أحمد".
     */
    fun search(query: String): List<Entry> {
        val db = _database.value ?: return emptyList()
        val terms = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        val pool = if (searchIncludesRecycleBin) db.allEntries() else db.visibleEntries()
        if (terms.isEmpty()) return pool
        return pool.filter { entry ->
            val haystack = normalizeForSearch(
                buildString {
                    append(entry.title).append(' ')
                    append(entry.username).append(' ')
                    append(entry.url).append(' ')
                    append(entry.notes).append(' ')
                    append(entry.tags)
                }
            )
            terms.all { haystack.contains(it) }
        }
    }

    // --------------------------------------------------------------- biometrics

    /**
     * Stores the master password for biometric unlock. The password is verified against the key
     * that opened the vault first, so a typo cannot enrol a password that will never work.
     */
    fun enableBiometricUnlock(password: String): Result<Unit> = runCatching {
        val current = compositeKey ?: throw VaultException(VaultError.Locked, "Unlock the vault first")
        val keyFileBytes = keyFileCopy.takeIf { it.isFile }?.readBytes()
        val candidate = CompositeKey.build(password.takeIf { it.isNotEmpty() }, keyFileBytes)
        if (!MessageDigest.isEqual(candidate.rawKey(), current.rawKey())) {
            throw VaultException(VaultError.InvalidCredentials, "That is not the password this vault was opened with")
        }
        secureStore.enroll(password).getOrElse { cause ->
            throw VaultException(VaultError.Biometric, cause.message ?: "Biometric unlock is unavailable", cause)
        }
        _status.value = _status.value.copy(hasBiometric = true)
    }

    /** Forgets the wrapped master password. */
    fun disableBiometricUnlock() {
        secureStore.clear()
        _status.value = _status.value.copy(hasBiometric = false)
    }

    // ------------------------------------------------------------------ backups

    /** Copies the encrypted database to a location the user picked. Nothing is decrypted. */
    suspend fun exportBackup(uri: Uri): Result<Unit> = io {
        val bytes = currentBytes()
            ?: throw VaultException(VaultError.NoDatabase, "There is no database to export")
        try {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw VaultException(VaultError.Io, "The chosen location could not be written to")
        } catch (e: SecurityException) {
            throw VaultException(VaultError.Io, "Permission to write the chosen file was denied", e)
        } catch (e: IOException) {
            throw VaultException(VaultError.Io, "The backup could not be written: ${e.message}", e)
        }
    }

    // --------------------------------------------------------------------- sync

    /** The encrypted database exactly as it is on disk, for sending to a paired desktop. */
    fun currentBytes(): ByteArray? = dbFile.takeIf { it.isFile }?.readBytes()

    /**
     * Merges a database received from a paired device into this one and saves the result.
     *
     * The remote copy is opened with *our* composite key before anything is touched: if the
     * desktop returned something we cannot decrypt, the local vault is left exactly as it was.
     * Merging (rather than overwriting) keeps any edit made on the phone while the transfer was
     * in flight, and still converges — the desktop already merged our copy into its own.
     *
     * @return added, updated, deleted counts.
     */
    suspend fun mergeFrom(remoteBytes: ByteArray): Result<Triple<Int, Int, Int>> = io {
        mutex.withLock {
            val key = compositeKey ?: throw VaultException(VaultError.Locked, "The vault is locked")
            val local = requireDatabase()
            validateContainer(remoteBytes)

            val remote = try {
                KdbxReader.read(remoteBytes, key)
            } catch (e: Exception) {
                throw VaultException(
                    VaultError.MergeFailed,
                    "The database sent by the other device does not open with this vault's key",
                    e,
                )
            }

            val changes = try {
                Merger(remote, local).merge()
            } catch (e: Exception) {
                throw VaultException(VaultError.MergeFailed, "The databases could not be merged: ${e.message}", e)
            }

            val added = changes.count {
                it.kind == Merger.Change.Kind.EntryAdded || it.kind == Merger.Change.Kind.GroupAdded
            }
            val updated = changes.count {
                it.kind == Merger.Change.Kind.EntryUpdated ||
                    it.kind == Merger.Change.Kind.GroupUpdated ||
                    it.kind == Merger.Change.Kind.Moved
            }
            val deleted = changes.count {
                it.kind == Merger.Change.Kind.EntryDeleted || it.kind == Merger.Change.Kind.GroupDeleted
            }

            saveLocked()
            Triple(added, updated, deleted)
        }
    }

    // -------------------------------------------------------------- auto-lock

    /** Call on user interaction; auto-lock measures idle time from the last call. */
    fun noteActivity() {
        lastActivityElapsed = SystemClock.elapsedRealtime()
    }

    /** Seconds since the last [noteActivity]. Monotonic — unaffected by clock changes. */
    fun idleSeconds(): Long = (SystemClock.elapsedRealtime() - lastActivityElapsed) / 1000L

    /**
     * Locks the vault when the configured idle timeout has elapsed.
     * @return true if this call locked the vault.
     */
    suspend fun lockIfIdle(): Boolean {
        if (_status.value.state != VaultState.Unlocked) return false
        val timeout = prefs.current().autoLockSeconds
        if (timeout == Prefs.AUTO_LOCK_NEVER) return false
        if (idleSeconds() < timeout) return false
        lock()
        return true
    }

    /**
     * Arms a repository-scoped watcher that enforces [lockIfIdle] at the auto-lock deadline.
     *
     * Called when the app leaves the foreground. The ViewModel keeps its own countdown as a
     * second layer, but that one dies with the Activity; this watcher runs in [lockScope] and
     * therefore keeps ticking until the vault is locked or the process itself is gone.
     */
    fun armBackgroundLock() {
        relockJob?.cancel()
        relockJob = lockScope.launch {
            while (isActive && _status.value.state == VaultState.Unlocked) {
                val timeout = prefs.current().autoLockSeconds
                if (timeout == Prefs.AUTO_LOCK_NEVER) return@launch
                if (lockIfIdle()) return@launch
                val remainingMillis = (timeout - idleSeconds()) * 1000L
                delay(remainingMillis.coerceAtLeast(RELOCK_POLL_MILLIS))
            }
        }
    }

    /** Stands the watcher down; called once the user is back and the ViewModel timers run. */
    fun disarmBackgroundLock() {
        relockJob?.cancel()
        relockJob = null
    }

    // ------------------------------------------------------------------ helpers

    private fun requireDatabase(): KdbxDatabase =
        _database.value ?: throw VaultException(VaultError.Locked, "The vault is locked")

    private fun markDirty() {
        _status.value = _status.value.copy(dirty = true)
    }

    private fun rebuildSnapshot(db: KdbxDatabase) {
        lastSaved.clear()
        db.root.relink()
        db.allEntries().forEach { lastSaved[it.uuid] = it.deepCopy() }
    }

    private fun openWith(bytes: ByteArray, key: CompositeKey): KdbxDatabase {
        validateContainer(bytes)
        return try {
            KdbxReader.read(bytes, key)
        } catch (e: KdbxException) {
            throw VaultException(
                VaultError.InvalidCredentials,
                "The database did not open — check the password and key file (${e.message})",
                e,
            )
        } catch (e: Exception) {
            // A malformed body can fail deep inside the parser with something other than
            // KdbxException; that is a damaged file, not a wrong password.
            throw VaultException(VaultError.CorruptFile, "The database file is damaged: ${e.message}", e)
        }
    }

    /** Rejects anything that is not a KDBX 3.1+ container before we spend a KDF on it. */
    private fun validateContainer(bytes: ByteArray) {
        if (bytes.size < 12) {
            throw VaultException(VaultError.CorruptFile, "That file is too small to be a KDBX database")
        }
        val header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val sig1 = header.int
        val sig2 = header.int
        if (sig1 != Kdbx.SIGNATURE_1 || sig2 != Kdbx.SIGNATURE_2) {
            throw VaultException(VaultError.CorruptFile, "That file is not a KDBX database")
        }
        val version = header.int
        val major = version and Kdbx.FILE_VERSION_CRITICAL_MASK
        // Bind the comparands to locals: `major < (...) || major > ...` parses as type arguments.
        val oldestSupported = Kdbx.FILE_VERSION_3_1 and Kdbx.FILE_VERSION_CRITICAL_MASK
        val newestSupported = Kdbx.FILE_VERSION_4
        if (major < oldestSupported || major > newestSupported) {
            throw VaultException(VaultError.CorruptFile, "This KDBX version is not supported by Hisn")
        }
    }

    private fun readKeyFile(uri: Uri): ByteArray = try {
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw VaultException(VaultError.Io, "The key file could not be opened")
    } catch (e: SecurityException) {
        throw VaultException(VaultError.Io, "Permission to read the key file was denied", e)
    } catch (e: IOException) {
        throw VaultException(VaultError.Io, "The key file could not be read: ${e.message}", e)
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun writeStoredMeta(name: String, hasKeyFile: Boolean) {
        runCatching {
            vaultDir.mkdirs()
            metaFile.writeText(
                JSONObject()
                    .put("name", name)
                    .put("hasKeyFile", hasKeyFile)
                    .put("importedAt", System.currentTimeMillis())
                    .toString()
            )
        }
    }

    private fun readStoredName(): String? = runCatching {
        if (!metaFile.isFile) return@runCatching null
        JSONObject(metaFile.readText()).optString("name").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Runs [block] off the main thread and converts failures into a [Result].
     * Cancellation is rethrown rather than reported as a failure, so a cancelled screen does not
     * look like a broken vault.
     */
    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        private const val DB_NAME = "hisn.kdbx"
        private const val RECYCLE_BIN_ICON = 43
        private const val MIN_DB_BYTES = 64

        /** Shortest re-check interval of the background-lock watcher, in milliseconds. */
        private const val RELOCK_POLL_MILLIS = 1_000L

        @Volatile
        private var sharedInstance: VaultRepository? = null

        /**
         * The one repository for the whole process.
         *
         * There must be exactly one: the vault is a single file plus decrypted state in memory, and
         * two instances would each hold their own copy. The autofill service runs in the same
         * process as the UI, so a password saved from a fill prompt through one instance would be
         * invisible to — and overwritable by — the other. Unlocking in one would also leave the
         * other still locked, which the user experiences as the app forgetting they just unlocked.
         */
        fun shared(context: Context): VaultRepository {
            sharedInstance?.let { return it }
            return synchronized(this) {
                sharedInstance ?: VaultRepository(context.applicationContext).also { sharedInstance = it }
            }
        }

        /**
         * Folds the Arabic forms that users type interchangeably, so search behaves the way an
         * Arabic speaker expects rather than the way Unicode code points happen to compare.
         */
        fun normalizeForSearch(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text.lowercase()) {
                when (ch) {
                    'ـ' -> {}                              // tatweel
                    in 'ً'..'ْ' -> {}                 // harakat
                    'ٓ', 'ٔ', 'ٕ', 'ٰ' -> {} // maddah, hamza above/below, dagger alef
                    'آ', 'أ', 'إ', 'ٱ' -> sb.append('ا') // alef forms
                    'ى' -> sb.append('ي')             // alef maqsura -> ya
                    'ة' -> sb.append('ه')             // ta marbuta -> ha
                    'ؤ' -> sb.append('و')             // waw with hamza
                    'ئ' -> sb.append('ي')             // ya with hamza
                    else -> if (ch.isWhitespace()) sb.append(' ') else sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
