package org.hisn.app.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.hisn.app.R
import org.hisn.app.data.HisnSettings
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState
import org.hisn.app.kdbx.Entry
import org.hisn.app.kdbx.KdbxDatabase
import org.hisn.app.kdbx.Meta
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.HisnTextField
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle
import java.util.UUID

/**
 * "حفظ في حصن؟" — offered after a login was submitted in another app.
 *
 * Two things make this screen worth having rather than saving silently:
 *
 *  1. The user sees what is about to be stored, including the password behind a reveal toggle.
 *     A credential saved wrongly is worse than one not saved at all, because it will be offered
 *     later as if it were right.
 *  2. Whichever branch is taken, the entry ends up carrying the binding
 *     [AutofillMatcher.bindingUrlFor] produces, so the *next* request for this app or site
 *     matches it. Without that step the same login would be offered for saving over and over.
 */
class AutofillSaveActivity : FragmentActivity() {

    private enum class Stage { Checking, Password, Form, NoDatabase }

    private enum class Mode { New, Update }

    /** An entry the target already matches — a candidate for "تحديث مُدخَل موجود". */
    private data class Candidate(val uuid: UUID, val title: String, val username: String)

    private data class GroupOption(val uuid: UUID, val label: String)

    private data class SaveUi(
        val stage: Stage = Stage.Checking,
        val busy: Boolean = false,
        val error: String? = null,
        val mode: Mode = Mode.New,
        val title: String = "",
        val revealed: Boolean = false,
        val groups: List<GroupOption> = emptyList(),
        val groupUuid: UUID? = null,
        val candidates: List<Candidate> = emptyList(),
        val selected: UUID? = null,
    )

    private lateinit var repo: VaultRepository
    private var target: AutofillTarget? = null
    private var username: String = ""
    private var password: String = ""

    private val ui: MutableState<SaveUi> = mutableStateOf(SaveUi())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAutofillSecureFlag(block = true)

        repo = AutofillVault.repository(this)
        target = AutofillFlow.targetOf(intent)
        username = intent.getStringExtra(AutofillConstants.EXTRA_SUBMITTED_USERNAME).orEmpty()
        password = intent.getStringExtra(AutofillConstants.EXTRA_SUBMITTED_PASSWORD).orEmpty()

        // Nothing worth storing, or nothing to bind it to.
        if (target == null || password.isEmpty()) {
            finish()
            return
        }
        ui.value = ui.value.copy(title = target?.displayName().orEmpty())

        setContent {
            val settings by repo.prefs.settings.collectAsState(initial = HisnSettings())
            LaunchedEffect(settings.blockScreenshots) {
                applyAutofillSecureFlag(settings.blockScreenshots)
            }
            HisnTheme(themeMode = settings.theme) {
                Content()
            }
        }

