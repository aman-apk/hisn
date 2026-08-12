package org.hisn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.CustomFieldUi
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.HisnTextField
import org.hisn.app.ui.components.PasswordField
import org.hisn.app.ui.components.PasswordGeneratorSheet
import org.hisn.app.ui.components.StrengthBar
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    viewModel: VaultViewModel,
    entryUuid: UUID?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = HisnTheme.palette
    val groups by viewModel.groups.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var draft by remember(entryUuid) { mutableStateOf(viewModel.draftFor(entryUuid)) }
    var revealPassword by remember { mutableStateOf(entryUuid == null) }
    var showGenerator by remember { mutableStateOf(false) }
    var groupMenuOpen by remember { mutableStateOf(false) }

    val duplicateKeys = remember(draft.customFields) {
        draft.customFields
            .map { it.key.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val titleValid = draft.title.isNotBlank()
    val canSave = titleValid && duplicateKeys.isEmpty() && !busy

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (entryUuid == null) R.string.edit_title_new else R.string.edit_title_existing
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(HisnIcons.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.saveEntry(draft, onDone) },
                        enabled = canSave,
                    ) {
                        Text(
                            text = stringResource(R.string.action_save),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HisnTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                label = stringResource(R.string.label_title),
                leadingIcon = HisnIcons.Shield,
                isError = !titleValid,
                supportingText = if (titleValid) null else stringResource(R.string.edit_title_required),
                modifier = Modifier.fillMaxWidth(),
            )

            HisnTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = stringResource(R.string.label_username),
                leadingIcon = HisnIcons.Person,
                textStyle = SecretTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )

            PasswordField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = stringResource(R.string.label_password),
                revealed = revealPassword,
                onRevealedChange = { revealPassword = it },
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth(),
                extraActions = {
                    IconButton(onClick = { showGenerator = true }) {
                        Icon(
                            imageVector = HisnIcons.Regenerate,
                            contentDescription = stringResource(R.string.action_open_generator),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            StrengthBar(password = draft.password)

            HisnTextField(
                value = draft.url,
                onValueChange = { draft = draft.copy(url = it) },
                label = stringResource(R.string.label_url),
                leadingIcon = HisnIcons.Link,
                keyboardType = KeyboardType.Uri,
                textStyle = SecretTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )

            HisnTextField(
                value = draft.totp,
                onValueChange = { draft = draft.copy(totp = it) },
                label = stringResource(R.string.label_totp_secret),
                leadingIcon = HisnIcons.Timer,
                supportingText = stringResource(R.string.edit_totp_hint),
                textStyle = SecretTextStyle,
                modifier = Modifier.fillMaxWidth(),
            )

            HisnTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = stringResource(R.string.label_notes),
                leadingIcon = HisnIcons.Notes,
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default,
                modifier = Modifier.fillMaxWidth(),
            )

            if (entryUuid == null) {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, palette.outline, RoundedCornerShape(12.dp))
                            .clickable(role = Role.DropdownList) { groupMenuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HisnIcons.Folder,
                            contentDescription = null,
                            tint = palette.muted,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.label_group),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.muted,
                            )
                            Text(
                                text = groups.firstOrNull { it.uuid == draft.groupUuid }?.name
                                    ?: draft.groupName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Icon(
                            imageVector = HisnIcons.Caret,
                            contentDescription = null,
                            tint = palette.muted,
                        )
                    }
                    DropdownMenu(
                        expanded = groupMenuOpen,
                        onDismissRequest = { groupMenuOpen = false },
                    ) {
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.path) },
                                onClick = {
                                    draft = draft.copy(groupUuid = group.uuid, groupName = group.name)
                                    groupMenuOpen = false
                                },
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.edit_group_fixed, draft.groupName),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.section_custom_fields),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            draft.customFields.forEachIndexed { index, field ->
                CustomFieldEditor(
                    field = field,
                    duplicate = field.key.trim().isNotEmpty() && field.key.trim() in duplicateKeys,
                    onChange = { updated ->
                        draft = draft.copy(
                            customFields = draft.customFields.toMutableList().also { it[index] = updated }
                        )
                    },
                    onRemove = {
                        draft = draft.copy(
                            customFields = draft.customFields.filterIndexed { i, _ -> i != index }
                        )
                    },
                )
            }

            TextButton(
                onClick = {
                    draft = draft.copy(
                        customFields = draft.customFields + CustomFieldUi("", "", protected = false)
                    )
                }
            ) {
                Icon(HisnIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_add_field))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { viewModel.saveEntry(draft, onDone) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showGenerator) {
        PasswordGeneratorSheet(
            onDismiss = { showGenerator = false },
            onAccept = { generated ->
                draft = draft.copy(password = generated)
                revealPassword = true
                showGenerator = false
            },
        )
    }
}

@Composable
private fun CustomFieldEditor(
    field: CustomFieldUi,
    duplicate: Boolean,
    onChange: (CustomFieldUi) -> Unit,
    onRemove: () -> Unit,
) {
    val palette = HisnTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.raised, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HisnTextField(
                value = field.key,
                onValueChange = { onChange(field.copy(key = it)) },
                label = stringResource(R.string.label_field_name),
                isError = duplicate,
                supportingText = if (duplicate) stringResource(R.string.edit_field_duplicate) else null,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = HisnIcons.Delete,
                    contentDescription = stringResource(R.string.action_remove_field),
                    tint = palette.weak,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HisnTextField(
            value = field.value,
            onValueChange = { onChange(field.copy(value = it)) },
            label = stringResource(R.string.label_field_value),
            textStyle = SecretTextStyle,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.label_field_protected),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = field.protected,
                onCheckedChange = { onChange(field.copy(protected = it)) },
            )
        }
    }
}
