package org.hisn.app.autofill

import org.hisn.app.autofill.AutofillMatcher.allUrls
import org.hisn.app.autofill.AutofillMatcher.androidPackages
import org.hisn.app.kdbx.Entry
import org.hisn.app.kdbx.Group
import org.hisn.app.kdbx.KdbxDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillMatcher] — which entries an autofill request is allowed to see.
 *
 * This is the security boundary of the whole feature. The system asks for credentials before the
 * user has chosen anything, so every entry this class returns is an entry whose password the user
 * may hand over with one tap. A false negative is an annoyance; a false positive is a stolen
 * password. The tests are therefore written as a whitelist: each one names the single reason an
 * entry earned its place, and the negative assertions next to it are the point of the test, not
 * padding.
 */
class AutofillMatcherTest {

    /** A package the platform vouches for as a browser, so a webDomain from it may be believed. */
    private val chrome = "com.android.chrome"

    // --------------------------------------------------------------- fixtures

    private fun entry(
        title: String,
        url: String? = null,
        username: String = "زينب",
        password: String = "kalimat-sirr",
        extraFields: Map<String, String> = emptyMap(),
    ): Entry {
        val e = Entry()
        e.set(Entry.TITLE, title)
        if (username.isNotEmpty()) e.set(Entry.USERNAME, username)
        if (password.isNotEmpty()) e.set(Entry.PASSWORD, password, protected = true)
        if (url != null) e.set(Entry.URL, url)
        extraFields.forEach { (key, value) -> e.set(key, value) }
        return e
    }

    private fun databaseOf(vararg entries: Entry): KdbxDatabase {
        val root = Group(name = "الجذر")
        entries.forEach(root::addEntry)
        return KdbxDatabase(root = root)
    }

    /** Builds the target exactly as the service does: reduce the reported host to its domain. */
    private fun web(host: String, packageName: String = chrome): AutofillTarget.Web =
        AutofillTarget.Web(
            packageName = packageName,
            domain = requireNonNullDomain(host),
            host = DomainReducer.hostOf(host)!!,
        )

    private fun requireNonNullDomain(host: String): String =
        DomainReducer.registrableDomain(host) ?: error("fixture host has no domain: $host")

    private fun app(packageName: String) = AutofillTarget.App(packageName)

    private fun titles(matches: List<AutofillMatcher.Match>) = matches.map { it.entry.title }

    // ------------------------------------------------------------- the phishing case

