package org.hisn.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DomainReducer] — the step that decides *which site* a URL belongs to.
 *
 * Everything the autofill service does about web requests rests on this pair of functions: the
 * matcher compares reduced domains, and a wrong reduction is not a cosmetic bug — reducing
 * `bank.example.evil.com` to `bank.example` would hand the bank's password to the attacker who
 * registered `evil.com`. So the tests below are as much about what must *not* come out as about
 * what must.
 *
 * The reducer is deliberately narrower than the real public-suffix list; the cases here pin the
 * rules it does implement (strip `www.`, keep two labels, keep three for a known multi-part
 * suffix) rather than pretending it is a full PSL.
 */
class DomainReducerTest {

    // ----------------------------------------------------------------- hostOf

    @Test
    fun hostOfExtractsTheHostFromAFullUrl() {
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example"))
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example/"))
        assertEquals("login.bank.example", DomainReducer.hostOf("https://login.bank.example/signin"))
        assertEquals("bank.example", DomainReducer.hostOf("http://bank.example/login?next=/accounts"))
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example#top"))
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example?lang=ar"))
    }

    @Test
    fun hostOfDropsThePortAndTheCredentials() {
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example:8443"))
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example:8443/login?q=1"))
        // Credentials in the authority are the classic display trick: what precedes the final '@'
        // is a username, never the host. "https://bank.example@evil.com" must reduce to evil.com.
        assertEquals("bank.example", DomainReducer.hostOf("https://sara@bank.example/"))
        assertEquals("bank.example", DomainReducer.hostOf("https://sara:secret@bank.example:8443/x"))
        assertEquals("evil.com", DomainReducer.hostOf("https://bank.example@evil.com/login"))
    }

    @Test
    fun hostOfAcceptsABareHost() {
        assertEquals("bank.example", DomainReducer.hostOf("bank.example"))
        assertEquals("login.bank.example", DomainReducer.hostOf("login.bank.example"))
        // A bare host with a port, as a webDomain attribute can carry it.
        assertEquals("bank.example", DomainReducer.hostOf("bank.example:8443"))
    }

    @Test
    fun hostOfNormalisesCaseSurroundingSpaceAndATrailingDot() {
        assertEquals("bank.example", DomainReducer.hostOf("HTTPS://Bank.Example/Login"))
        assertEquals("bank.example", DomainReducer.hostOf("BANK.EXAMPLE"))
        assertEquals("bank.example", DomainReducer.hostOf("  bank.example  "))
        // The fully-qualified spelling "bank.example." is the same host as "bank.example"; without
        // trimming the dot the two would compare unequal and a valid entry would silently not match.
        assertEquals("bank.example", DomainReducer.hostOf("bank.example."))
        assertEquals("bank.example", DomainReducer.hostOf("https://bank.example./login"))
    }

    @Test
    fun hostOfReturnsNullWhenThereIsNoHost() {
        assertNull(DomainReducer.hostOf(null))
        assertNull(DomainReducer.hostOf(""))
        assertNull(DomainReducer.hostOf("   "))
        assertNull(DomainReducer.hostOf("https://"))
        // A lone dot trims away to nothing; it must not become an empty host that matches everything.
        assertNull(DomainReducer.hostOf("."))
    }

    // ------------------------------------------------------- registrableDomain

    @Test
    fun registrableDomainStripsSubdomains() {
        assertEquals("bank.example", DomainReducer.registrableDomain("login.bank.example"))
        assertEquals("bank.example", DomainReducer.registrableDomain("www.bank.example"))
        assertEquals("bank.example", DomainReducer.registrableDomain("secure.login.bank.example"))
        assertEquals("bank.example", DomainReducer.registrableDomain("bank.example"))
    }

    @Test
    fun registrableDomainWorksFromAFullUrl() {
        assertEquals("bank.example", DomainReducer.registrableDomain("https://login.bank.example/signin?x=1"))
        assertEquals("bank.co.uk", DomainReducer.registrableDomain("https://sara@login.bank.co.uk:8443/x"))
        assertEquals("bank.example", DomainReducer.registrableDomain("HTTPS://WWW.Bank.Example/"))
    }

    @Test
    fun registrableDomainKeepsThreeLabelsUnderAMultiPartSuffix() {
        // Two labels would leave "co.uk" / "com.sa" / "gov.eg" — a public suffix, shared by every
        // registrant under it. An entry reduced to a bare suffix would match every site in the
        // country, which is the widest possible leak this class can cause.
        assertEquals("example.co.uk", DomainReducer.registrableDomain("shop.example.co.uk"))
        assertEquals("bank.com.sa", DomainReducer.registrableDomain("bank.com.sa"))
        assertEquals("bank.com.sa", DomainReducer.registrableDomain("www.bank.com.sa"))
        assertEquals("bank.com.sa", DomainReducer.registrableDomain("online.bank.com.sa"))
        assertEquals("y.gov.eg", DomainReducer.registrableDomain("x.y.gov.eg"))
        assertEquals("bank.com.ae", DomainReducer.registrableDomain("login.bank.com.ae"))
    }

    @Test
    fun aLookalikeHostReducesToTheAttackersDomainNotTheImitatedOne() {
        // The whole point of the reduction: the label sequence "bank.example" appearing anywhere to
        // the left is decoration. Only the registrable domain identifies the owner.
        assertEquals("evil.com", DomainReducer.registrableDomain("bank.example.evil.com"))
        assertEquals("evil.com", DomainReducer.registrableDomain("www.bank.example.evil.com"))
        assertEquals("evil.co.uk", DomainReducer.registrableDomain("bank.example.evil.co.uk"))
        // A single label glued on with a hyphen is a different domain too.
        assertEquals("bank-example.com", DomainReducer.registrableDomain("bank-example.com"))
    }

    @Test
    fun anIpAddressIsReturnedWhole() {
        // An IP has no registrable domain; truncating 192.168.1.10 to "1.10" would be meaningless
        // and would make unrelated hosts on the LAN compare equal.
        assertEquals("192.168.1.10", DomainReducer.registrableDomain("192.168.1.10"))
        assertEquals("192.168.1.10", DomainReducer.registrableDomain("http://192.168.1.10:8080/router"))
        assertEquals("10.0.0.1", DomainReducer.registrableDomain("10.0.0.1"))
    }

    @Test
    fun aBareLabelIsHandledWithoutCrashing() {
        assertEquals("localhost", DomainReducer.registrableDomain("localhost"))
        assertEquals("localhost", DomainReducer.registrableDomain("http://localhost:8080/"))
        assertEquals("router", DomainReducer.registrableDomain("router"))
    }

    @Test
    fun registrableDomainIsNullWhenThereIsNoHost() {
        assertNull(DomainReducer.registrableDomain(null))
        assertNull(DomainReducer.registrableDomain(""))
        assertNull(DomainReducer.registrableDomain("   "))
        assertNull(DomainReducer.registrableDomain("."))
        assertNull(DomainReducer.registrableDomain("https://"))
    }
}
