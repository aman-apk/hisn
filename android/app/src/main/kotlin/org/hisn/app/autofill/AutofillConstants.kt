package org.hisn.app.autofill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import androidx.core.content.IntentCompat
import org.hisn.app.data.VaultRepository

/**
 * The vocabulary shared between [HisnAutofillService] and the three activities it launches
 * (`AutofillUnlockActivity`, `AutofillPickerActivity`, `AutofillSaveActivity`).
 *
 * The service and the activities run in the same process but never call each other directly: the
 * platform launches the activity from an `IntentSender` and takes the result back. Everything they
 * have to agree on therefore travels as intent extras, and it all lives here so that neither side
 * has to guess a key.
 *
 * Read [putTarget]/[readTarget] and [putFieldIds]/[readUsernameId]/[readPasswordId] as pairs: an
 * activity reconstructs exactly what the service parsed, with no re-parsing of the view structure.
 */
object AutofillConstants {

    private const val PREFIX = "org.hisn.app.autofill.extra."

    // -- what is being filled -----------------------------------------------------------------

    /** Package name of the app that asked to be filled. Always present. */
    const val EXTRA_PACKAGE_NAME = PREFIX + "PACKAGE_NAME"

    /** Human-readable label of that app, when the package manager would tell us. May be absent. */
    const val EXTRA_APP_LABEL = PREFIX + "APP_LABEL"

    /** Full host (`login.bank.example`) — present only for a browser request. */
    const val EXTRA_WEB_HOST = PREFIX + "WEB_HOST"

    /** Registrable domain (`bank.example`) — present only for a browser request. */
    const val EXTRA_WEB_DOMAIN = PREFIX + "WEB_DOMAIN"

    // -- where the values go ------------------------------------------------------------------

    /** [AutofillId] of the user name field; absent when the screen has none (two-step logins). */
    const val EXTRA_USERNAME_ID = PREFIX + "USERNAME_ID"

    /** [AutofillId] of the password field; absent when the screen has none. */
    const val EXTRA_PASSWORD_ID = PREFIX + "PASSWORD_ID"

    // -- what the user just typed (save flow only) --------------------------------------------

    const val EXTRA_SUBMITTED_USERNAME = PREFIX + "SUBMITTED_USERNAME"

    /**
     * The password the user submitted, on its way to `AutofillSaveActivity` for confirmation.
     *
     * It is an extra on an *explicit* intent aimed at our own component, which is the mechanism
     * the platform provides for this hand-off. It must never be logged, never be put into a
     * `RemoteViews`, and never be written anywhere but the vault.
     */
    const val EXTRA_SUBMITTED_PASSWORD = PREFIX + "SUBMITTED_PASSWORD"

    // -- PendingIntent request codes ----------------------------------------------------------
    //
    // A PendingIntent is identified by its creator, its request code and its intent's component —
    // extras are not part of that identity. So every sender in this feature needs its own code, or
    // one flow silently replaces another's. These are the service's; the activities keep their own
    // in a different range for the same reason.

    /** Opens `AutofillUnlockActivity` from the "افتح حصن للملء" row. */
    const val RC_UNLOCK = 0x4101

    /** Opens `AutofillPickerActivity` from the "بحث في حصن…" row. */
    const val RC_PICK = 0x4201

    /** Opens `AutofillSaveActivity` once the user has submitted a login. */
    const val RC_SAVE = 0x4301

    /**
     * The inline suggestion's attribution intent — what the keyboard opens when the user long
     * presses a suggestion to ask where it came from. Immutable, because unlike an authentication
     * sender nothing is ever filled into it.
     */
    const val RC_ATTRIBUTION = 0x4401

    // -- target <-> intent --------------------------------------------------------------------

    fun putTarget(intent: Intent, target: AutofillTarget): Intent {
        intent.putExtra(EXTRA_PACKAGE_NAME, target.packageName)
        when (target) {
            is AutofillTarget.App -> target.appLabel?.let { intent.putExtra(EXTRA_APP_LABEL, it) }
            is AutofillTarget.Web -> {
                intent.putExtra(EXTRA_WEB_HOST, target.host)
                intent.putExtra(EXTRA_WEB_DOMAIN, target.domain)
            }
        }
        return intent
    }

