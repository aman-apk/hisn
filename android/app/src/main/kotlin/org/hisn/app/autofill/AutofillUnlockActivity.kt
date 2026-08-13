package org.hisn.app.autofill

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.hisn.app.MainActivity
import org.hisn.app.R
import org.hisn.app.data.HisnSettings
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState
import org.hisn.app.kdbx.Entry
import org.hisn.app.ui.components.FingerprintGlyph
import org.hisn.app.ui.components.HisnIcons
import org.hisn.app.ui.components.PasswordField
import org.hisn.app.ui.components.ShieldLogo
import org.hisn.app.ui.theme.CopperInk
import org.hisn.app.ui.theme.HisnTheme

/**
 * The half of the autofill contract the activities own: reading back what
 * [HisnAutofillService] put in the launch intent, and handing the platform an answer.
 *
 * Everything about *which* entry may be offered still comes from [AutofillMatcher], and every row
 * the user sees is built by [DatasetBuilder]; this object only carries values between them.
 */
internal object AutofillFlow {

    /**
     * Distinct from [AutofillConstants.RC_PICK] so that opening the picker from a response we
     * built here cannot cancel the PendingIntent the service handed the live fill session.
     */
    private const val RC_PICK_AFTER_UNLOCK = 0x4202

    /**
     * The target this activity was launched for, or null when the intent did not come from the
     * service.
     *
     * [StructureParser] has already refused to believe a `webDomain` from anything but a browser,
     * so this repeats a check that should never fire. It costs a set lookup, and it means a future
     * caller that builds one of these intents by hand cannot widen a match from "this app" to
     * "this domain" — which is the one mistake in this feature that hands a bank password to a
     * game.
     */
    fun targetOf(intent: Intent?): AutofillTarget? =
        when (val target = AutofillConstants.readTarget(intent)) {
            null -> null
            is AutofillTarget.App -> target
            is AutofillTarget.Web ->
                if (BrowserRegistry.isBrowser(target.packageName)) {
                    target
                } else {
                    AutofillTarget.App(target.packageName)
                }
        }

    /** Wraps a `Dataset` or a [FillResponse] the way the framework expects it back. */
    fun resultIntent(result: Parcelable): Intent =
        Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result)

    /**
     * The answer for several matches: one row per entry, plus the way into the picker for when
     * none of them is the right one.
     *
     * This response *replaces* the one the service returned, which is why the save information is
     * rebuilt here — without it the platform would forget it was allowed to offer "save this
     * login" after the user finished typing.
     */
    fun fillResponseFor(
        context: Context,
        entries: List<Entry>,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        target: AutofillTarget,
    ): FillResponse? {
        val builder = FillResponse.Builder()
        var rows = 0
        entries.forEach { entry ->
            DatasetBuilder.entryDataset(context, entry, usernameId, passwordId)?.let {
                builder.addDataset(it)
                rows++
            }
        }
        if (rows == 0) return null

        DatasetBuilder.authDataset(
            context = context,
            usernameId = usernameId,
            passwordId = passwordId,
            title = context.getString(R.string.autofill_search_in_hisn),
            subtitle = target.displayName(),
            authentication = pickerSender(context, target, usernameId, passwordId),
        )?.let(builder::addDataset)

        saveInfo(usernameId, passwordId, target)?.let(builder::setSaveInfo)
        return builder.build()
    }

    private fun saveInfo(
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        target: AutofillTarget,
    ): SaveInfo? {
        if (passwordId == null) return null
        var type = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (usernameId != null) type = type or SaveInfo.SAVE_DATA_TYPE_USERNAME
        return SaveInfo.Builder(type, arrayOf(passwordId))
            .apply {
                usernameId?.let { setOptionalIds(arrayOf(it)) }
                // A web page swaps its content instead of finishing an activity, so the fields
                // disappearing is the only reliable "submitted" signal there.
                if (target is AutofillTarget.Web) setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            }
            .build()
    }

    /**
     * Mutable on purpose: the platform adds the assist structure and the client state to an
     * authentication intent before launching it, and from Android 12 an immutable PendingIntent is
     * refused outright.
     */
    private fun pickerSender(
        context: Context,
        target: AutofillTarget,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
    ) = PendingIntent.getActivity(
        context,
        RC_PICK_AFTER_UNLOCK,
        AutofillPickerActivity.intentFor(
            context = context,
            target = target,
            usernameIds = listOfNotNull(usernameId),
            passwordIds = listOfNotNull(passwordId),
        ),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        },
    ).intentSender
}

