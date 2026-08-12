package org.hisn.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.AUTO_LOCK_CHOICES
import org.hisn.app.ui.CLIPBOARD_CHOICES
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.PasswordField
import org.hisn.app.ui.components.ShieldLogo
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val palette = HisnTheme.palette
    val settings by viewModel.settings.collectAsState()
    val status by viewModel.status.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()

    var askForMasterPassword by remember { mutableStateOf(false) }

    val deviceSupportsBiometrics = remember {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(viewModel::exportBackup) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            SectionLabel(stringResource(R.string.settings_section_security))

            ChoiceRow(
                icon = HisnIcons.Lock,
                title = stringResource(R.string.settings_auto_lock),
                subtitle = stringResource(R.string.settings_auto_lock_hint),
                value = stringResource(autoLockLabel(settings.autoLockMinutes)),
                options = AUTO_LOCK_CHOICES.map { it to stringResource(autoLockLabel(it)) },
                onSelect = viewModel::setAutoLockMinutes,
            )

            ChoiceRow(
                icon = HisnIcons.Timer,
                title = stringResource(R.string.settings_clipboard),
                subtitle = stringResource(R.string.settings_clipboard_hint),
                value = stringResource(clipboardLabel(settings.clipboardSeconds)),
                options = CLIPBOARD_CHOICES.map { it to stringResource(clipboardLabel(it)) },
                onSelect = viewModel::setClipboardSeconds,
            )

            SettingRow(
                icon = HisnIcons.Key,
                title = stringResource(R.string.settings_biometric),
                subtitle = when {
                    !deviceSupportsBiometrics -> stringResource(R.string.settings_biometric_unavailable)
                    biometricEnabled -> stringResource(R.string.settings_biometric_on)
                    else -> stringResource(R.string.settings_biometric_off)
                },
                trailing = {
                    Switch(
                        checked = biometricEnabled,
                        // Turning it back off means deleting the wrapped key, which only the
                        // repository can do; until it exposes that, the switch is one-way.
                        enabled = deviceSupportsBiometrics && !biometricEnabled,
                        onCheckedChange = { wanted -> if (wanted) askForMasterPassword = true },
                    )
                },
            )

            SectionLabel(stringResource(R.string.settings_section_appearance))
            ThemePicker(
                selected = settings.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            SectionLabel(stringResource(R.string.settings_section_backup))
            SettingRow(
                icon = HisnIcons.Export,
                title = stringResource(R.string.settings_export),
                subtitle = stringResource(R.string.settings_export_hint),
                onClick = {
                    exportBackup.launch(suggestedBackupName(status.databaseName))
                },
            )

            SectionLabel(stringResource(R.string.settings_section_about))
            AboutCard(versionName = remember { context.versionName() })

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.about_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (askForMasterPassword) {
        MasterPasswordDialog(
            onDismiss = { askForMasterPassword = false },
            onConfirm = { password ->
                askForMasterPassword = false
                viewModel.enableBiometricUnlock(password)
            },
        )
    }
}

@Composable
private fun MasterPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HisnIcons.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.biometric_enable_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.biometric_enable_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.label_master_password),
                    revealed = revealed,
                    onRevealedChange = { revealed = it },
                    onImeAction = { if (password.isNotEmpty()) onConfirm(password) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(46.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else palette.tintedFill,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(themeLabel(mode)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutCard(versionName: String) {
    val palette = HisnTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.raised, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldLogo(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.about_version, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = palette.outline, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_credit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
    }
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

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        trailing?.invoke()
    }
}

/** A setting whose value is picked from a short, fixed list. */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = HisnIcons.Caret,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(optionValue)
                    },
                )
            }
        }
    }
}

private fun autoLockLabel(minutes: Int): Int = when (minutes) {
    0 -> R.string.autolock_immediately
    1 -> R.string.autolock_1
    5 -> R.string.autolock_5
    15 -> R.string.autolock_15
    30 -> R.string.autolock_30
    else -> R.string.autolock_never
}

private fun clipboardLabel(seconds: Int): Int = when (seconds) {
    10 -> R.string.clipboard_10
    30 -> R.string.clipboard_30
    60 -> R.string.clipboard_60
    120 -> R.string.clipboard_120
    else -> R.string.clipboard_never
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

private fun suggestedBackupName(databaseName: String?): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val base = databaseName?.takeIf { it.isNotBlank() }?.replace(Regex("[\\\\/:*?\"<>|]"), "-") ?: "hisn"
    return "$base-$stamp.kdbx"
}

private fun Context.versionName(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
} catch (_: PackageManager.NameNotFoundException) {
    // The package always exists at runtime; treat an impossible failure as "unknown".
    "—"
}