    /**
     * Rebuilds the target the service decided on. Returns null when the intent did not come from
     * the service — an activity must refuse to hand out secrets in that case rather than guess.
     */
    fun readTarget(intent: Intent?): AutofillTarget? {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val host = intent.getStringExtra(EXTRA_WEB_HOST)?.takeIf { it.isNotBlank() }
        // Rebuilt through AutofillTarget.of so the browser check is re-applied. A stored target is
        // just bytes by the time it comes back; trusting the host recorded in it would reinstate
        // the very hole the check closes.
        return AutofillTarget.of(
            packageName = packageName,
            webDomain = host,
            appLabel = intent.getStringExtra(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The same values in a [Bundle], for `FillResponse.setClientState` — which the platform hands
     * back on the save request and to every authentication activity. Nothing secret goes in: a
     * client state is held by the system and passed on to whoever fills next.
     */
    fun putTarget(bundle: Bundle, target: AutofillTarget): Bundle {
        bundle.putString(EXTRA_PACKAGE_NAME, target.packageName)
        when (target) {
            is AutofillTarget.App -> target.appLabel?.let { bundle.putString(EXTRA_APP_LABEL, it) }
            is AutofillTarget.Web -> {
                bundle.putString(EXTRA_WEB_HOST, target.host)
                bundle.putString(EXTRA_WEB_DOMAIN, target.domain)
            }
        }
        return bundle
    }

    fun readTarget(bundle: Bundle?): AutofillTarget? {
        val packageName = bundle?.getString(EXTRA_PACKAGE_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val host = bundle.getString(EXTRA_WEB_HOST)?.takeIf { it.isNotBlank() }
        // Same rule as the Intent overload: revalidate rather than trust what was written down.
        return AutofillTarget.of(
            packageName = packageName,
            webDomain = host,
            appLabel = bundle.getString(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() },
        )
    }

    // -- field ids <-> intent -----------------------------------------------------------------

    fun putFieldIds(intent: Intent, usernameId: AutofillId?, passwordId: AutofillId?): Intent {
        usernameId?.let { intent.putExtra(EXTRA_USERNAME_ID, it) }
        passwordId?.let { intent.putExtra(EXTRA_PASSWORD_ID, it) }
        return intent
    }

    fun readUsernameId(intent: Intent?): AutofillId? = readId(intent, EXTRA_USERNAME_ID)

    fun readPasswordId(intent: Intent?): AutofillId? = readId(intent, EXTRA_PASSWORD_ID)

    private fun readId(intent: Intent?, key: String): AutofillId? =
        intent?.let { IntentCompat.getParcelableExtra(it, key, AutofillId::class.java) }
}

/**
 * The one [VaultRepository] the autofill flow uses.
 *
 * [VaultRepository] is not a singleton: each instance owns its own composite key and its own
 * decrypted model, so two instances mean two independent lock states. Here that is not cosmetic:
 *
 *  - the service would still see a locked vault after the user unlocked in `AutofillUnlockActivity`,
 *    so every fill request would ask for the master password again;
 *  - a save made through `AutofillSaveActivity` writes the file from one in-memory model while
 *    another instance still holds the pre-save one, and whichever saves last wins — silent data
 *    loss.
 *
 * Nothing here weakens the lock. The instance starts locked, holds no key material until an unlock
 * succeeds, and the idle timeout is applied before anything is handed out — by [settle] on the
 * activities' side, and by the service on every fill request.
 */
object AutofillVault {

    /**
     * Delegates to [VaultRepository.shared] so the autofill flow and the app's own screens operate
     * on the same vault. Holding a second instance here would race the UI's saves.
     */
    fun repository(context: Context): VaultRepository = VaultRepository.shared(context)

    /**
     * The repository with the auto-lock timeout already applied.
     *
     * Nothing in the autofill flow runs an idle watchdog — there is no long-lived screen to host
     * one — so the timeout is enforced lazily, the moment something asks for the vault. A vault
     * left unlocked in a backgrounded process is therefore closed again before it can hand
     * anything out.
     */
    suspend fun settle(context: Context): VaultRepository =
        repository(context).also { it.lockIfIdle() }
}
