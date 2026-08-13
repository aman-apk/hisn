package org.hisn.app.autofill

/**
 * What the system is asking us to fill.
 *
 * Autofill requests arrive with two very different levels of trust, and conflating them is how an
 * autofill service leaks credentials:
 *
 *  - **[App]** — a normal application asking for its own fields. The only identity we can rely on
 *    is the package name, which the platform guarantees.
 *  - **[Web]** — a *browser* rendering a page. Here the credentials belong to the site, not to the
 *    browser, so the domain decides the match. The domain is read out of the view hierarchy, which
 *    means any app could claim one; we therefore only believe it from a package we recognise as a
 *    browser (see [BrowserRegistry]).
 */
sealed class AutofillTarget {

    /** The package the request came from. Always known and always trustworthy. */
    abstract val packageName: String

    data class App(override val packageName: String, val appLabel: String? = null) : AutofillTarget()

    data class Web(
        override val packageName: String,
        /** The registrable domain, already reduced from the full host (e.g. `bank.example.co.uk`). */
        val domain: String,
        /** The full host as reported, kept for display and for exact-host scoring. */
        val host: String,
    ) : AutofillTarget()

    /** Short text for the "filling for …" line in the UI. */
    fun displayName(): String = when (this) {
        is Web -> host
        is App -> appLabel ?: packageName
    }

    companion object {
        /**
         * The only sanctioned way to turn a (package, claimed web domain) pair into a target.
         *
         * The anti-phishing rule lives here rather than at each call site on purpose. A `webDomain`
         * is attacker-controlled — any app can write one into its view nodes — so it is honoured
         * only when the caller is a package we recognise as a browser. Every other case degrades to
         * [App] on the package name, which the platform guarantees.
         *
         * Both the request parser and the code that restores a target from a Bundle/Intent must go
         * through this function. Deserialising a `Web` target without re-applying the check would
         * reintroduce exactly the hole the check exists to close, since a stored target is just
         * bytes by the time it comes back.
         */
        fun of(packageName: String, webDomain: String?, appLabel: String? = null): AutofillTarget {
            val host = DomainReducer.hostOf(webDomain)
            if (host != null && BrowserRegistry.isBrowser(packageName)) {
                val domain = DomainReducer.registrableDomain(host) ?: host
                return Web(packageName = packageName, domain = domain, host = host)
            }
            return App(packageName = packageName, appLabel = appLabel)
        }
    }
}

/**
 * Which packages are allowed to speak for a web domain.
 *
 * A malicious app can put anything it likes in the `webDomain` attribute of its view nodes. If we
 * trusted that blindly, an app called `com.evil.game` could claim to be `bank.example` and be
 * offered the bank's password. So a `webDomain` is only honoured when the requesting package is a
 * browser we know; otherwise the request is treated as an ordinary app request and matched on the
 * package name, which the platform vouches for.
 *
 * The list is intentionally explicit rather than heuristic ("does the package contain the word
 * browser"), because a heuristic here is a vulnerability.
 */
object BrowserRegistry {

    private val browsers = setOf(
        // Chrome and Chromium family
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.android.browser",
        "com.google.android.apps.chrome",
        // Firefox family
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_fdroid",
        "org.mozilla.focus",
        "org.mozilla.klar",
        // Others in common use
        "com.microsoft.emmx",              // Edge
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.sec.android.app.sbrowser",    // Samsung Internet
        "com.sec.android.app.sbrowser.beta",
        "com.yandex.browser",
        "com.kiwibrowser.browser",
        "org.torproject.torbrowser",
        "info.guardianproject.orfox",
        "com.UCMobile.intl",
        "acr.browser.lightning",
        "com.stoutner.privacybrowser.standard",
        "org.adblockplus.browser",
        "com.ecosia.android",
        "com.qwant.liberty",
    )

    fun isBrowser(packageName: String): Boolean = packageName in browsers

    /** Exposed so the settings screen can explain what is recognised. */
    fun known(): Set<String> = browsers
}

