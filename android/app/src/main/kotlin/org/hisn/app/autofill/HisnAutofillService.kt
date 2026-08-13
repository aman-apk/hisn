package org.hisn.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.hisn.app.R
import org.hisn.app.data.Prefs
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState

/**
 * Hisn's Android Autofill service.
 *
 * The whole feature is one promise — *a password only ever reaches a field the user pointed at, on
 * a screen we could identify* — and this class is where it is kept:
 *
 *  - **Locked means locked.** A locked or absent vault produces exactly one kind of row: an
 *    authentication dataset whose values are null and whose `IntentSender` opens
 *    [AutofillUnlockActivity]. Real values are only ever put in a dataset once [VaultRepository]
 *    reports [VaultState.Unlocked], and the idle timeout is re-applied here on every request,
 *    because while the user is inside someone else's app nothing else is watching the clock.
 *  - **Only the matcher decides.** Every candidate comes from [AutofillMatcher]. There is no code
 *    path that lists the vault into another app's window; when nothing matches, the user gets the
 *    "بحث في حصن…" row and picks for themselves.
 *  - **A `webDomain` is only believed from a browser.** [StructureParser] settles that before we
 *    see the target, which is why everything below can treat [AutofillTarget] as trustworthy.
 *  - **Nothing is logged.** Not a password, not a user name, not a title or a URL — the few log
 *    lines here carry an exception's class name and nothing else.
 *
 * The activities it launches own the vocabulary of the intents (`AutofillFlow`) and the single
 * shared [VaultRepository] (`AutofillVault`); this service goes through both rather than keeping
 * its own copy of either.
 */