        lifecycleScope.launch { begin() }
    }

    private suspend fun begin() {
        repo.lockIfIdle()
        when (repo.status.value.state) {
            VaultState.Unlocked -> showForm()
            VaultState.Locked -> ui.value = ui.value.copy(stage = Stage.Password)
            VaultState.NoDatabase -> ui.value = ui.value.copy(stage = Stage.NoDatabase)
        }
    }

    private fun showForm() {
        val database = repo.database.value
        val current = target
        if (database == null || current == null) {
            ui.value = ui.value.copy(stage = Stage.Password)
            return
        }

        val groups = database.root.groupsRecursive()
            .filterNot { database.isInRecycleBin(it) }
            .map { group ->
                GroupOption(
                    uuid = group.uuid,
                    label = group.path().ifBlank { group.name },
                )
            }
        val candidates = AutofillMatcher.match(database, current).map { match ->
            Candidate(
                uuid = match.entry.uuid,
                title = match.entry.title.ifBlank { match.entry.username }
                    .ifBlank { getString(R.string.entry_untitled) },
                username = match.entry.username,
            )
        }

        ui.value = ui.value.copy(
            stage = Stage.Form,
            error = null,
            groups = groups,
            groupUuid = ui.value.groupUuid ?: database.root.uuid,
            candidates = candidates,
            // Updating the entry the login already matches is the likely intent — a changed
            // password — so it is preselected when there is exactly one obvious candidate.
            mode = if (candidates.size == 1) Mode.Update else ui.value.mode,
            selected = ui.value.selected ?: candidates.firstOrNull()?.uuid,
        )
    }

    private fun save() {
        val state = ui.value
        if (state.busy) return
        val database = repo.database.value
        val current = target
        if (database == null || current == null) {
            ui.value = state.copy(error = getString(R.string.error_no_database))
            return
        }

        lifecycleScope.launch {
            ui.value = ui.value.copy(busy = true, error = null)
            val result = when (state.mode) {
                Mode.New -> saveAsNew(database, current, state)
                Mode.Update -> saveOverExisting(database, current, state)
            }
            // addEntry/updateEntry persist inside the same lock; save() is the retry that
            // matters only when that write failed and left the vault dirty.
            val persisted = result.isSuccess &&
                (!repo.status.value.dirty || repo.save().isSuccess)

            if (persisted) {
                repo.noteActivity()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                ui.value = ui.value.copy(busy = false, error = getString(R.string.error_save_failed))
            }
        }
    }

    private suspend fun saveAsNew(
        database: KdbxDatabase,
        target: AutofillTarget,
        state: SaveUi,
    ): Result<Unit> {
        val meta = database.meta
        val entry = Entry()
        entry.set(Entry.TITLE, state.title.trim().ifBlank { target.displayName() }, meta.protectTitle)
        entry.set(Entry.USERNAME, username, meta.protectUserName)
        entry.set(Entry.PASSWORD, password, meta.protectPassword)
        bind(entry, target, meta)
        return repo.addEntry(state.groupUuid ?: database.root.uuid, entry)
    }

    private suspend fun saveOverExisting(
        database: KdbxDatabase,
        target: AutofillTarget,
        state: SaveUi,
    ): Result<Unit> {
        val uuid = state.selected
            ?: return Result.failure(IllegalStateException("No entry was chosen"))
        val stored = database.findEntry(uuid)
            ?: return Result.failure(IllegalStateException("The entry is no longer in the database"))

        val meta = database.meta
        // A copy, not the live node: the repository needs the untouched original still in the
        // tree to record the previous version in the entry's history.
        val edited = stored.deepCopy()
        if (username.isNotEmpty()) edited.set(Entry.USERNAME, username, meta.protectUserName)
        edited.set(Entry.PASSWORD, password, meta.protectPassword)
        bind(edited, target, meta)
        return repo.updateEntry(edited)
    }

    /**
     * Makes sure the entry names the thing it was saved for, in the vocabulary the matcher reads.
     *
     * An entry that already matches is left alone: rewriting a URL the user curated, or piling up
     * duplicate `KP2A_URL_*` attributes on every save, is not our business.
     */
    private fun bind(entry: Entry, target: AutofillTarget, meta: Meta) {
        if (AutofillMatcher.scoreOf(entry, target) != null) return
        val binding = AutofillMatcher.bindingUrlFor(target)
        if (entry.url.isBlank()) {
            entry.set(Entry.URL, binding, meta.protectUrl)
            return
        }
        // The URL slot is taken, so use the additional-URL attribute both KeePassXC and
        // Keepass2Android understand.
        var index = 1
        while (entry.fields.containsKey("$ADDITIONAL_URL_KEY$index")) index++
        entry.set("$ADDITIONAL_URL_KEY$index", binding, protected = false)
    }

    /** [masterPassword] opens the vault; it is not the credential this screen is here to store. */
    private fun unlock(masterPassword: String) {
        if (ui.value.busy) return
        lifecycleScope.launch {
            ui.value = ui.value.copy(busy = true, error = null)
            val result = repo.unlock(masterPassword, keyFileUri = null)
            ui.value = ui.value.copy(busy = false)
            result
                .onSuccess { showForm() }
                .onFailure { ui.value = ui.value.copy(error = getString(R.string.error_unlock_failed)) }
        }
    }

    private fun unlockWithBiometric() {
        if (ui.value.busy) return
        promptForBiometricUnlock(
            repo = repo,
            onFailure = { ui.value = ui.value.copy(busy = false, error = it) },
            onAuthorised = { cipherResult ->
                lifecycleScope.launch {
                    ui.value = ui.value.copy(busy = true, error = null)
                    val result = repo.unlockWithBiometric(cipherResult)
                    ui.value = ui.value.copy(busy = false)
                    result
                        .onSuccess { showForm() }
                        .onFailure {
                            ui.value = ui.value.copy(
                                error = getString(R.string.error_biometric_unlock_failed)
                            )
                        }
                }
            },
        )
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    // -- UI -------------------------------------------------------------------------------

    @Composable
    private fun Content() {
        val state = ui.value
        val targetName = target?.displayName().orEmpty()

        AutofillDialog(onDismiss = ::cancel) {
            AutofillDialogHeader(
                title = stringResource(R.string.autofill_save_title),
                subtitle = stringResource(R.string.autofill_saving_for, targetName),
            )
            Spacer(Modifier.height(18.dp))

            when (state.stage) {
                Stage.Checking -> AutofillBusyRow()

                Stage.Password -> AutofillUnlockPanel(
                    databaseName = repo.status.value.databaseName
                        ?: stringResource(R.string.unlock_unnamed_database),
                    busy = state.busy,
                    error = state.error,
                    biometricAvailable = canUseBiometricUnlock(repo),
                    onErrorConsumed = { ui.value = ui.value.copy(error = null) },
                    onUnlock = ::unlock,
                    onBiometric = ::unlockWithBiometric,
                    onCancel = ::cancel,
                )

                Stage.NoDatabase -> AutofillMessagePanel(
                    message = stringResource(R.string.autofill_no_database_message),
                    icon = HisnIcons.Import,
                    actionLabel = stringResource(R.string.action_cancel),
                    onAction = ::cancel,
                    onCancel = ::cancel,
                )

                Stage.Form -> SaveForm(state)
            }
        }
    }

    @Composable
    private fun SaveForm(state: SaveUi) {
        val palette = HisnTheme.palette

        CredentialPreview(state.revealed) {
            ui.value = ui.value.copy(revealed = !state.revealed)
        }

        Spacer(Modifier.height(16.dp))
        ModeToggle(
            mode = state.mode,
            updateEnabled = state.candidates.isNotEmpty(),
            onSelect = { ui.value = ui.value.copy(mode = it, error = null) },
        )
        Spacer(Modifier.height(14.dp))

        when (state.mode) {
            Mode.New -> {
                HisnTextField(
                    value = state.title,
                    onValueChange = { ui.value = ui.value.copy(title = it) },
                    label = stringResource(R.string.label_title),
                    leadingIcon = HisnIcons.Notes,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                GroupPicker(
                    groups = state.groups,
                    selected = state.groupUuid,
                    onSelect = { ui.value = ui.value.copy(groupUuid = it) },
                )
            }

            Mode.Update -> {
                Text(
                    text = stringResource(R.string.autofill_save_choose_entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 184.dp)) {
                    items(state.candidates, key = { it.uuid.toString() }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            selected = candidate.uuid == state.selected,
                            onSelect = { ui.value = ui.value.copy(selected = candidate.uuid) },
                        )
                    }
                }
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = palette.weak,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = ::save,
            enabled = !state.busy && (state.mode == Mode.New || state.selected != null),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(HisnIcons.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.action_cancel),
                color = palette.muted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    /** What is about to be stored: the user name in the clear, the password behind a toggle. */
    @Composable
    private fun CredentialPreview(revealed: Boolean, onToggleReveal: () -> Unit) {
        val palette = HisnTheme.palette
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.tintedFill, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.label_username),
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
            Text(
                text = username.ifBlank { stringResource(R.string.autofill_prompt_no_username) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.label_password),
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (revealed) password else MASK.repeat(password.length.coerceAtMost(24)),
                    style = SecretTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleReveal, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (revealed) HisnIcons.Hide else HisnIcons.Reveal,
                        contentDescription = stringResource(
                            if (revealed) R.string.action_hide_password else R.string.action_reveal_password
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun ModeToggle(mode: Mode, updateEnabled: Boolean, onSelect: (Mode) -> Unit) {
        val palette = HisnTheme.palette
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
                Mode.New to R.string.autofill_save_new,
                Mode.Update to R.string.autofill_save_update,
            ).forEach { (option, label) ->
                val isSelected = option == mode
                val enabled = option == Mode.New || updateEnabled
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .height(44.dp)
                        .background(
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                else -> palette.tintedFill
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = enabled) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            enabled -> MaterialTheme.colorScheme.onSurface
                            else -> palette.muted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!updateEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.autofill_save_no_candidates),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }

    @Composable
    private fun GroupPicker(
        groups: List<GroupOption>,
        selected: UUID?,
        onSelect: (UUID) -> Unit,
    ) {
        val palette = HisnTheme.palette
        var open by remember { mutableStateOf(false) }
        val label = groups.firstOrNull { it.uuid == selected }?.label
            ?: stringResource(R.string.label_group)

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.tintedFill, RoundedCornerShape(12.dp))
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HisnIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = HisnIcons.Caret,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.label) },
                        onClick = {
                            open = false
                            onSelect(group.uuid)
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CandidateRow(candidate: Candidate, selected: Boolean, onSelect: () -> Unit) {
        val palette = HisnTheme.palette
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (candidate.username.isNotBlank()) {
                    Text(
                        text = candidate.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    companion object {
        /** The attribute prefix KeePassXC and Keepass2Android read for extra URLs. */
        private const val ADDITIONAL_URL_KEY = "KP2A_URL_"

        /**
         * One bullet per character of the stored password, capped so a very long generated
         * password cannot push the reveal button off the panel.
         */
        private const val MASK = "•"

        /**
         * The intent [HisnAutofillService] starts from `onSaveRequest`, once the platform's own
         * "save this login?" bar has been accepted.
         *
         * [password] travels as an extra on an explicit intent aimed at this component, which is
         * the mechanism the platform provides for the hand-off; it must never be logged, put in a
         * `RemoteViews`, or written anywhere but the vault.
         */
        fun intentFor(
            context: Context,
            target: AutofillTarget,
            username: String,
            password: String,
        ): Intent = Intent(context, AutofillSaveActivity::class.java).also {
            AutofillConstants.putTarget(it, target)
            it.putExtra(AutofillConstants.EXTRA_SUBMITTED_USERNAME, username)
            it.putExtra(AutofillConstants.EXTRA_SUBMITTED_PASSWORD, password)
        }
    }
}
