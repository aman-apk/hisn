package org.hisn.app.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.hisn.app.R
import org.hisn.app.data.VaultState
import org.hisn.app.sync.LocalBackup
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.EmptyState
import org.hisn.app.ui.components.FingerprintGlyph
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.PasswordField
import org.hisn.app.ui.components.ShieldLogo
import org.hisn.app.ui.theme.HisnTheme

/**
 * The face of the app: the copper shield, the vault's name, and the one field that stands
 * between the user and their secrets.
 *
 * @param onCreateVault opens the in-app creation flow — the way out for the user who has no
 *   .kdbx file anywhere and therefore nothing to import.
 */
@Composable
fun UnlockScreen(
    viewModel: VaultViewModel,
    onCreateVault: () -> Unit,
) {
    val context = LocalContext.current
    val palette = HisnTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current

    val status by viewModel.status.collectAsState()
    val error by viewModel.unlockError.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()

    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var keyFileUri by remember { mutableStateOf<Uri?>(null) }
    var keyFileName by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    val pickDatabase = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importDatabase) }

    val pickKeyFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            keyFileUri = uri
            keyFileName = context.displayNameOf(uri)
        }
    }

    val biometricAvailable = remember(biometricEnabled, status.hasBiometric) {
        biometricEnabled && status.hasBiometric && context.canUseBiometrics()
    }

    fun submit() {
        keyboard?.hide()
        viewModel.unlock(password, keyFileUri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        ShieldLogo(
            modifier = Modifier.size(132.dp),
            contentDescription = stringResource(R.string.app_name),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (status.state == VaultState.NoDatabase) {
            // Creation first, import second: the user with an existing file knows what to look
            // for, the user without one must not be left staring at a file picker.
            EmptyState(
                icon = HisnIcons.Import,
                title = stringResource(R.string.unlock_no_database_title),
                message = stringResource(R.string.unlock_no_database_message_gate),
                actionLabel = stringResource(R.string.action_create_vault),
                onAction = onCreateVault,
            )
            TextButton(
                onClick = { pickDatabase.launch(LocalBackup.OPEN_MIME_TYPES) },
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(R.string.action_open_database),
                    color = palette.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            Text(
                text = status.databaseName ?: stringResource(R.string.unlock_unnamed_database),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            PasswordField(
                value = password,
                onValueChange = {
                    password = it
                    if (error != null) viewModel.clearUnlockError()
                },
                label = stringResource(R.string.label_master_password),
                revealed = revealed,
                onRevealedChange = { revealed = it },
                enabled = !busy,
                isError = error != null,
                onImeAction = { submit() },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            KeyFileRow(
                fileName = keyFileName,
                enabled = !busy,
                onPick = { pickKeyFile.launch(arrayOf("*/*")) },
                onClear = {
                    keyFileUri = null
                    keyFileName = null
                },
            )

            val currentError = error
            if (currentError != null) {
                Spacer(Modifier.height(14.dp))
                ErrorPanel(
                    message = stringResource(currentError.message),
                    detail = currentError.detail,
                )
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(HisnIcons.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(if (busy) R.string.action_unlocking else R.string.action_unlock),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            if (biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { context.promptForBiometrics(viewModel) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    FingerprintGlyph(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.action_unlock_with_biometric))
                }
            }

            Spacer(Modifier.height(18.dp))
            TextButton(
                // Opening another database replaces the current vault, and this screen is
                // reachable without ever proving the vault's password — so confirm first.
                onClick = { confirmReplace = true },
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(R.string.action_open_other_database),
                    color = palette.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.replace_vault_title)) },
            text = { Text(stringResource(R.string.replace_vault_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        pickDatabase.launch(LocalBackup.OPEN_MIME_TYPES)
                    },
                ) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun KeyFileRow(
    fileName: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fileName == null) {
            TextButton(onClick = onPick, enabled = enabled) {
                Icon(HisnIcons.Attachment, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_choose_key_file))
            }
        } else {
            Icon(
                imageVector = HisnIcons.Attachment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, enabled = enabled) {
                Text(
                    text = stringResource(R.string.action_remove_key_file),
                    color = palette.weak,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Failures are shown in place, in rust, with whatever detail the lower layers reported. */
@Composable
private fun ErrorPanel(message: String, detail: String?) {
    val palette = HisnTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.weak.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Icon(
            imageVector = HisnIcons.Warning,
            contentDescription = null,
            tint = palette.weak,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.weak,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        }
    }
}

private fun Context.canUseBiometrics(): Boolean =
    BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Runs the system biometric prompt over the keystore Cipher that seals the master password.
 *
 * The Cipher is bound to a key that requires a fresh strong-biometric authentication for a
 * single operation, so the sealed password is only ever unwrapped by [BiometricPrompt]
 * handing back the very Cipher it authorised.
 */
private fun Context.promptForBiometrics(viewModel: VaultViewModel) {
    val activity = findFragmentActivity()
    if (activity == null) {
        viewModel.biometricFailed(null)
        return
    }
    val cipher = viewModel.biometricCipher().getOrElse { cause ->
        // Typically a new fingerprint enrolment invalidated the key; the enrolment has been
        // dropped, so the user has to re-enable biometric unlock with their password.
        viewModel.biometricFailed(cause.message)
        return
    }

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorised = result.cryptoObject?.cipher
                if (authorised == null) {
                    viewModel.biometricFailed(null)
                    return
                }
                viewModel.unlockWithBiometric(authorised)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelling is a decision, not a failure worth shouting about.
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    return
                }
                viewModel.biometricFailed(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.biometric_prompt_title))
        .setSubtitle(getString(R.string.biometric_prompt_subtitle))
        .setNegativeButtonText(getString(R.string.action_cancel))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/** The user-facing name of a picked document, for the key-file row. */
internal fun Context.displayNameOf(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
}
