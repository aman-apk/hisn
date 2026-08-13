package org.hisn.app.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.SearchBar
import org.hisn.app.ui.theme.HisnTheme
import java.text.Collator
import java.util.UUID

/**
 * "بحث في حصن…" — the searchable way to fill from an entry the matcher did not offer.
 *
 * The matcher is deliberately strict: it only offers entries that name the app or site being
 * filled. That is right for an unprompted popup, and wrong as the last word — a user whose entry
 * is called "البنك" and carries no URL still has to be able to use it. This screen is that
 * escape hatch, and it is safe because the user is the one choosing: nothing is offered, and
 * nothing is filled until a row is tapped.
 *
 * Which is exactly why the header names the target. The one thing the user cannot be allowed to
 * get wrong is *who receives the password*, so "تملأ الآن لـ …" is on screen the whole time.
 */
class AutofillPickerActivity : FragmentActivity() {

    private enum class Stage { Checking, Password, List, NoDatabase }

    /** A row of the list. Deliberately holds no secret — the password is read at the tap. */
    private data class PickerRow(
        val uuid: UUID,
        val title: String,
        val username: String,
        /** True when the matcher would have offered this entry anyway; those sort first. */
        val matched: Boolean,
    )

    private data class PickerUi(
        val stage: Stage = Stage.Checking,
        val busy: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val rows: List<PickerRow> = emptyList(),
    )

    private lateinit var repo: VaultRepository
    private var target: AutofillTarget? = null
    private var usernameId: AutofillId? = null
    private var passwordId: AutofillId? = null

    private val ui: MutableState<PickerUi> = mutableStateOf(PickerUi())

    /** Arabic titles must not sort by UTF-16 code unit. */
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAutofillSecureFlag(block = true)

        repo = AutofillVault.repository(this)
        target = AutofillFlow.targetOf(intent)
        usernameId = AutofillConstants.readUsernameId(intent)
        passwordId = AutofillConstants.readPasswordId(intent)

        if (target == null || (usernameId == null && passwordId == null)) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

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
            VaultState.Unlocked -> showList()
            VaultState.Locked -> ui.value = ui.value.copy(stage = Stage.Password)
            VaultState.NoDatabase -> ui.value = ui.value.copy(stage = Stage.NoDatabase)
        }
    }

    private fun showList() {
        ui.value = ui.value.copy(stage = Stage.List, error = null)
        rebuildRows(ui.value.query)
    }

    /**
     * Rebuilds the visible list. Entries the matcher would have offered are pinned to the top so
     * the obvious choice stays the first one even while searching.
     */
    private fun rebuildRows(query: String) {
        val database = repo.database.value
        val current = target
        if (database == null || current == null) {
            ui.value = ui.value.copy(query = query, rows = emptyList())
            return
        }

        val found: List<Entry> = repo.search(query)
        val rows = found
            .map { entry ->
                PickerRow(
                    uuid = entry.uuid,
                    title = entry.title.ifBlank { entry.username }
                        .ifBlank { getString(R.string.entry_untitled) },
                    username = entry.username,
                    matched = AutofillMatcher.scoreOf(entry, current) != null,
                )
            }
            .sortedWith(
                compareByDescending<PickerRow> { it.matched }
                    .thenComparator { a, b -> collator.compare(a.title, b.title) }
            )
        ui.value = ui.value.copy(query = query, rows = rows)
    }

    /** The tap that actually hands a password over. */
    private fun fillWith(uuid: UUID) {
        val entry = repo.database.value?.findEntry(uuid)
        if (entry == null) {
            ui.value = ui.value.copy(error = getString(R.string.error_entry_missing))
            return
        }
        val dataset = DatasetBuilder.entryDataset(this, entry, usernameId, passwordId)
        if (dataset == null) {
            ui.value = ui.value.copy(error = getString(R.string.autofill_entry_has_nothing))
            return
        }
        repo.noteActivity()
        setResult(Activity.RESULT_OK, AutofillFlow.resultIntent(dataset))
        finish()
    }

    private fun unlock(password: String) {
        if (ui.value.busy) return
        lifecycleScope.launch {
            ui.value = ui.value.copy(busy = true, error = null)
            val result = repo.unlock(password, keyFileUri = null)
            ui.value = ui.value.copy(busy = false)
            result
                .onSuccess { showList() }
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
                        .onSuccess { showList() }
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

    @Composable
    private fun Content() {
        val state = ui.value
        val targetName = target?.displayName().orEmpty()

        AutofillDialog(onDismiss = ::cancel) {
            AutofillDialogHeader(
                title = stringResource(R.string.autofill_picker_title),
                subtitle = stringResource(R.string.autofill_filling_for, targetName),
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

                Stage.List -> EntryPicker(state)
            }
        }
    }

    @Composable
    private fun EntryPicker(state: PickerUi) {
        val palette = HisnTheme.palette

        SearchBar(
            query = state.query,
            onQueryChange = ::rebuildRows,
        )
        Spacer(Modifier.height(12.dp))

        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = palette.weak,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.autofill_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 288.dp)) {
                items(state.rows, key = { it.uuid.toString() }) { row ->
                    PickerEntryRow(row = row, onClick = { fillWith(row.uuid) })
                }
            }
        }

        TextButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.action_cancel),
                color = palette.muted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    @Composable
    private fun PickerEntryRow(row: PickerRow, onClick: () -> Unit) {
        val palette = HisnTheme.palette
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (row.matched) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            palette.tintedFill
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.title.trim().firstOrNull()?.toString()?.uppercase() ?: "•",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (row.matched) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.username.isNotBlank()) {
                    Text(
                        text = row.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.matched) {
                Text(
                    text = stringResource(R.string.autofill_row_suggested),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(palette.tintedFill, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }

    companion object {
        /**
         * The intent behind the "بحث في حصن…" row. `HisnAutofillService` can offer that row on
         * every request — it fills nothing by itself, so it is safe with the vault locked.
         */
        fun intentFor(
            context: Context,
            target: AutofillTarget,
            usernameIds: List<AutofillId>,
            passwordIds: List<AutofillId>,
        ): Intent = Intent(context, AutofillPickerActivity::class.java).also {
            AutofillConstants.putTarget(it, target)
            AutofillConstants.putFieldIds(it, usernameIds.firstOrNull(), passwordIds.firstOrNull())
        }
    }
}
