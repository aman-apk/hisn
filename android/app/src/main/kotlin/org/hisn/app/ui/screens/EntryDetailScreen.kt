package org.hisn.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.kdbx.TotpSettings
import org.hisn.app.ui.EntryDetail
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.EmptyState
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.TotpCountdown
import org.hisn.app.ui.components.formatTotpCode
import org.hisn.app.ui.components.rememberEpochSeconds
import org.hisn.app.ui.components.rememberTotpCode
import org.hisn.app.ui.theme.CodeTextStyle
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    viewModel: VaultViewModel,
    entryUuid: UUID,
    onBack: () -> Unit,
    onEdit: (UUID) -> Unit,
) {
    val palette = HisnTheme.palette
    val detailFlow = remember(entryUuid) { viewModel.detailOf(entryUuid) }
    val detail by detailFlow.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail?.title?.ifBlank { stringResource(R.string.entry_untitled) }
                            ?: stringResource(R.string.entry_details),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HisnIcons.Back, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (detail != null) {
                        IconButton(onClick = { onEdit(entryUuid) }) {
                            Icon(HisnIcons.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = HisnIcons.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = palette.weak,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = palette.muted,
                ),
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            EmptyState(
                icon = HisnIcons.Warning,
                title = stringResource(R.string.entry_missing_title),
                message = stringResource(R.string.entry_missing_message),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
        } else {
            DetailBody(
                detail = current,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(HisnIcons.Delete, contentDescription = null, tint = palette.weak) },
            title = { Text(stringResource(R.string.delete_entry_title)) },
            text = { Text(stringResource(R.string.delete_entry_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteEntry(entryUuid, onBack)
                }) {
                    Text(stringResource(R.string.action_delete), color = palette.weak)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailBody(
    detail: EntryDetail,
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier,
) {
    val palette = HisnTheme.palette
    val context = LocalContext.current
    var passwordRevealed by remember(detail.uuid) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Header(detail)

        Spacer(Modifier.height(20.dp))

        if (detail.username.isNotBlank()) {
            DetailField(
                icon = HisnIcons.Person,
                label = stringResource(R.string.label_username),
                value = detail.username,
                monospace = true,
                onCopy = { viewModel.copyUsername(detail.uuid) },
            )
        }

        if (detail.password.isNotBlank()) {
            DetailField(
                icon = HisnIcons.Key,
                label = stringResource(R.string.label_password),
                value = detail.password,
                monospace = true,
                masked = !passwordRevealed,
                onCopy = { viewModel.copyPassword(detail.uuid) },
                extraAction = {
                    IconButton(onClick = { passwordRevealed = !passwordRevealed }) {
                        Icon(
                            imageVector = if (passwordRevealed) HisnIcons.Hide else HisnIcons.Reveal,
                            contentDescription = stringResource(
                                if (passwordRevealed) R.string.action_hide_password
                                else R.string.action_reveal_password
                            ),
                            tint = palette.muted,
                        )
                    }
                },
            )
        }

        if (detail.url.isNotBlank()) {
            DetailField(
                icon = HisnIcons.Link,
                label = stringResource(R.string.label_url),
                value = detail.url,
                monospace = true,
                onCopy = {
                    viewModel.copyValue(detail.url, R.string.msg_url_copied, sensitive = false)
                },
                extraAction = {
                    IconButton(onClick = { context.openUrl(detail.url, viewModel) }) {
                        Icon(
                            imageVector = HisnIcons.Link,
                            contentDescription = stringResource(R.string.action_open_url),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }

        val totp = detail.totp
        if (totp != null) {
            Spacer(Modifier.height(4.dp))
            TotpPanel(
                onCopy = { viewModel.copyTotp(detail.uuid) },
                settings = totp,
            )
        } else if (detail.hasTotpField) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.totp_unreadable_explained),
                style = MaterialTheme.typography.bodySmall,
                color = palette.weak,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        if (detail.customFields.isNotEmpty()) {
            SectionTitle(stringResource(R.string.section_custom_fields))
            detail.customFields.forEach { field ->
                DetailField(
                    icon = HisnIcons.Notes,
                    label = field.key,
                    value = field.value,
                    monospace = field.protected,
                    masked = field.protected,
                    revealable = field.protected,
                    onCopy = {
                        viewModel.copyValue(
                            field.value,
                            R.string.msg_field_copied,
                            sensitive = field.protected,
                        )
                    },
                )
            }
        }

        if (detail.notes.isNotBlank()) {
            SectionTitle(stringResource(R.string.label_notes))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.raised, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = detail.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    viewModel.copyValue(detail.notes, R.string.msg_notes_copied, sensitive = false)
                }
            ) {
                Icon(HisnIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_copy_notes))
            }
        }

        if (detail.attachments.isNotEmpty()) {
            SectionTitle(stringResource(R.string.section_attachments))
            detail.attachments.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HisnIcons.Attachment,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = stringResource(R.string.attachments_desktop_only),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }

        SectionTitle(stringResource(R.string.section_metadata))
        MetaRow(stringResource(R.string.meta_group), detail.groupName)
        MetaRow(stringResource(R.string.meta_created), formatTimestamp(detail.created))
        MetaRow(stringResource(R.string.meta_modified), formatTimestamp(detail.modified))
        detail.expiresAt?.let { expiry ->
            MetaRow(
                label = stringResource(R.string.meta_expires),
                value = formatTimestamp(expiry),
                valueColor = if (detail.expired) palette.weak else null,
            )
        }
    }
}

@Composable
private fun Header(detail: EntryDetail) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(palette.tintedFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HisnIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.title.ifBlank { stringResource(R.string.entry_untitled) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = detail.groupName,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        if (detail.expired) {
            Text(
                text = stringResource(R.string.entry_expired),
                style = MaterialTheme.typography.labelMedium,
                color = palette.weak,
            )
        }
    }
}

/** Live one-time code with the countdown ring beside it. */
@Composable
private fun TotpPanel(
    settings: TotpSettings,
    onCopy: () -> Unit,
) {
    val palette = HisnTheme.palette
    val now = rememberEpochSeconds()
    val code = rememberTotpCode(settings, now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.tintedFill, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TotpCountdown(settings = settings, epochSeconds = now)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.label_totp),
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
            )
            Text(
                text = code?.let(::formatTotpCode) ?: stringResource(R.string.totp_unreadable),
                style = CodeTextStyle,
                color = if (code != null) MaterialTheme.colorScheme.primary else palette.weak,
            )
        }
        IconButton(onClick = onCopy, enabled = code != null) {
            Icon(
                imageVector = HisnIcons.Copy,
                contentDescription = stringResource(R.string.action_copy_totp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DetailField(
    icon: ImageVector,
    label: String,
    value: String,
    onCopy: () -> Unit,
    monospace: Boolean = false,
    masked: Boolean = false,
    revealable: Boolean = false,
    extraAction: @Composable (() -> Unit)? = null,
) {
    val palette = HisnTheme.palette
    var revealed by remember(label, value) { mutableStateOf(false) }
    val hidden = masked && !(revealable && revealed)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                )
                Text(
                    text = if (hidden) MASK else value,
                    style = if (monospace) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (revealable) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) HisnIcons.Hide else HisnIcons.Reveal,
                        contentDescription = stringResource(
                            if (revealed) R.string.action_hide_password else R.string.action_reveal_password
                        ),
                        tint = palette.muted,
                    )
                }
            }
            extraAction?.invoke()
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = HisnIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = palette.outline, thickness = 1.dp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun MetaRow(label: String, value: String, valueColor: Color? = null) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val MASK = "••••••••••"

private fun formatTimestamp(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochSeconds * 1000L))

/**
 * Opens a stored URL. Entries frequently store a bare host, which has no scheme and would
 * be treated as a relative path, so one is added before handing it to the system.
 */
private fun Context.openUrl(url: String, viewModel: VaultViewModel) {
    val normalized = if (url.contains("://")) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        viewModel.reportFailure(R.string.error_no_browser, e.message)
    }
}
