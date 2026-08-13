package org.hisn.app.autofill

import org.hisn.app.kdbx.Entry
import org.hisn.app.kdbx.KdbxDatabase

/**
 * Decides which entries may be offered for a given autofill request.
 *
 * The rule this file exists to enforce: **an entry is only offered when it names the thing being
 * filled.** Autofill runs without the user having chosen anything, so a loose match here silently
 * hands a bank password to whatever app asked. Everything below is therefore a whitelist — an entry
 * has to earn its place — and there is no "fall back to showing everything" branch.
 *
 * How an entry declares what it belongs to:
 *  - a URL field or a `KP2A_URL*` attribute containing the site (the same attribute KeePassXC and
 *    Keepass2Android use, so databases stay interchangeable)
 *  - `androidapp://com.example.app` in any of those URL slots, the Keepass2Android convention for
 *    binding an entry to an installed app
 *  - the package name itself in the `AndroidApp` attribute
 */
object AutofillMatcher {

    /** Attribute prefix shared with the desktop app and Keepass2Android. */
    private const val ADDITIONAL_URL_PREFIX = "KP2A_URL"
    private const val ANDROID_APP_ATTRIBUTE = "AndroidApp"
    private const val ANDROID_APP_SCHEME = "androidapp://"

    /** A candidate entry with the score that got it there; higher wins. */
    data class Match(val entry: Entry, val score: Int)

    /**
     * Score bands. The gaps are deliberate: they leave room to insert finer rules later without
     * reordering existing ones, and they make a debug dump readable.
     */
    private const val SCORE_EXACT_PACKAGE = 100
    private const val SCORE_EXACT_HOST = 90
    private const val SCORE_DOMAIN = 80
    private const val SCORE_TITLE_IS_DOMAIN = 40

    /**
     * Entries that may be shown for [target], best first.
     *
     * Recycled entries are excluded: a password the user threw away should not come back through a
     * fill prompt.
     */
    fun match(db: KdbxDatabase, target: AutofillTarget, limit: Int = 8): List<Match> =
        db.visibleEntries()
            .mapNotNull { entry -> scoreOf(entry, target)?.let { Match(entry, it) } }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.entry.title.lowercase() })
            .take(limit)

    /** The score for one entry, or null when it must not be offered. */
    fun scoreOf(entry: Entry, target: AutofillTarget): Int? {
        // An entry with no password and no username can fill nothing.
        if (entry.password.isEmpty() && entry.username.isEmpty()) return null

        return when (target) {
            is AutofillTarget.App -> scoreForApp(entry, target.packageName)
            is AutofillTarget.Web -> scoreForWeb(entry, target)
        }
    }

    // -- app requests ---------------------------------------------------------------------------

    /**
     * Only an explicit binding counts. There is no fuzzy "the title looks like the app" rule: app
     * labels and package names are attacker-chosen, so a guess is a leak.
     */
    private fun scoreForApp(entry: Entry, packageName: String): Int? {
        val declared = entry.androidPackages()
        return if (packageName in declared) SCORE_EXACT_PACKAGE else null
    }

    // -- browser requests -----------------------------------------------------------------------

    private fun scoreForWeb(entry: Entry, target: AutofillTarget.Web): Int? {
        var best: Int? = null
        for (raw in entry.allUrls()) {
            // androidapp:// bindings never satisfy a web request.
            if (raw.startsWith(ANDROID_APP_SCHEME, ignoreCase = true)) continue

            val host = DomainReducer.hostOf(raw) ?: continue
            val score = when {
                host == target.host -> SCORE_EXACT_HOST
                DomainReducer.registrableDomain(host) == target.domain -> SCORE_DOMAIN
                else -> null
            }
            if (score != null && (best == null || score > best!!)) best = score
        }
        if (best != null) return best

        // Last resort: the title *is* the domain, e.g. an entry literally called "bank.example".
        // Requiring an exact equality keeps this from matching "My bank" against anything.
        val title = entry.title.trim().lowercase()
        if (title.isNotEmpty() && (title == target.domain || title == target.host)) {
            return SCORE_TITLE_IS_DOMAIN
        }
        return null
    }

    // -- entry accessors ------------------------------------------------------------------------

    /** The URL field plus every `KP2A_URL*` attribute, in declaration order, blanks removed. */
    fun Entry.allUrls(): List<String> {
        val out = mutableListOf<String>()
        url.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        for ((key, value) in fields) {
            if (key.startsWith(ADDITIONAL_URL_PREFIX, ignoreCase = true) && value.value.isNotBlank()) {
                out.add(value.value.trim())
            }
        }
        return out
    }

    /** Package names this entry is bound to, from `androidapp://` URLs and the `AndroidApp` field. */
    fun Entry.androidPackages(): Set<String> {
        val out = mutableSetOf<String>()
        for (raw in allUrls()) {
            if (raw.startsWith(ANDROID_APP_SCHEME, ignoreCase = true)) {
                raw.substring(ANDROID_APP_SCHEME.length)
                    .substringBefore('/')
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotEmpty() }
                    ?.let(out::add)
            }
        }
        fields[ANDROID_APP_ATTRIBUTE]?.value
            ?.split(',', ';', ' ')
            ?.forEach { part -> part.trim().lowercase().takeIf { it.isNotEmpty() }?.let(out::add) }
        return out
    }

    /**
     * The URL to write into a newly saved entry so the next request matches it.
     *
     * Saving is the only path that *creates* a binding, which is why it goes through the same
     * vocabulary the matcher reads rather than inventing a new one.
     */
    fun bindingUrlFor(target: AutofillTarget): String = when (target) {
        is AutofillTarget.Web -> "https://${target.host}"
        is AutofillTarget.App -> "$ANDROID_APP_SCHEME${target.packageName}"
    }
}