/**
 * "افتح حصن للملء" — the vault is locked and the user has asked for it to be opened so a field
 * can be filled.
 *
 * The activity does the matching itself and hands the framework a finished dataset or response.
 * That is deliberate: the alternative — unlock here and let the service redo the work on the next
 * request — would leave the field empty and the user tapping twice.
 */
class AutofillUnlockActivity : FragmentActivity() {

    private enum class Stage { Checking, Password, NoDatabase, NoMatches }

    private data class UnlockUi(
        val stage: Stage = Stage.Checking,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private lateinit var repo: VaultRepository
    private var target: AutofillTarget? = null
    private var usernameId: AutofillId? = null
    private var passwordId: AutofillId? = null

    private val ui: MutableState<UnlockUi> = mutableStateOf(UnlockUi())

    /** The picker is the way out when nothing in the vault matches. Its answer becomes ours. */
    private val pickEntry = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Secure before the first frame, then reconciled with the preference below — exactly as
        // MainActivity does it, so there is never a capturable moment.
        applyAutofillSecureFlag(block = true)

        repo = AutofillVault.repository(this)
        target = AutofillFlow.targetOf(intent)
        usernameId = AutofillConstants.readUsernameId(intent)
        passwordId = AutofillConstants.readPasswordId(intent)

        // No target, or nowhere to put anything: fail closed rather than guess what to fill.
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
        // Nothing watches the idle clock while the user is in someone else's app, so the timeout
        // is applied here before the vault's state is believed.
        repo.lockIfIdle()
        when (repo.status.value.state) {
            VaultState.Unlocked -> deliver()
            VaultState.Locked -> ui.value = ui.value.copy(stage = Stage.Password)
            VaultState.NoDatabase -> ui.value = ui.value.copy(stage = Stage.NoDatabase)
        }
    }

    /**
     * Turns an open vault into an answer for the framework: one match fills straight away, more
     * than one becomes a list to choose from.
     */
    private fun deliver() {
        val database = repo.database.value
        val current = target
        if (database == null || current == null) {
            ui.value = ui.value.copy(stage = Stage.Password)
            return
        }

        val matches = AutofillMatcher.match(database, current).map { it.entry }
        val answer: Parcelable? = when (matches.size) {
            0 -> null
            1 -> DatasetBuilder.entryDataset(this, matches.first(), usernameId, passwordId)
            else -> AutofillFlow.fillResponseFor(this, matches, usernameId, passwordId, current)
        }

        if (answer == null) {
            ui.value = ui.value.copy(stage = Stage.NoMatches, busy = false)
            return
        }
        repo.noteActivity()
        setResult(Activity.RESULT_OK, AutofillFlow.resultIntent(answer))
        finish()
    }

    private fun unlock(password: String) {
        if (ui.value.busy) return
        lifecycleScope.launch {
            ui.value = ui.value.copy(busy = true, error = null)
            val result = repo.unlock(password, keyFileUri = null)
            ui.value = ui.value.copy(busy = false)
            result
                .onSuccess { deliver() }
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
                        .onSuccess { deliver() }
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

    private fun openPicker() {
        val current = target ?: return
        pickEntry.launch(
            AutofillPickerActivity.intentFor(
                context = this,
                target = current,
                usernameIds = listOfNotNull(usernameId),
                passwordIds = listOfNotNull(passwordId),
            )
        )
    }

    private fun openHisn() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        cancel()
    }

    @Composable
    private fun Content() {
        val state = ui.value
        val targetName = target?.displayName().orEmpty()

        AutofillDialog(onDismiss = ::cancel) {
            AutofillDialogHeader(
                title = stringResource(R.string.autofill_unlock_title),
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
                    actionLabel = stringResource(R.string.autofill_open_hisn),
                    onAction = ::openHisn,
                    onCancel = ::cancel,
                )

                Stage.NoMatches -> AutofillMessagePanel(
                    message = stringResource(R.string.autofill_no_matches_message, targetName),
                    icon = HisnIcons.Search,
                    actionLabel = stringResource(R.string.autofill_search_in_hisn),
                    onAction = ::openPicker,
                    onCancel = ::cancel,
                )
            }
        }
    }

