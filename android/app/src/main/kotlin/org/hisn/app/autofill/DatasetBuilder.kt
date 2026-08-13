package org.hisn.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillRequest
import android.service.autofill.InlinePresentation
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import org.hisn.app.R
import org.hisn.app.kdbx.Entry

/**
 * Builds the rows the fill prompt shows and the [Dataset]s behind them.
 *
 * The one invariant here: **a presentation never contains the secret.** A row shows the entry's
 * title and user name, because the prompt is drawn by another app's window (and, inline, by the
 * keyboard) — anything put in a `RemoteViews` or a `Slice` has left our process. The password only
 * ever travels inside the [Dataset]'s values, which the platform delivers straight to the field
 * the user chose.
 */
object DatasetBuilder {

    private const val TAG = "HisnAutofill"

    // -- presentations --------------------------------------------------------------------------

    /** The dropdown row: icon, title, and a subtitle that is at most a user name. */
    fun presentation(context: Context, title: CharSequence, subtitle: CharSequence?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.autofill_item)
        views.setTextViewText(R.id.autofill_title, title)
        views.setImageViewResource(R.id.autofill_icon, R.mipmap.ic_launcher)
        if (subtitle.isNullOrEmpty()) {
            views.setViewVisibility(R.id.autofill_subtitle, View.GONE)
        } else {
            views.setViewVisibility(R.id.autofill_subtitle, View.VISIBLE)
            views.setTextViewText(R.id.autofill_subtitle, subtitle)
        }
        return views
    }

    /**
     * The row's first line. Falls back to the user name, then to the "(بلا عنوان)" label, so a row
     * is never blank — and so a row built here reads exactly like one built by `AutofillFlow` for
     * the picker.
     */
    fun titleFor(context: Context, entry: Entry): String =
        entry.title.ifBlank { entry.username }.ifBlank { context.getString(R.string.entry_untitled) }

    /** The row's second line: the user name, and never anything else. Blank hides the line. */
    fun subtitleFor(entry: Entry): String = entry.username

    // -- datasets -------------------------------------------------------------------------------

    /**
     * A dataset that fills [entry]'s real values. Only ever built once the vault is open.
     *
     * Returns null when nothing would actually be filled — an entry with only a password on a
     * screen with only a user name field, say — because an empty dataset would clear the field the
     * user tapped.
     */
    fun entryDataset(
        context: Context,
        entry: Entry,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        inline: InlinePresentation? = null,
    ): Dataset? {
        val username = entry.username
        val password = entry.password
        val fillsUsername = usernameId != null && username.isNotEmpty()
        val fillsPassword = passwordId != null && password.isNotEmpty()
        if (!fillsUsername && !fillsPassword) return null

        val presentation = presentation(context, titleFor(context, entry), subtitleFor(entry))

        val builder = Dataset.Builder()
        if (fillsUsername) {
            builder.setValueCompat(usernameId, AutofillValue.forText(username), presentation, inline)
        }
        if (fillsPassword) {
            builder.setValueCompat(passwordId, AutofillValue.forText(password), presentation, inline)
        }
        return builder.build()
    }

    /**
     * A dataset that fills nothing until the user has been through one of our screens: the values
     * are null and [authentication] opens the activity that will produce the real ones.
     *
     * This is the only kind of dataset a locked vault may return.
     */
    fun authDataset(
        context: Context,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        title: CharSequence,
        subtitle: CharSequence?,
        authentication: IntentSender,
        inline: InlinePresentation? = null,
    ): Dataset? {
        if (usernameId == null && passwordId == null) return null
        val presentation = presentation(context, title, subtitle)
        val builder = Dataset.Builder()
        usernameId?.let { builder.setValueCompat(it, null, presentation, inline) }
        passwordId?.let { builder.setValueCompat(it, null, presentation, inline) }
        builder.setAuthentication(authentication)
        return builder.build()
    }

    /**
     * The three-argument `setValue` is deprecated from API 33 in favour of `Presentations`, but it
     * is the only form that exists on API 26, and it still works everywhere. The four-argument one
     * carries the inline row alongside the dropdown row on API 30+.
     */
    @Suppress("DEPRECATION")
    private fun Dataset.Builder.setValueCompat(
        id: AutofillId,
        value: AutofillValue?,
        presentation: RemoteViews,
        inline: InlinePresentation?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) {
            setValue(id, value, presentation, inline)
        } else {
            setValue(id, value, presentation)
        }
    }

    // -- inline suggestions ---------------------------------------------------------------------

    /**
     * The keyboard's suggestion strip (Android 11+).
     *
     * The IME renders these itself from a `Slice` whose layout is defined by `androidx.autofill`,
     * so the library is the supported way to build one. Everything is wrapped: an IME that asks
     * for a style we cannot satisfy must degrade to the dropdown, never fail the request.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    class InlineSupport private constructor(
        private val specs: List<InlinePresentationSpec>,
        private val maxSuggestions: Int,
        private val attribution: PendingIntent,
    ) {

        /** How many rows the keyboard is willing to show. */
        val capacity: Int get() = maxSuggestions

        /**
         * @param index position of this row in the response; the last spec repeats once the
         *        keyboard's list of specs runs out, which is what the platform expects.
         * @param pinned keeps the row visible when the strip scrolls — used for our own actions,
         *        never for a credential.
         */
        fun presentation(
            context: Context,
            index: Int,
            title: CharSequence,
            subtitle: CharSequence?,
            pinned: Boolean = false,
        ): InlinePresentation? {
            if (index >= maxSuggestions || specs.isEmpty()) return null
            val spec = specs.getOrElse(index) { specs.last() }
            return try {
                if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
                val icon = Icon.createWithResource(context, R.mipmap.ic_launcher).apply {
                    // Without this the keyboard tints the icon to its own colour scheme, which on
                    // a blue-grey IME would be the one place in Hisn that is not copper.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setTintBlendMode(BlendMode.DST)
                }
                val content = InlineSuggestionUi.newContentBuilder(attribution)
                    .setTitle(title)
                    .also { builder -> subtitle?.takeIf { it.isNotEmpty() }?.let(builder::setSubtitle) }
                    .setStartIcon(icon)
                    .setContentDescription(title)
                    .build()
                InlinePresentation(content.slice, spec, pinned)
            } catch (e: Exception) {
                Log.w(TAG, "inline presentation unavailable (${e.javaClass.simpleName})")
                null
            }
        }

        companion object {
            /**
             * @param attribution opened when the user long-presses a suggestion; the platform
             *        requires one, and the picker is the honest answer to "where did this come
             *        from".
             */
            fun from(request: FillRequest, attribution: PendingIntent): InlineSupport? {
                val inlineRequest = request.inlineSuggestionsRequest ?: return null
                val specs = inlineRequest.inlinePresentationSpecs
                if (specs.isEmpty() || inlineRequest.maxSuggestionCount <= 0) return null
                return InlineSupport(specs, inlineRequest.maxSuggestionCount, attribution)
            }
        }
    }
}
