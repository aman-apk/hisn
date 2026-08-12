package org.hisn.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.sync.PairedDevice
import org.hisn.app.sync.SyncProgress
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.CopperProgressBar
import org.hisn.app.ui.components.EmptyState
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.HisnTextField
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle

/**
 * Device-to-device synchronisation.
 *
 * Pairing is by code rather than by camera: a QR scanner would pull in CameraX plus a
 * decoder for a one-off action, and the desktop app already shows the pairing payload as
 * text next to its QR code. The screen says so plainly instead of hiding a missing scanner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val palette = HisnTheme.palette
    val devices by viewModel.pairedDevices.collectAsState()
    val progress by viewModel.syncProgress.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.status.collectAsState()

    var showPairDialog by remember { mutableStateOf(false) }
    var deviceToForget by remember { mutableStateOf<PairedDevice?>(null) }
    var confirmImport by remember { mutableStateOf(false) }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(viewModel::exportBackup) }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importDatabase) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HisnIcons.Back, contentDescription = stringResource(R.string.action_back))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            ProgressPanel(progress)

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showPairDialog = true },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(HisnIcons.QrCode, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sync_pair_action))
            }
            Text(
                text = stringResource(R.string.sync_pair_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )

            SectionLabel(stringResource(R.string.sync_section_devices))
            if (devices.isEmpty()) {
                EmptyState(
                    icon = HisnIcons.Devices,
                    title = stringResource(R.string.sync_no_devices_title),
                    message = stringResource(R.string.sync_no_devices_message),
                )
            } else {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        enabled = !busy,
                        onSync = { viewModel.syncWith(device) },
                        onForget = { deviceToForget = device },
                    )
                }
            }

            SectionLabel(stringResource(R.string.sync_section_local))
            Text(
                text = stringResource(R.string.sync_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { exportBackup.launch(backupFileName(status.databaseName)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HisnIcons.Export, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_export))
                }
                OutlinedButton(
                    onClick = { confirmImport = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HisnIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_import))
                }
            }
        }
    }

    if (showPairDialog) {
        PairDialog(
            onDismiss = { showPairDialog = false },
            onPaste = { context.clipboardText() },
            onConfirm = { payload ->
                showPairDialog = false
                viewModel.pairFromCode(payload)
            },
        )
    }

    val forgetTarget = deviceToForget
    if (forgetTarget != null) {
        AlertDialog(
            onDismissRequest = { deviceToForget = null },
            icon = { Icon(HisnIcons.Delete, contentDescription = null, tint = palette.weak) },
            title = { Text(stringResource(R.string.sync_forget_title)) },
            text = { Text(stringResource(R.string.sync_forget_message, forgetTarget.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deviceToForget = null
                    viewModel.forgetDevice(forgetTarget)
                }) {
                    Text(stringResource(R.string.action_forget), color = palette.weak)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToForget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            icon = { Icon(HisnIcons.Warning, contentDescription = null, tint = palette.weak) },
            title = { Text(stringResource(R.string.sync_import_title)) },
            text = { Text(stringResource(R.string.sync_import_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importBackup.launch(arrayOf("*/*"))
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProgressPanel(progress: SyncProgress) {
    val palette = HisnTheme.palette
    if (progress is SyncProgress.Idle) return

    val fraction: Float? = when (progress) {
        is SyncProgress.Transferring ->
            if (progress.bytesTotal > 0) {
                (progress.bytesDone.toFloat() / progress.bytesTotal.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }

        is SyncProgress.Done -> 1f
        is SyncProgress.Failed -> 1f
        else -> null
    }

    val message = when (progress) {
        is SyncProgress.Connecting -> stringResource(R.string.sync_state_connecting, progress.host)
        is SyncProgress.Handshaking -> stringResource(R.string.sync_state_handshaking)
        is SyncProgress.Transferring -> stringResource(R.string.sync_state_transferring)
        is SyncProgress.Merging -> stringResource(R.string.sync_state_merging, progress.changes.toString())
        is SyncProgress.Done -> stringResource(
            R.string.sync_state_done,
            progress.added.toString(),
            progress.updated.toString(),
            progress.deleted.toString(),
        )

        is SyncProgress.Failed -> stringResource(R.string.sync_state_failed)
        else -> ""
    }

    val accent = when (progress) {
        is SyncProgress.Failed -> palette.weak
        is SyncProgress.Done -> palette.strong
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(palette.raised, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (progress is SyncProgress.Failed) HisnIcons.Warning else HisnIcons.Sync,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                modifier = Modifier.weight(1f),
            )
        }
        if (progress is SyncProgress.Failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = progress.message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        CopperProgressBar(progress = fraction)
    }
}

@Composable
private fun DeviceRow(
    device: PairedDevice,
    enabled: Boolean,
    onSync: () -> Unit,
    onForget: () -> Unit,
) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(palette.raised, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HisnIcons.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${device.host}:${device.port}",
                style = MaterialTheme.typography.bodySmall.copy(
                    textDirection = SecretTextStyle.textDirection,
                ),
                color = palette.muted,
            )
            Text(
                text = stringResource(R.string.sync_fingerprint, device.fingerprint),
                style = MaterialTheme.typography.labelSmall.copy(
                    textDirection = SecretTextStyle.textDirection,
                ),
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSync, enabled = enabled) {
            Icon(
                imageVector = HisnIcons.Sync,
                contentDescription = stringResource(R.string.action_sync_now),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onForget, enabled = enabled) {
            Icon(
                imageVector = HisnIcons.Delete,
                contentDescription = stringResource(R.string.action_forget),
                tint = palette.muted,
            )
        }
    }
}

@Composable
private fun PairDialog(
    onDismiss: () -> Unit,
    onPaste: () -> String?,
    onConfirm: (String) -> Unit,
) {
    var payload by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HisnIcons.QrCode, contentDescription = null) },
        title = { Text(stringResource(R.string.sync_pair_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_pair_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                HisnTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = stringResource(R.string.label_pairing_code),
                    singleLine = false,
                    minLines = 2,
                    textStyle = SecretTextStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onPaste()?.let { payload = it } }) {
                    Icon(HisnIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_paste_from_clipboard))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(payload) },
                enabled = payload.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_pair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

private fun backupFileName(databaseName: String?): String {
    val base = databaseName?.takeIf { it.isNotBlank() }?.replace(Regex("[\\\\/:*?\"<>|]"), "-") ?: "hisn"
    return "$base-backup.kdbx"
}

/** Reads the pairing payload the user copied from the desktop app. */
private fun Context.clipboardText(): String? {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
}