    companion object {
        /**
         * The intent [HisnAutofillService] wraps in the `IntentSender` of the authentication
         * dataset it returns while the vault is locked.
         *
         * The lists exist because a screen can carry more than one field of a kind (a password
         * and its confirmation); only the first of each is used today, and widening that is a
         * change here rather than at every call site.
         */
        fun intentFor(
            context: Context,
            target: AutofillTarget,
            usernameIds: List<AutofillId>,
            passwordIds: List<AutofillId>,
        ): Intent = Intent(context, AutofillUnlockActivity::class.java).also {
            AutofillConstants.putTarget(it, target)
            AutofillConstants.putFieldIds(it, usernameIds.firstOrNull(), passwordIds.firstOrNull())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces of the three autofill dialogs.
// ---------------------------------------------------------------------------------------------

/**
 * FLAG_SECURE, reconciled with [HisnSettings.blockScreenshots] the same way MainActivity does it.
 * These windows show passwords, so the default while nothing is known yet is "secure".
 */
internal fun Activity.applyAutofillSecureFlag(block: Boolean) {
    if (block) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

internal fun Context.canUseBiometricUnlock(repo: VaultRepository): Boolean =
    repo.secureStore.isEnrolled &&
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Runs the system prompt over the Keystore Cipher that seals the master password and hands the
 * unwrapped bytes to [onAuthorised], which must consume them (the repository wipes them).
 */
internal fun FragmentActivity.promptForBiometricUnlock(
    repo: VaultRepository,
    onFailure: (String?) -> Unit,
    onAuthorised: (ByteArray) -> Unit,
) {
    val cipher = repo.secureStore.decryptCipher().getOrElse {
        // Typically a new fingerprint enrolment invalidated the key, in which case the enrolment
        // has just been dropped and the user has to switch biometric unlock on again in the app.
        onFailure(getString(R.string.error_biometric_unlock_failed))
        return
    }

    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorised = result.cryptoObject?.cipher
                if (authorised == null) {
                    onFailure(getString(R.string.error_biometric_unlock_failed))
                    return
                }
                repo.secureStore.unwrap(authorised)
                    .onSuccess(onAuthorised)
                    .onFailure { onFailure(getString(R.string.error_biometric_unlock_failed)) }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelling is a decision, not a failure worth shouting about.
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    onFailure(null)
                    return
                }
                onFailure(getString(R.string.error_biometric_unlock_failed))
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

/**
 * The card every autofill screen sits in: a scrim over the app that asked, and a copper panel on
 * top of it. Tapping the scrim cancels, which is what a user expects from something that appeared
 * over their login form.
 */
@Composable
internal fun AutofillDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = HisnTheme.palette
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CopperInk.copy(alpha = 0.72f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            )
            .safeDrawingPadding()
            .imePadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                // Swallows taps that land on the panel, so only the scrim dismisses.
                .clickable(interactionSource = cardInteraction, indication = null) { },
            shape = RoundedCornerShape(24.dp),
            color = palette.raised,
        ) {
            Column(modifier = Modifier.padding(22.dp), content = content)
        }
    }
}

/**
 * The shield, what is happening, and — always — which app or site the credentials belong to.
 *
 * The subtitle is not decoration. Everything in this flow ends with a password leaving the vault
 * for another app, and the only defence against filling the wrong one is that the user can read
 * where it is going before they commit.
 */
@Composable
internal fun AutofillDialogHeader(title: String, subtitle: String) {
    val palette = HisnTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShieldLogo(modifier = Modifier.size(42.dp), withPlate = false)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AutofillBusyRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

/** Password + fingerprint, in the compact form the fill flow has room for. */
@Composable
internal fun AutofillUnlockPanel(
    databaseName: String,
    busy: Boolean,
    error: String?,
    biometricAvailable: Boolean,
    onErrorConsumed: () -> Unit,
    onUnlock: (String) -> Unit,
    onBiometric: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = HisnTheme.palette
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    Text(
        text = databaseName,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))

    PasswordField(
        value = password,
        onValueChange = {
            password = it
            if (error != null) onErrorConsumed()
        },
        label = stringResource(R.string.label_master_password),
        revealed = revealed,
        onRevealedChange = { revealed = it },
        enabled = !busy,
        isError = error != null,
        onImeAction = { if (!busy) onUnlock(password) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (error != null) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = palette.weak,
        )
    }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { onUnlock(password) },
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(HisnIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(if (busy) R.string.action_unlocking else R.string.action_unlock),
            style = MaterialTheme.typography.titleSmall,
        )
    }

    if (biometricAvailable) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onBiometric,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
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

    Spacer(Modifier.height(4.dp))
    TextButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.action_cancel),
            color = palette.muted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A dead end with one way forward: nothing matched, or there is no vault yet. */
@Composable
internal fun AutofillMessagePanel(
    message: String,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = HisnTheme.palette
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
        textAlign = TextAlign.Start,
    )
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = onAction,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(actionLabel, style = MaterialTheme.typography.titleSmall)
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.action_cancel),
            color = palette.muted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