class HisnAutofillService : AutofillService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var vault: VaultRepository

    /**
     * A snapshot of the idle timeout, kept current by the collector below.
     *
     * `AutofillVault.settle()` does the same job but suspends to read the setting, and a fill
     * request is on the user's critical path: the prompt appears while the keyboard is opening, so
     * this side of it stays synchronous and reads the value it was told last.
     */
    @Volatile
    private var autoLockSeconds: Int = DEFAULT_AUTO_LOCK_SECONDS

    override fun onCreate() {
        super.onCreate()
        vault = AutofillVault.repository(this)
        scope.launch {
            vault.prefs.settings.collect { autoLockSeconds = it.autoLockSeconds }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------- filling

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // Everything below is synchronous — parse, match, build — so there is nothing to abandon
        // when the signal fires; the platform simply discards a response that arrives too late.
        val response = try {
            buildFillResponse(request)
        } catch (e: Throwable) {
            Log.w(TAG, "fill request failed (${e.javaClass.simpleName})")
            null
        }
        // onSuccess(null) is how the framework is told "nothing to offer on this screen".
        callback.onSuccess(response)
    }

    private fun buildFillResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val parsed = StructureParser.parse(listOf(structure)) ?: return null
        if (!parsed.fillable) return null

        val target = withLabel(parsed.target)
        if (!mayFill(target.packageName)) return null

        // The vault may have been left unlocked in this process by an earlier fill; the user has
        // been in other apps since, and no screen of ours was up to notice.
        enforceAutoLock()

        val pickIntent = intentFor(AutofillPickerActivity::class.java, target, parsed)
        val inline = inlineSupport(request, pickIntent)

        val response = FillResponse.Builder()
        var rows = 0
        val inlineEntryLimit = inlineEntryLimit(inline)

        val database = vault.database.value
        if (vault.status.value.state == VaultState.Unlocked && database != null) {
            for (match in AutofillMatcher.match(database, target, MAX_DATASETS)) {
                val dataset = DatasetBuilder.entryDataset(
                    context = this,
                    entry = match.entry,
                    usernameId = parsed.usernameId,
                    passwordId = parsed.passwordId,
                    inline = if (rows < inlineEntryLimit) {
                        inlineRow(
                            inline = inline,
                            index = rows,
                            title = DatasetBuilder.titleFor(this, match.entry),
                            subtitle = DatasetBuilder.subtitleFor(match.entry),
                        )
                    } else {
                        null
                    },
                ) ?: continue
                response.addDataset(dataset)
                rows++
            }
        } else {
            unlockDataset(target, parsed, inline, rows)?.let {
                response.addDataset(it)
                rows++
            }
        }

        // Offered whatever the state. The matcher is strict on purpose, and this row is how the
        // user reaches an entry it would not guess at — by opening a screen of ours, rather than
        // by us listing the vault into the requesting app's window.
        searchDataset(target, parsed, pickIntent, inline, minOf(rows, inlineEntryLimit))?.let {
            response.addDataset(it)
            rows++
        }

        val saveInfo = saveInfoFor(parsed, target)
        if (rows == 0 && saveInfo == null) return null
        saveInfo?.let(response::setSaveInfo)
        // Travels with the response and comes back on the save request; nothing secret goes in.
        response.setClientState(AutofillConstants.putTarget(Bundle(), target))
        return response.build()
    }

    /** The row shown while the vault is closed. Its values are null; only the unlock screen fills. */
    private fun unlockDataset(
        target: AutofillTarget,
        parsed: ParsedStructure,
        inline: DatasetBuilder.InlineSupport?,
        row: Int,
    ): Dataset? {
        val title = getString(R.string.autofill_unlock_title)
        val subtitle = getString(R.string.autofill_filling_for, target.displayName())
        val intent = intentFor(AutofillUnlockActivity::class.java, target, parsed)
        return DatasetBuilder.authDataset(
            context = this,
            usernameId = parsed.usernameId,
            passwordId = parsed.passwordId,
            title = title,
            subtitle = subtitle,
            authentication = authenticationSender(AutofillConstants.RC_UNLOCK, intent),
            inline = inlineRow(inline, row, title, subtitle, pinned = true),
        )
    }

    /** The "بحث في حصن…" row, which opens the picker. */
    private fun searchDataset(
        target: AutofillTarget,
        parsed: ParsedStructure,
        pickIntent: Intent,
        inline: DatasetBuilder.InlineSupport?,
        row: Int,
    ): Dataset? {
        val title = getString(R.string.autofill_search_in_hisn)
        val subtitle = getString(R.string.autofill_filling_for, target.displayName())
        return DatasetBuilder.authDataset(
            context = this,
            usernameId = parsed.usernameId,
            passwordId = parsed.passwordId,
            title = title,
            subtitle = subtitle,
            authentication = authenticationSender(AutofillConstants.RC_PICK, pickIntent),
            inline = inlineRow(inline, row, title, subtitle, pinned = true),
        )
    }

    /**
     * What Android should offer to remember once the user submits.
     *
     * A screen with only a user name is the first half of a two-step login: from API 28 the
     * platform can be asked to hold the save until the password page has been through here too, so
     * Hisn never offers to store a user name with no password. Below that, it simply does not
     * offer.
     */
    private fun saveInfoFor(parsed: ParsedStructure, target: AutofillTarget): SaveInfo? {
        val password = parsed.passwordId
        val username = parsed.usernameId
        return when {
            password != null -> {
                var type = SaveInfo.SAVE_DATA_TYPE_PASSWORD
                if (username != null) type = type or SaveInfo.SAVE_DATA_TYPE_USERNAME
                SaveInfo.Builder(type, arrayOf(password))
                    .apply {
                        username?.let { setOptionalIds(arrayOf(it)) }
                        // A page swaps its own content instead of finishing an activity, so in a
                        // browser the fields going away is the only reliable "submitted" signal.
                        if (target is AutofillTarget.Web) {
                            setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                        }
                    }
                    .build()
            }

            username != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, arrayOf(username))
                    .setFlags(SaveInfo.FLAG_DELAY_SAVE)
                    .build()

            else -> null
        }
    }

    // ----------------------------------------------------------------------------------- saving

    /**
     * The user submitted a login. Nothing is written here: the credentials are handed to
     * [AutofillSaveActivity], which shows what it is about to store and waits to be told.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structures: List<AssistStructure> = request.fillContexts.map { it.structure }
            val parsed = StructureParser.parse(structures)
            val password = parsed?.submittedPassword
            if (parsed == null || password.isNullOrEmpty()) {
                // A user name on its own is not a credential, and an entry without a password is
                // not worth interrupting the user for.
                callback.onFailure(getString(R.string.autofill_save_nothing))
                return
            }

            // The client state was written by us at fill time, when a browser's domain had been
            // through BrowserRegistry; prefer it over anything re-read from the submitted screen.
            val target = clientStateTarget(request.clientState)
                ?.takeIf { it.packageName == parsed.target.packageName }
                ?: withLabel(parsed.target)
            if (!mayFill(target.packageName)) {
                callback.onFailure(getString(R.string.autofill_save_nothing))
                return
            }

            val intent = Intent(this, AutofillSaveActivity::class.java).also {
                AutofillConstants.putTarget(it, target)
                it.putExtra(AutofillConstants.EXTRA_SUBMITTED_USERNAME, parsed.submittedUsername.orEmpty())
                it.putExtra(AutofillConstants.EXTRA_SUBMITTED_PASSWORD, password)
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // The platform launches this one itself and adds nothing to it, so it is immutable.
                callback.onSuccess(pendingIntent(AutofillConstants.RC_SAVE, intent, mutable = false).intentSender)
            } else {
                startActivity(intent)
                callback.onSuccess()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "save request failed (${e.javaClass.simpleName})")
            callback.onFailure(getString(R.string.autofill_save_nothing))
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    /**
     * Applies the idle timeout the settings screen owns.
     *
     * Autofill is the one place an unlocked vault can be reached with no Hisn screen on display,
     * so the check belongs here as much as in the UI. `AutofillVault.settle()` is the suspending
     * equivalent the activities use.
     */
    private fun enforceAutoLock() {
        if (vault.status.value.state != VaultState.Unlocked) return
        val timeout = autoLockSeconds
        if (timeout == Prefs.AUTO_LOCK_NEVER) return
        if (vault.idleSeconds() < timeout) return
        vault.lock()
    }

    /** Hisn does not fill Hisn, and the platform itself has no login to fill. */
    private fun mayFill(packageName: String): Boolean =
        packageName != applicationContext.packageName && packageName != PLATFORM_PACKAGE

    /**
     * The target the save request will get back, with the browser check applied again on the way
     * in: a client state has been through the system, so it is read with the same suspicion as any
     * other intent extra rather than trusted because we wrote it.
     */
    private fun clientStateTarget(state: Bundle?): AutofillTarget? =
        when (val target = AutofillConstants.readTarget(state)) {
            null -> null
            is AutofillTarget.App -> target
            is AutofillTarget.Web ->
                if (BrowserRegistry.isBrowser(target.packageName)) target else AutofillTarget.App(target.packageName)
        }

    private fun intentFor(
        activity: Class<*>,
        target: AutofillTarget,
        parsed: ParsedStructure,
    ): Intent = Intent(this, activity).also {
        AutofillConstants.putTarget(it, target)
        AutofillConstants.putFieldIds(it, parsed.usernameId, parsed.passwordId)
    }

    /**
     * An `IntentSender` for a dataset's authentication. It must be mutable: before launching it
     * the platform fills in the assist structure, the client state and — from API 30 — the inline
     * request, and from API 31 a `PendingIntent` that has not declared its mutability is rejected
     * outright.
     */
    private fun authenticationSender(requestCode: Int, intent: Intent): IntentSender =
        pendingIntent(requestCode, intent, mutable = true).intentSender

    private fun pendingIntent(requestCode: Int, intent: Intent, mutable: Boolean): PendingIntent {
        // FLAG_UPDATE_CURRENT rather than FLAG_CANCEL_CURRENT: a second request for the same screen
        // must refresh the extras of the sender the platform is holding, not invalidate a sender
        // the user may be about to tap.
        val flags = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }

    private fun withLabel(target: AutofillTarget): AutofillTarget = when (target) {
        is AutofillTarget.Web -> target
        is AutofillTarget.App ->
            if (target.appLabel != null) target else target.copy(appLabel = appLabel(target.packageName))
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            .toString()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    // -- inline suggestions, API 30+ -------------------------------------------------------------

    private fun inlineSupport(request: FillRequest, attributionIntent: Intent): DatasetBuilder.InlineSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Handed to the keyboard, which only launches it, so it needs nothing filled in.
        val attribution = pendingIntent(AutofillConstants.RC_ATTRIBUTION, attributionIntent, mutable = false)
        return DatasetBuilder.InlineSupport.from(request, attribution)
    }

    /**
     * How many keyboard slots the matches may take. The last one is kept for the pinned
     * "بحث في حصن…" row, which has to survive however many entries matched — it is the only way
     * out of the strip when none of them is the right one.
     */
    private fun inlineEntryLimit(inline: DatasetBuilder.InlineSupport?): Int {
        if (inline == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Int.MAX_VALUE
        return (inline.capacity - 1).coerceAtLeast(0)
    }

    private fun inlineRow(
        inline: DatasetBuilder.InlineSupport?,
        index: Int,
        title: String,
        subtitle: String?,
        pinned: Boolean = false,
    ): InlinePresentation? {
        if (inline == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return inline.presentation(this, index, title, subtitle, pinned)
    }

    private companion object {
        const val TAG = "HisnAutofill"

        /** Matches the vault's own default; only used until the settings collector first emits. */
        const val DEFAULT_AUTO_LOCK_SECONDS = 60

        /** More rows than this is a list, not a suggestion. */
        const val MAX_DATASETS = 8

        /** System dialogs and the recents screen report this; there is nothing there to fill. */
        const val PLATFORM_PACKAGE = "android"
    }
}
