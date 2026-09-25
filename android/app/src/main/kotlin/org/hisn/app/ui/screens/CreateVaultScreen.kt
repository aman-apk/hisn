package org.hisn.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.HisnTextField
import org.hisn.app.ui.components.PasswordField
import org.hisn.app.ui.components.StrengthBar
import org.hisn.app.ui.theme.HisnTheme

/**
 * Creates the vault on the device itself, for the user with no .kdbx to import: a name, a
 * master passphrase entered twice, one button. The repository writes a fresh KDBX4 file and
 * opens it in the same motion, so success lands directly in the entry list.
 *
 * Field state is deliberately plain [remember], never saveable: a passphrase must not be
 * serialised into the saved-instance bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVaultScreen(
    viewModel: VaultViewModel,
    onCreated: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = HisnTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val busy by viewModel.busy.collectAsState()

    val defaultName = stringResource(R.string.create_name_default)
    var name by remember { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordRevealed by remember { mutableStateOf(false) }
    var confirmRevealed by remember { mutableStateOf(false) }

    /** Empty-field errors only appear once the user has tried; the mismatch appears as typed. */
    var attempted by remember { mutableStateOf(false) }
    val nameMissing = attempted && name.isBlank()
    val passwordMissing = attempted && password.isEmpty()
    val mismatch = (attempted || confirm.isNotEmpty()) && confirm != password

    fun submit() {
        attempted = true
        if (name.isBlank() || password.isEmpty() || confirm != password) return
        keyboard?.hide()
        viewModel.createVault(name.trim(), password, onCreated)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.create_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )

            Spacer(Modifier.height(24.dp))
            HisnTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.create_name_label),
                enabled = !busy,
                isError = nameMissing,
                supportingText = if (nameMissing) stringResource(R.string.create_name_empty) else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.label_master_password),
                revealed = passwordRevealed,
                onRevealedChange = { passwordRevealed = it },
                enabled = !busy,
                isError = passwordMissing,
                supportingText = if (passwordMissing) stringResource(R.string.create_password_empty) else null,
                imeAction = ImeAction.Next,
                onImeAction = { focus.moveFocus(FocusDirection.Down) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            StrengthBar(password = password)

            Spacer(Modifier.height(16.dp))
            PasswordField(
                value = confirm,
                onValueChange = { confirm = it },
                label = stringResource(R.string.create_confirm_label),
                revealed = confirmRevealed,
                onRevealedChange = { confirmRevealed = it },
                enabled = !busy,
                isError = mismatch,
                supportingText = if (mismatch) stringResource(R.string.create_mismatch) else null,
                onImeAction = { submit() },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.create_password_note),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = stringResource(if (busy) R.string.create_busy else R.string.create_button),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}