    @Test
    fun anEntryForADomainMatchesItsSubdomainButNotALookalikeSuffix() {
        val bank = entry("مصرف الأمان", url = "https://bank.example")
        val db = databaseOf(bank)

        // The real site, reached through a subdomain the user never stored explicitly.
        val genuine = web("login.bank.example")
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, genuine)))

        // bank.example.evil.com is registered by the attacker: the labels on the left are theirs to
        // choose, so a substring or suffix comparison here would be the leak. Only evil.com owns it.
        val lookalike = web("bank.example.evil.com")
        assertEquals("evil.com", lookalike.domain)
        assertNull(AutofillMatcher.scoreOf(bank, lookalike))
        assertTrue(AutofillMatcher.match(db, lookalike).isEmpty())

        // The mirror image: an entry stored for the attacker's site must not be offered to the bank.
        val evilEntry = entry("موقع مشبوه", url = "https://bank.example.evil.com")
        assertNull(AutofillMatcher.scoreOf(evilEntry, genuine))
    }

    @Test
    fun aWebDomainClaimedByANonBrowserIsMatchedAsAnAppAndFindsNothing() {
        // The webDomain attribute is read out of the view hierarchy, so any app can write
        // "bank.example" into it. The service only believes it from a package in BrowserRegistry;
        // everything else becomes an App target, which needs an explicit androidapp:// binding.
        assertFalse(BrowserRegistry.isBrowser("com.evil.game"))
        assertTrue(BrowserRegistry.isBrowser(chrome))

        val bank = entry("مصرف الأمان", url = "https://bank.example")
        val db = databaseOf(bank)

        assertTrue(AutofillMatcher.match(db, app("com.evil.game")).isEmpty())
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, web("bank.example"))))
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun anExactHostOutranksAnEntryThatOnlyMatchesTheDomain() {
        val precise = entry("بوابة الدخول", url = "https://login.bank.example")
        val broad = entry("مصرف الأمان", url = "https://bank.example")
        val db = databaseOf(broad, precise)

        val matches = AutofillMatcher.match(db, web("login.bank.example"))

        assertEquals(listOf("بوابة الدخول", "مصرف الأمان"), titles(matches))
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun aTitleThatIsExactlyTheDomainRanksBelowARealUrlBinding() {
        val byUrl = entry("مصرف الأمان", url = "https://bank.example")
        val byTitle = entry("bank.example")
        val db = databaseOf(byTitle, byUrl)

        val matches = AutofillMatcher.match(db, web("bank.example"))

        assertEquals(listOf("مصرف الأمان", "bank.example"), titles(matches))
        assertTrue(matches[0].score > matches[1].score)
    }

    // ------------------------------------------------------------ app bindings

    @Test
    fun anAndroidAppBindingMatchesOnlyThatExactPackage() {
        val bound = entry("تطبيق المثال", url = "androidapp://com.example.app")

        assertNotNull(AutofillMatcher.scoreOf(bound, app("com.example.app")))
        // A package name is a prefix tree, and anyone may publish com.example.app.evil. Matching on
        // a prefix (or on the label) would bind the entry to an app the user never approved.
        assertNull(AutofillMatcher.scoreOf(bound, app("com.example.app.evil")))
        assertNull(AutofillMatcher.scoreOf(bound, app("com.example")))
        assertNull(AutofillMatcher.scoreOf(bound, app("com.example.other")))

        assertEquals(setOf("com.example.app"), bound.androidPackages())
    }

    @Test
    fun anAndroidAppBindingIsNeverOfferedToABrowser() {
        // androidapp:// is not a web URL. Reducing it as one would yield the host "com.example.app",
        // and a page at that domain would then collect the app's password.
        val bound = entry("تطبيق المثال", url = "androidapp://com.example.app")

        assertNull(AutofillMatcher.scoreOf(bound, web("com.example.app")))
        assertNull(AutofillMatcher.scoreOf(bound, web("example.com")))
        assertNull(AutofillMatcher.scoreOf(bound, web("app.com.example")))
    }

    @Test
    fun aWebEntryIsNeverOfferedToAnAppRequest() {
        // The reverse direction of the same rule: a stored site says nothing about which installed
        // app may read it, even when the package name reads like the domain.
        val site = entry("مصرف الأمان", url = "https://bank.example")

        assertNull(AutofillMatcher.scoreOf(site, app("example.bank")))
        assertNull(AutofillMatcher.scoreOf(site, app("com.bank.example")))
        assertEquals(emptySet<String>(), site.androidPackages())
    }

    @Test
    fun theAndroidAppAttributeBindsAnEntryToItsPackages() {
        val bound = entry(
            "تطبيق المثال",
            extraFields = mapOf("AndroidApp" to "com.example.app, com.example.companion"),
        )

        assertEquals(setOf("com.example.app", "com.example.companion"), bound.androidPackages())
        assertNotNull(AutofillMatcher.scoreOf(bound, app("com.example.app")))
        assertNotNull(AutofillMatcher.scoreOf(bound, app("com.example.companion")))
        assertNull(AutofillMatcher.scoreOf(bound, app("com.example.companion.evil")))
    }

    // ------------------------------------------------------------ title matching

    @Test
    fun aTitleIsOnlyABindingWhenItIsExactlyTheDomain() {
        val exactTitle = entry("bank.example")
        val proseTitle = entry("My bank")
        val arabicProse = entry("مصرفي")
        val db = databaseOf(exactTitle, proseTitle, arabicProse)
        val target = web("login.bank.example")

        assertNotNull(AutofillMatcher.scoreOf(exactTitle, target))
        // "My bank" is prose. Any substring or word-level rule here would offer it to bank.example,
        // and to every other host with "bank" in it, on nothing but a coincidence of naming.
        assertNull(AutofillMatcher.scoreOf(proseTitle, target))
        assertNull(AutofillMatcher.scoreOf(arabicProse, target))

        assertEquals(listOf("bank.example"), titles(AutofillMatcher.match(db, target)))
    }

    @Test
    fun aTitleBindingIsComparedLiterallyAgainstTheHostAndTheDomain() {
        // The title is never reduced, only compared. That makes the rule narrow in one direction —
        // a title spelling out a subdomain does not answer a domain-level request — which is the
        // safe direction to be wrong in: it can only refuse a match, never invent one.
        val hostTitle = entry("login.bank.example")
        assertNotNull(AutofillMatcher.scoreOf(hostTitle, web("login.bank.example")))
        assertNull(AutofillMatcher.scoreOf(hostTitle, web("bank.example")))
        assertNull(AutofillMatcher.scoreOf(hostTitle, web("bank.example.evil.com")))

        // A title that is the registrable domain answers every request under that domain.
        val domainTitle = entry("bank.example")
        assertNotNull(AutofillMatcher.scoreOf(domainTitle, web("login.bank.example")))
        assertNotNull(AutofillMatcher.scoreOf(domainTitle, web("bank.example")))
        assertNull(AutofillMatcher.scoreOf(domainTitle, web("bank.example.evil.com")))
    }

    // ------------------------------------------------------------ url vocabulary

    @Test
    fun kp2aUrlAttributesAreHonouredAsAdditionalUrls() {
        // Databases shared with KeePassXC and Keepass2Android keep their extra sites in KP2A_URL*.
        // Ignoring them would silently drop bindings the user made on the desktop.
        val multi = entry(
            "مصرف الأمان",
            url = "https://primary.example",
            extraFields = mapOf(
                "KP2A_URL_1" to "https://bank.example",
                "KP2A_URL_2" to "https://portal.bank.com.sa",
                "Notes-ish" to "https://not-a-url-field.example",
            ),
        )
        val db = databaseOf(multi)

        assertEquals(
            listOf("https://primary.example", "https://bank.example", "https://portal.bank.com.sa"),
            multi.allUrls(),
        )
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, web("bank.example"))))
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, web("bank.com.sa"))))
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, web("primary.example"))))
        // A field that is not a URL slot must not become one.
        assertTrue(AutofillMatcher.match(db, web("not-a-url-field.example")).isEmpty())
    }

    @Test
    fun storedUrlsAreMatchedRegardlessOfCasePortAndPath() {
        val messy = entry("مصرف الأمان", url = "HTTPS://Login.Bank.Example:8443/signin?lang=ar")

        assertNotNull(AutofillMatcher.scoreOf(messy, web("login.bank.example")))
        assertNotNull(AutofillMatcher.scoreOf(messy, web("bank.example")))
        assertNull(AutofillMatcher.scoreOf(messy, web("login.bank.example.evil.com")))
    }

    // -------------------------------------------------------------- exclusions

    @Test
    fun entriesInTheRecycleBinAreNeverOffered() {
        val root = Group(name = "الجذر")
        val bin = Group(name = "سلة المهملات")
        val binSub = Group(name = "قديم")
        root.addGroup(bin)
        bin.addGroup(binSub)

        val discarded = entry("مصرف قديم", url = "https://bank.example")
        val deeplyDiscarded = entry("مصرف أقدم", url = "https://bank.example")
        val live = entry("مصرف الأمان", url = "https://bank.example")
        bin.addEntry(discarded)
        binSub.addEntry(deeplyDiscarded)
        root.addEntry(live)

        val db = KdbxDatabase(root = root)
        db.meta.recycleBinUuid = bin.uuid

        // A password the user threw away must not come back through a fill prompt — including from
        // a subgroup of the bin, which is where "delete a whole folder" puts things.
        assertEquals(listOf("مصرف الأمان"), titles(AutofillMatcher.match(db, web("bank.example"))))

        // The exclusion has to come from the bin, not from a match that would have failed anyway.
        assertNotNull(AutofillMatcher.scoreOf(discarded, web("bank.example")))
        assertNotNull(AutofillMatcher.scoreOf(deeplyDiscarded, web("bank.example")))
    }

    @Test
    fun anEntryWithNeitherUsernameNorPasswordIsNeverOffered() {
        // A bookmark-style entry has nothing to fill; offering it produces a dataset that pastes
        // emptiness over whatever the user had typed.
        val bookmark = entry("مصرف الأمان", url = "https://bank.example", username = "", password = "")
        val db = databaseOf(bookmark)

        assertNull(AutofillMatcher.scoreOf(bookmark, web("bank.example")))
        assertTrue(AutofillMatcher.match(db, web("bank.example")).isEmpty())

        // One of the two is enough: username-only entries still fill the username field.
        val usernameOnly = entry("مصرف الأمان", url = "https://bank.example", password = "")
        val passwordOnly = entry("مصرف الأمان", url = "https://bank.example", username = "")
        assertNotNull(AutofillMatcher.scoreOf(usernameOnly, web("bank.example")))
        assertNotNull(AutofillMatcher.scoreOf(passwordOnly, web("bank.example")))
    }

    @Test
    fun anEntryWithoutAnyBindingIsNeverOffered() {
        val unrelated = entry("بريد العمل", url = "https://mail.example")
        val noUrl = entry("ملاحظة")
        val db = databaseOf(unrelated, noUrl)

        assertTrue(AutofillMatcher.match(db, web("bank.example")).isEmpty())
        assertTrue(AutofillMatcher.match(db, app("com.example.app")).isEmpty())
    }

    // ------------------------------------------------------------------- limits

    @Test
    fun matchTruncatesToTheLimitAndKeepsTheBest() {
        val exactHost = entry("بوابة الدخول", url = "https://login.bank.example")
        val byDomain = entry("مصرف الأمان", url = "https://bank.example")
        val byTitle = entry("bank.example")
        val unrelated = entry("بريد العمل", url = "https://mail.example")
        val db = databaseOf(unrelated, byTitle, byDomain, exactHost)
        val target = web("login.bank.example")

        assertEquals(listOf("بوابة الدخول", "مصرف الأمان", "bank.example"), titles(AutofillMatcher.match(db, target)))
        assertEquals(listOf("بوابة الدخول", "مصرف الأمان"), titles(AutofillMatcher.match(db, target, limit = 2)))
        assertEquals(listOf("بوابة الدخول"), titles(AutofillMatcher.match(db, target, limit = 1)))
        assertTrue(AutofillMatcher.match(db, target, limit = 0).isEmpty())
    }

    // -------------------------------------------------------------- save-back

    @Test
    fun bindingUrlForProducesTheVocabularyTheMatcherReads() {
        assertEquals("https://login.bank.example", AutofillMatcher.bindingUrlFor(web("login.bank.example")))
        assertEquals("androidapp://com.example.app", AutofillMatcher.bindingUrlFor(app("com.example.app")))
    }

    @Test
    fun anEntrySavedWithABindingUrlMatchesTheRequestItCameFrom() {
        // Save-and-refill is the loop a user actually experiences; if these two ends disagree the
        // password the user just stored is never offered again.
        val webTarget = web("login.bank.example")
        val savedFromWeb = entry("مصرف الأمان", url = AutofillMatcher.bindingUrlFor(webTarget))
        assertNotNull(AutofillMatcher.scoreOf(savedFromWeb, webTarget))
        assertNotNull(AutofillMatcher.scoreOf(savedFromWeb, web("bank.example")))
        assertNull(AutofillMatcher.scoreOf(savedFromWeb, web("bank.example.evil.com")))

        val appTarget = app("com.example.app")
        val savedFromApp = entry("تطبيق المثال", url = AutofillMatcher.bindingUrlFor(appTarget))
        assertNotNull(AutofillMatcher.scoreOf(savedFromApp, appTarget))
        assertNull(AutofillMatcher.scoreOf(savedFromApp, app("com.example.app.evil")))
    }
}
