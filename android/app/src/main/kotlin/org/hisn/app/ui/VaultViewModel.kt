package org.hisn.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.hisn.app.R
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState
import org.hisn.app.data.VaultStatus
import org.hisn.app.kdbx.Entry
import org.hisn.app.kdbx.KdbxDatabase
import org.hisn.app.kdbx.Times
import org.hisn.app.kdbx.Totp
import org.hisn.app.kdbx.TotpSettings
import org.hisn.app.sync.PairedDevice
import org.hisn.app.sync.SyncClient
import org.hisn.app.sync.SyncProgress
import org.hisn.app.ui.components.SecretClipboard
import org.hisn.app.ui.theme.ThemeMode
import java.text.Collator
import java.util.UUID

// ---------------------------------------------------------------------------------------
// Immutable snapshots the UI renders.
//
// The KDBX model is deliberately mutable (the format writer edits it in place), which makes
// it unusable as Compose state: mutating an Entry cannot invalidate a composition. Every
// screen therefore reads one of these snapshots, rebuilt whenever the database changes.
// ---------------------------------------------------------------------------------------

@Immutable
data class EntryCard(
    val uuid: UUID,
    val title: String,
    val username: String,
    val initial: String,
    val groupName: String,
    val expired: Boolean,
    val totp: TotpSettings?,
)

/** A run of entries under one heading; [title] is null when the list is already filtered. */
@Immutable
data class EntrySection(val title: String?, val entries: List<EntryCard>)

@Immutable
data class CustomFieldUi(val key: String, val value: String, val protected: Boolean)

@Immutable
data class EntryDetail(
    val uuid: UUID,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val totp: TotpSettings?,
    val hasTotpField: Boolean,
    val customFields: List<CustomFieldUi>,
    val attachments: List<String>,
    val groupName: String,
    val created: Long,
    val modified: Long,
    val expiresAt: Long?,
    val expired: Boolean,
)

/** The editable form state. [uuid] is null for an entry that does not exist yet. */
@Immutable
data class EntryDraft(
    val uuid: UUID? = null,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp: String = "",
    val customFields: List<CustomFieldUi> = emptyList(),
    val groupUuid: UUID? = null,
    val groupName: String = "",
)

@Immutable
data class GroupOption(val uuid: UUID, val name: String, val path: String, val count: Int)

@Immutable
data class UiSettings(
    val autoLockMinutes: Int = 5,
    val clipboardSeconds: Int = 30,
    val themeMode: ThemeMode = ThemeMode.System,
)

/** Why an unlock attempt failed, ready to render on the unlock screen. */
@Immutable
data class UnlockError(@StringRes val message: Int, val detail: String?)

/** One-shot feedback: a snackbar, never a silently swallowed failure. */
sealed interface UiEvent {
    data class Message(@StringRes val message: Int, val arg: String? = null) : UiEvent

    /** A copy confirmation that also tells the user when the clipboard will be wiped. */
    data class CopiedWithTimer(@StringRes val message: Int, val seconds: Int) : UiEvent

    data class Failure(@StringRes val message: Int, val detail: String?) : UiEvent
}

/** Auto-lock choices in minutes; -1 means never, 0 means as soon as the app is hidden. */
val AUTO_LOCK_CHOICES = listOf(0, 1, 5, 15, 30, -1)