/**
 * Reduces a host to the domain a password actually belongs to.
 *
 * `login.bank.example` and `www.bank.example` are the same account; `bank.example` and
 * `bank.example.evil.com` are not. Android ships a public-suffix database via
 * [android.net.Uri]-adjacent APIs only indirectly, so this uses a compact rule set: strip a leading
 * `www.`, then keep the last two labels — except for a known list of multi-part public suffixes
 * where three labels are needed (`co.uk`, `com.sa`, …).
 *
 * This deliberately errs towards being *narrower* than the true public-suffix list: when in doubt
 * the caller falls back to comparing full hosts, which can only refuse a match, never invent one.
 */
object DomainReducer {

    private val multiPartSuffixes = setOf(
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "net.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.za", "org.za", "web.za",
        "com.br", "net.br", "org.br", "gov.br",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "co.in", "net.in", "org.in", "gov.in", "ac.in",
        "com.mx", "com.ar", "com.co", "com.pe", "com.ve",
        "com.tr", "gov.tr", "edu.tr", "org.tr",
        // Arab world
        "com.sa", "net.sa", "org.sa", "gov.sa", "edu.sa", "med.sa", "sch.sa",
        "com.eg", "net.eg", "org.eg", "gov.eg", "edu.eg", "sci.eg",
        "com.ae", "net.ae", "org.ae", "gov.ae", "ac.ae", "sch.ae",
        "com.kw", "net.kw", "org.kw", "gov.kw", "edu.kw",
        "com.qa", "net.qa", "org.qa", "gov.qa", "edu.qa",
        "com.bh", "net.bh", "org.bh", "gov.bh", "edu.bh",
        "com.om", "net.om", "org.om", "gov.om", "edu.om",
        "com.jo", "net.jo", "org.jo", "gov.jo", "edu.jo",
        "com.lb", "net.lb", "org.lb", "gov.lb", "edu.lb",
        "com.sy", "gov.sy", "edu.sy", "org.sy",
        "com.iq", "gov.iq", "edu.iq", "net.iq",
        "com.ly", "com.tn", "com.dz", "com.ma", "com.ye", "com.sd", "com.ps",
        "com.pk", "net.pk", "org.pk", "gov.pk", "edu.pk",
        "com.my", "net.my", "org.my", "gov.my", "edu.my",
        "com.sg", "net.sg", "org.sg", "gov.sg", "edu.sg",
        "com.hk", "net.hk", "org.hk", "gov.hk", "edu.hk",
        "com.tw", "net.tw", "org.tw", "gov.tw", "edu.tw",
        "com.ph", "net.ph", "org.ph", "gov.ph",
        "co.id", "web.id", "or.id", "go.id", "ac.id",
        "co.th", "in.th", "go.th", "ac.th",
        "co.il", "org.il", "net.il", "gov.il", "ac.il",
        "com.ru", "net.ru", "org.ru",
        "com.ua", "net.ua", "org.ua",
        "com.pl", "net.pl", "org.pl", "gov.pl",
        "com.es", "com.pt", "com.gr", "com.cy", "com.mt",
        "com.ng", "com.gh", "com.ke", "co.ke", "co.tz", "co.ug",
    )

    /** `https://login.bank.co.uk/x` → `bank.co.uk`. Returns null when there is no usable host. */
    fun registrableDomain(hostOrUrl: String?): String? {
        val host = hostOf(hostOrUrl) ?: return null
        if (host.isEmpty()) return null
        // An IP address has no registrable domain; compare it whole.
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host

        val labels = host.removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".").ifEmpty { null }

        val lastTwo = labels.takeLast(2).joinToString(".")
        val take = if (lastTwo in multiPartSuffixes) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    /** Extracts the host from a URL, or accepts a bare host as-is. Lower-cased, port removed. */
    fun hostOf(hostOrUrl: String?): String? {
        val raw = hostOrUrl?.trim()?.lowercase() ?: return null
        if (raw.isEmpty()) return null

        var s = raw
        // Strip a scheme if present; anything with "://" is treated as a URL.
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
        // Strip credentials, path, query and fragment.
        s = s.substringAfterLast('@').substringBefore('/').substringBefore('?').substringBefore('#')
        // Strip the port, but leave bracketed IPv6 intact.
        if (!s.startsWith("[")) s = s.substringBefore(':')
        return s.trim('.').ifEmpty { null }
    }
}
