package org.hisn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.EntryCard
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.EmptyState
import org.hisn.app.ui.components.EntryRow
import org.hisn.app.ui.components.GroupChip
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.SearchBar
import org.hisn.app.ui.components.ShieldLogo
import org.hisn.app.ui.components.TotpDialog
import org.hisn.app.ui.theme.HisnTheme
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryListScreen(
    viewModel: VaultViewModel,
    onOpenEntry: (UUID) -> Unit,
    onCreateEntry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val palette = HisnTheme.palette
    val sections by viewModel.sections.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val query by viewModel.query.collectAsState()
    val groupFilter by viewModel.groupFilter.collectAsState()
    val totalEntries by viewModel.entryCount.collectAsState()

    var totpDialogFor by remember { mutableStateOf<EntryCard?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShieldLogo(
                            modifier = Modifier.size(28.dp),
                            withPlate = false,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSync) {
                        Icon(HisnIcons.Sync, contentDescription = stringResource(R.string.action_sync))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(HisnIcons.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(
                            imageVector = HisnIcons.Lock,
                            contentDescription = stringResource(R.string.action_lock),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = palette.muted,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateEntry,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(HisnIcons.Add, contentDescription = stringResource(R.string.action_add_entry))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(
                query = query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (groups.size > 1) {
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        GroupChip(
                            label = stringResource(R.string.group_all),
                            count = totalEntries,
                            selected = groupFilter == null,
                            onClick = { viewModel.setGroupFilter(null) },
                        )
                    }
                    items(groups.filter { it.count > 0 }, key = { it.uuid.toString() }) { group ->
                        GroupChip(
                            label = group.name,
                            count = group.count,
                            selected = groupFilter == group.uuid,
                            onClick = {
                                viewModel.setGroupFilter(if (groupFilter == group.uuid) null else group.uuid)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                sections.isEmpty() && query.isNotBlank() -> EmptyState(
                    icon = HisnIcons.Search,
                    title = stringResource(R.string.list_no_results_title),
                    message = stringResource(R.string.list_no_results_message, query),
                )

                sections.isEmpty() && groupFilter != null -> EmptyState(
                    icon = HisnIcons.Folder,
                    title = stringResource(R.string.list_group_empty_title),
                    message = stringResource(R.string.list_group_empty_message),
                )

                sections.isEmpty() -> EmptyState(
                    icon = HisnIcons.Key,
                    title = stringResource(R.string.list_empty_title),
                    message = stringResource(R.string.list_empty_message),
                    actionLabel = stringResource(R.string.action_add_entry),
                    onAction = onCreateEntry,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    sections.forEach { section ->
                        if (section.title != null) {
                            item(key = "header-${section.title}") {
                                SectionHeader(section.title, section.entries.size)
                            }
                        }
                        items(section.entries, key = { it.uuid.toString() }) { card ->
                            EntryRow(
                                entry = card,
                                onClick = { onOpenEntry(card.uuid) },
                                onCopyUsername = { viewModel.copyUsername(card.uuid) },
                                onCopyPassword = { viewModel.copyPassword(card.uuid) },
                                onShowTotp = { totpDialogFor = card },
                                onCopyTotp = { viewModel.copyTotp(card.uuid) },
                            )
                        }
                    }
                }
            }
        }
    }

    val dialogCard = totpDialogFor
    val dialogTotp = dialogCard?.totp
    if (dialogCard != null && dialogTotp != null) {
        TotpDialog(
            entryTitle = dialogCard.title.ifBlank { stringResource(R.string.entry_untitled) },
            settings = dialogTotp,
            onCopy = {
                viewModel.copyTotp(dialogCard.uuid)
                totpDialogFor = null
            },
            onDismiss = { totpDialogFor = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val palette = HisnTheme.palette
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HisnIcons.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
        }
        HorizontalDivider(color = palette.outline, thickness = 1.dp)
    }
}