/** Clipboard-clear choices in seconds; 0 means never clear automatically. */
val CLIPBOARD_CHOICES = listOf(10, 30, 60, 120, 0)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = VaultRepository(application)
    private val syncClient = SyncClient(application, repo)
    private val clipboard = SecretClipboard(application, viewModelScope)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Locale-aware ordering: Arabic titles must not sort by UTF-16 code unit. */
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val cardOrder = Comparator<EntryCard> { a, b ->
        val byTitle = collator.compare(a.title.ifBlank { a.username }, b.title.ifBlank { b.username })
        if (byTitle != 0) byTitle else a.uuid.compareTo(b.uuid)
    }

    /**
     * Bumped after every mutation. [VaultRepository.database] emits the same
     * [KdbxDatabase] instance it mutated in place, and a StateFlow suppresses a repeat of
     * an equal value, so this is what actually drives the derived lists.
     */
    private val revision = MutableStateFlow(0)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _groupFilter = MutableStateFlow<UUID?>(null)
    val groupFilter: StateFlow<UUID?> = _groupFilter.asStateFlow()

    private val _unlockError = MutableStateFlow<UnlockError?>(null)
    val unlockError: StateFlow<UnlockError?> = _unlockError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<UiSettings> = _settings.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(repo.biometricEnabled)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val status: StateFlow<VaultStatus> = repo.status
    val syncProgress: StateFlow<SyncProgress> = syncClient.progress
    val pairedDevices: StateFlow<List<PairedDevice>> = syncClient.pairedDevices

    private var autoLockJob: Job? = null

    val sections: StateFlow<List<EntrySection>> =
        combine(repo.database, _query, _groupFilter, revision) { db, query, group, _ ->
            buildSections(db, query, group)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<GroupOption>> =
        combine(repo.database, revision) { db, _ -> buildGroups(db) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entryCount: StateFlow<Int> =
        combine(repo.database, revision) { db, _ -> db?.visibleEntries()?.size ?: 0 }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // -- Database lifecycle ---------------------------------------------------------------

    fun importDatabase(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.importDatabase(uri)
            _busy.value = false
            result.onSuccess {
                _unlockError.value = null
                bump()
            }.onFailure {
                _unlockError.value = UnlockError(R.string.error_import_failed, it.message)
            }
        }
    }

    fun unlock(password: String, keyFileUri: Uri?) {
        if (password.isEmpty() && keyFileUri == null) {
            _unlockError.value = UnlockError(R.string.error_credentials_empty, null)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _unlockError.value = null
            val result = repo.unlock(password, keyFileUri)
            _busy.value = false
            result.onSuccess {
                bump()
            }.onFailure {
                _unlockError.value = UnlockError(R.string.error_unlock_failed, it.message)
            }
        }
    }

    /**
     * @param payload the crypto payload from the biometric prompt. The repository owns the
     *   KeyStore-wrapped master password, so this is empty when the prompt was run without a
     *   CryptoObject and the OS authentication itself is the proof.
     */
    fun unlockWithBiometric(payload: ByteArray) {
        viewModelScope.launch {
            _busy.value = true
            _unlockError.value = null
            val result = repo.unlockWithBiometric(payload)
            _busy.value = false
            result.onSuccess {
                bump()
            }.onFailure {
                _unlockError.value = UnlockError(R.string.error_biometric_unlock_failed, it.message)
            }
        }
    }

    fun biometricFailed(detail: String?) {
        _unlockError.value = UnlockError(R.string.error_biometric_unlock_failed, detail)
    }

    fun lock() {
        autoLockJob?.cancel()
        autoLockJob = null
        // A locked vault that left a password on the clipboard is not locked.
        clipboard.clearNow()
        repo.lock()
        _query.value = ""
        _groupFilter.value = null
        _unlockError.value = null
        bump()
    }

    fun clearUnlockError() {
        _unlockError.value = null
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.exportBackup(uri)
            _busy.value = false
            result.onSuccess {
                emit(UiEvent.Message(R.string.msg_backup_exported))
            }.onFailure {
                emit(UiEvent.Failure(R.string.error_export_failed, it.message))
            }
        }
    }

    // -- Auto-lock ------------------------------------------------------------------------

    /** Called when the activity stops: start the countdown that locks the vault. */
    fun onAppBackgrounded() {
        val minutes = _settings.value.autoLockMinutes
        if (minutes < 0) return
        autoLockJob?.cancel()
        autoLockJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            if (repo.status.value.state == VaultState.Unlocked) lock()
        }
    }

    /** Called when the activity starts again: the user is back, so cancel the countdown. */
    fun onAppForegrounded() {
        autoLockJob?.cancel()
        autoLockJob = null
    }

    // -- Browsing -------------------------------------------------------------------------

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setGroupFilter(uuid: UUID?) {
        _groupFilter.value = uuid
    }

    fun detailOf(uuid: UUID): StateFlow<EntryDetail?> {
        val initial = repo.database.value?.let { db -> db.findEntry(uuid)?.toDetail(db) }
        return combine(repo.database, revision) { db, _ -> db?.findEntry(uuid)?.toDetail(db) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }

    fun draftFor(uuid: UUID?): EntryDraft {
        val db = repo.database.value ?: return EntryDraft()
        val entry = uuid?.let { db.findEntry(it) }
            ?: return EntryDraft(groupUuid = db.root.uuid, groupName = groupLabel(db.root.name, db))
        return EntryDraft(
            uuid = entry.uuid,
            title = entry.title,
            username = entry.username,
            password = entry.password,
            url = entry.url,
            notes = entry.notes,
            // Entries written by older plugins split the seed and its tuning across several
            // attributes. Editing must not silently drop the period/digits, so anything that
            // is not already a plain "otp" attribute is presented as an otpauth:// URI.
            totp = entry.fields[Entry.OTP]?.value?.takeIf { it.isNotBlank() }
                ?: Totp.forEntry(entry)?.toOtpAuthUri(entry.title, entry.username)
                ?: "",
            customFields = entry.customFieldKeys()
                .filterNot { it in OTP_KEYS }
                .map { key ->
                    val field = entry.fields[key]
                    CustomFieldUi(key, field?.value.orEmpty(), field?.protected == true)
                },
            groupUuid = entry.parent?.uuid ?: db.root.uuid,
            groupName = groupLabel(entry.parent?.name ?: db.root.name, db),
        )
    }

    // -- Editing --------------------------------------------------------------------------

    fun saveEntry(draft: EntryDraft, onSaved: () -> Unit) {
        viewModelScope.launch {
            val db = repo.database.value
            if (db == null) {
                emit(UiEvent.Failure(R.string.error_no_database, null))
                return@launch
            }
            _busy.value = true
            val result = if (draft.uuid == null) {
                val entry = Entry()
                entry.times.creationTime = Times.nowSeconds()
                applyDraft(entry, draft, db)
                repo.addEntry(draft.groupUuid ?: db.root.uuid, entry)
            } else {
                val original = db.findEntry(draft.uuid)
                if (original == null) {
                    _busy.value = false
                    emit(UiEvent.Failure(R.string.error_entry_missing, null))
                    return@launch
                }
                // A copy, not the live node: the repository needs the untouched original
                // still in the tree to record the previous version in the entry's history.
                val edited = original.deepCopy()
                applyDraft(edited, draft, db)
                repo.updateEntry(edited)
            }
            val saved = result.isSuccess && persist()
            _busy.value = false
            bump()
            result.onFailure { emit(UiEvent.Failure(R.string.error_save_failed, it.message)) }
            if (saved) {
                emit(UiEvent.Message(R.string.msg_entry_saved))
                onSaved()
            }
        }
    }

    fun deleteEntry(uuid: UUID, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.deleteEntry(uuid)
            val done = result.isSuccess && persist()
            _busy.value = false
            bump()
            result.onFailure { emit(UiEvent.Failure(R.string.error_delete_failed, it.message)) }
            if (done) {
                emit(UiEvent.Message(R.string.msg_entry_deleted))
                onDeleted()
            }
        }
    }

    // -- Clipboard ------------------------------------------------------------------------

    fun copyUsername(uuid: UUID) {
        val entry = repo.database.value?.findEntry(uuid) ?: return
        copy(entry.username, R.string.msg_username_copied, sensitive = false)
    }

    fun copyPassword(uuid: UUID) {
        val entry = repo.database.value?.findEntry(uuid) ?: return
        copy(entry.password, R.string.msg_password_copied, sensitive = true)
    }

    fun copyTotp(uuid: UUID) {
        val settings = repo.database.value?.findEntry(uuid)?.totpSettings()
        if (settings == null) {
            emit(UiEvent.Failure(R.string.error_totp_unreadable, null))
            return
        }
        val code = runCatching { Totp.generate(settings).value }.getOrElse {
            emit(UiEvent.Failure(R.string.error_totp_unreadable, it.message))
            return
        }
        copy(code, R.string.msg_totp_copied, sensitive = true)
    }

    fun copyValue(value: String, @StringRes message: Int, sensitive: Boolean) {
        copy(value, message, sensitive)
    }

    /** Lets a screen surface a failure it detected itself, e.g. no app can open a URL. */
    fun reportFailure(@StringRes message: Int, detail: String?) {
        emit(UiEvent.Failure(message, detail))
    }

    private fun copy(value: String, @StringRes message: Int, sensitive: Boolean) {
        if (value.isEmpty()) {
            emit(UiEvent.Failure(R.string.error_nothing_to_copy, null))
            return
        }
        val seconds = if (sensitive) _settings.value.clipboardSeconds else 0
        if (!clipboard.copy(CLIP_LABEL, value, seconds)) {
            emit(UiEvent.Failure(R.string.error_clipboard_unavailable, null))
            return
        }
        if (seconds > 0) {
            emit(UiEvent.CopiedWithTimer(message, seconds))
        } else {
            emit(UiEvent.Message(message))
        }
    }

    // -- Settings -------------------------------------------------------------------------

    fun setAutoLockMinutes(minutes: Int) {
        _settings.value = _settings.value.copy(autoLockMinutes = minutes)
        prefs.edit().putInt(KEY_AUTO_LOCK, minutes).apply()
    }

    fun setClipboardSeconds(seconds: Int) {
        _settings.value = _settings.value.copy(clipboardSeconds = seconds)
        prefs.edit().putInt(KEY_CLIPBOARD, seconds).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    /** Wraps the master password with the device keystore so a fingerprint can unlock. */
    fun enableBiometricUnlock(masterPassword: String) {
        if (masterPassword.isEmpty()) {
            emit(UiEvent.Failure(R.string.error_credentials_empty, null))
            return
        }
        repo.enableBiometricUnlock(masterPassword)
            .onSuccess {
                _biometricEnabled.value = repo.biometricEnabled
                emit(UiEvent.Message(R.string.msg_biometric_enabled))
            }
            .onFailure {
                emit(UiEvent.Failure(R.string.error_biometric_enable_failed, it.message))
            }
    }

    // -- Sync -----------------------------------------------------------------------------

    fun pairFromCode(payload: String) {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) {
            emit(UiEvent.Failure(R.string.error_pairing_code_empty, null))
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = syncClient.pairFromQrPayload(trimmed)
            _busy.value = false
            result.onSuccess { emit(UiEvent.Message(R.string.msg_device_paired, it.name)) }
                .onFailure { emit(UiEvent.Failure(R.string.error_pairing_failed, it.message)) }
        }
    }

    fun syncWith(device: PairedDevice) {
        viewModelScope.launch {
            val result = syncClient.syncWith(device)
            bump()
            result.onFailure { emit(UiEvent.Failure(R.string.error_sync_failed, it.message)) }
        }
    }

    fun forgetDevice(device: PairedDevice) {
        syncClient.forget(device)
        emit(UiEvent.Message(R.string.msg_device_forgotten, device.name))
    }

    // -- Internals ------------------------------------------------------------------------

    private fun bump() {
        revision.value = revision.value + 1
    }

    private fun emit(event: UiEvent) {
        _events.tryEmit(event)
    }

    /** @return true when the vault is safely on disk. */
    private suspend fun persist(): Boolean {
        if (!repo.status.value.dirty) return true
        val result = repo.save()
        result.onFailure { emit(UiEvent.Failure(R.string.error_save_failed, it.message)) }
        return result.isSuccess
    }

    private fun readSettings(): UiSettings {
        val theme = prefs.getString(KEY_THEME, ThemeMode.System.name)
        return UiSettings(
            autoLockMinutes = prefs.getInt(KEY_AUTO_LOCK, 5),
            clipboardSeconds = prefs.getInt(KEY_CLIPBOARD, 30),
            themeMode = ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.System,
        )
    }

    private fun buildSections(db: KdbxDatabase?, query: String, group: UUID?): List<EntrySection> {
        if (db == null) return emptyList()
        val matches = if (query.isBlank()) {
            db.visibleEntries()
        } else {
            repo.search(query).filterNot { db.isInRecycleBin(it) }
        }
        val filtered = if (group == null) matches else matches.filter { it.parent?.uuid == group }
        val cards = filtered.map { it.toCard(db) }.sortedWith(cardOrder)

        // With a search term or a group filter the grouping is redundant noise.
        if (query.isNotBlank() || group != null) {
            return if (cards.isEmpty()) emptyList() else listOf(EntrySection(null, cards))
        }
        return cards.groupBy { it.groupName }
            .toList()
            .sortedWith(compareBy(collator) { it.first })
            .map { (name, entries) -> EntrySection(name, entries) }
    }

    private fun buildGroups(db: KdbxDatabase?): List<GroupOption> {
        if (db == null) return emptyList()
        return db.root.groupsRecursive()
            .filterNot { db.isInRecycleBin(it) }
            .map { group ->
                GroupOption(
                    uuid = group.uuid,
                    name = groupLabel(group.name, db),
                    path = group.path(),
                    count = group.entries.size,
                )
            }
            .sortedWith(compareBy(collator) { it.name })
    }

    /** Groups written by other tools sometimes carry no name; fall back to the vault's. */
    private fun groupLabel(name: String, db: KdbxDatabase): String =
        name.ifBlank { db.meta.databaseName.ifBlank { name } }

    /** Reads every TOTP storage layout the desktop can write, not just the "otp" attribute. */
    private fun Entry.totpSettings(): TotpSettings? = Totp.forEntry(this)

    private fun Entry.toCard(db: KdbxDatabase): EntryCard {
        val name = title
        val label = name.ifBlank { username }
        return EntryCard(
            uuid = uuid,
            title = name,
            username = username,
            initial = label.trim().firstOrNull()?.toString()?.uppercase().orEmpty().ifEmpty { "•" },
            groupName = groupLabel(parent?.name ?: db.root.name, db),
            expired = isExpired(),
            totp = totpSettings(),
        )
    }

    private fun Entry.toDetail(db: KdbxDatabase): EntryDetail = EntryDetail(
        uuid = uuid,
        title = title,
        username = username,
        password = password,
        url = url,
        notes = notes,
        totp = totpSettings(),
        hasTotpField = fields.keys.any { it in OTP_KEYS },
        customFields = customFieldKeys()
            .filterNot { it in OTP_KEYS }
            .map { key ->
                val field = fields[key]
                CustomFieldUi(key, field?.value.orEmpty(), field?.protected == true)
            },
        attachments = attachments.map { it.name },
        groupName = groupLabel(parent?.name ?: db.root.name, db),
        created = times.creationTime,
        modified = times.lastModificationTime,
        expiresAt = times.expiryTime.takeIf { times.expires },
        expired = isExpired(),
    )

    private fun Entry.isExpired(): Boolean {
        val expiry = times.expiryTime ?: return false
        return times.expires && expiry <= Times.nowSeconds()
    }

    private fun applyDraft(entry: Entry, draft: EntryDraft, db: KdbxDatabase) {
        val meta = db.meta
        entry.set(Entry.TITLE, draft.title, meta.protectTitle)
        entry.set(Entry.USERNAME, draft.username, meta.protectUserName)
        entry.set(Entry.PASSWORD, draft.password, meta.protectPassword)
        entry.set(Entry.URL, draft.url, meta.protectUrl)
        entry.set(Entry.NOTES, draft.notes, meta.protectNotes)

        // The draft carries the whole TOTP configuration (an otpauth:// URI when the entry
        // used one of the split legacy layouts), so the old attributes are replaced by the
        // single "otp" attribute KeePassXC writes today rather than left half-updated.
        OTP_KEYS.forEach { entry.fields.remove(it) }
        if (draft.totp.isNotBlank()) entry.set(Entry.OTP, draft.totp.trim(), protected = true)

        val reserved = Entry.DEFAULT_FIELDS + OTP_KEYS
        entry.fields.keys.filterNot { it in reserved }.toList().forEach { entry.fields.remove(it) }
        draft.customFields
            .filter { it.key.isNotBlank() }
            .forEach { entry.set(it.key.trim(), it.value, it.protected) }

        val now = Times.nowSeconds()
        entry.times.lastModificationTime = now
        entry.times.lastAccessTime = now
    }

    private companion object {
        const val PREFS_NAME = "hisn_ui_settings"
        const val KEY_AUTO_LOCK = "auto_lock_minutes"
        const val KEY_CLIPBOARD = "clipboard_seconds"
        const val KEY_THEME = "theme_mode"

        /** Some launchers surface the clip label; the brand name says nothing secret. */
        const val CLIP_LABEL = "Hisn"

        /**
         * Every attribute name that is part of a TOTP configuration in some tool's dialect.
         * They are hidden from the custom-field editor and rewritten as a unit on save, so a
         * seed can never end up separated from the settings that describe it.
         */
        val OTP_KEYS = setOf(
            Entry.OTP,
            "TOTP Seed",
            "TOTP Settings",
            "TimeOtp-Secret-Base32",
            "TimeOtp-Length",
            "TimeOtp-Period",
            "TimeOtp-Algorithm",
        )
    }
}
