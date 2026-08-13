package org.hisn.app.autofill

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.text.InputType
import android.view.View
import android.view.ViewStructure
import android.view.autofill.AutofillId
import org.hisn.app.data.VaultRepository

/**
 * What one autofill request turned out to be about.
 *
 * The submitted values are only populated for a save request; they are the user's own credentials
 * and this class deliberately keeps them out of [toString] so no stray log line can carry them.
 */
class ParsedStructure internal constructor(
    /** Who is asking — already resolved through [BrowserRegistry], so a webDomain here is trusted. */
    val target: AutofillTarget,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    /** The fields Android should offer to save, in the order they appear on screen. */
    val saveIds: List<AutofillId>,
    val submittedUsername: String?,
    val submittedPassword: String?,
) {
    /** True when there is at least one field we could put something into. */
    val fillable: Boolean get() = usernameId != null || passwordId != null

    override fun toString(): String =
        "ParsedStructure(target=${target.packageName}, username=${usernameId != null}, " +
            "password=${passwordId != null})"
}

/**
 * Turns an [AssistStructure] into "which field is the user name, which is the password, and whose
 * screen is this".
 *
 * Android hands us a tree of view nodes and no guarantee that any of them says what it is. The
 * signals are read in descending order of reliability, and the first one that speaks decides:
 *
 *  1. `autofillHints` — the app told us outright, including the W3C `autocomplete` values browsers
 *     forward (`username`, `current-password`, `new-password`).
 *  2. `inputType` — a masked field is a password whatever it is called; an email variation is a
 *     user name.
 *  3. `htmlInfo` — `type=password`, or a `name`/`id` that says user/email/login/pass.
 *  4. `idEntry` and the hint text — the last resort, matched against a deliberately small token
 *     list (in Arabic as well as English) and never allowed to promote a field whose `inputType`
 *     says it is something else.
 *
 * Two shapes that must keep working, and do:
 *  - a password field with no user name field at all (the second page of a two-step login),
 *  - a user name field the app never labelled, sitting immediately above the password field.
 */
object StructureParser {

    /** Guard rails for pathological structures; a login form is never anywhere near these. */
    private const val MAX_DEPTH = 40
    private const val MAX_FIELDS = 64

    private enum class Kind {
        Username,
        Password,

        /** A phone number: a real login on many Arabic services, but only when nothing better exists. */
        WeakUsername,

        /** Card numbers, addresses and the like: never a credential, and never picked up by adjacency. */
        Ignored,
        Unknown,
    }

    private class Field(
        val id: AutofillId,
        val index: Int,
        val kind: Kind,
        /** Present in a save request; null in a fill request, where the field is still empty. */
        val value: String?,
        val webDomain: String?,
        /** A plain text box — eligible to become the user name by sitting above the password. */
        val plainText: Boolean,
    )

    /**
     * Parses one or more structures. A fill request passes the current screen only; a save request
     * passes every screen of the session, so a user name typed on page one is still found when the
     * password arrives on page two.
     *
     * Returns null when the request tells us nothing we can act on — including when the platform
     * did not name the calling package, in which case we cannot know whose credentials these are
     * and must not offer any.
     */
    fun parse(structures: List<AssistStructure>): ParsedStructure? {
        if (structures.isEmpty()) return null

        val fields = mutableListOf<Field>()
        var packageName: String? = null

        for (structure in structures) {
            structure.activityComponent?.packageName?.takeIf { it.isNotBlank() }?.let { packageName = it }
            for (i in 0 until structure.windowNodeCount) {
                val root = structure.getWindowNodeAt(i).rootViewNode ?: continue
                collect(root, inheritedDomain = null, depth = 0, out = fields)
            }
        }

        val pkg = packageName ?: return null
        if (fields.isEmpty()) return null

        val ordered = dedupe(fields)

        val passwords = ordered.filter { it.kind == Kind.Password }
        val password = passwords.firstOrNull()
        val passwordIndex = password?.index ?: Int.MAX_VALUE

        val username = pickUsername(ordered, passwordIndex)

        // The credential fields' own domain first: a page can embed a frame from somewhere else,
        // and the site that owns the password is the one the password box belongs to.
        val webDomain = password?.webDomain
            ?: username?.webDomain
            ?: ordered.firstNotNullOfOrNull { it.webDomain }
        val target = targetFor(pkg, webDomain)

        return ParsedStructure(
            target = target,
            usernameId = username?.id,
            passwordId = password?.id,
            saveIds = listOfNotNull(username?.id, password?.id).distinct(),
            submittedUsername = username?.value,
            // A sign-up form repeats the password; either copy is the value the user submitted.
            submittedPassword = passwords.firstNotNullOfOrNull { it.value },
        )
    }

    // -- traversal ------------------------------------------------------------------------------

    /**
     * Depth-first, in declaration order, which is close enough to reading order for the adjacency
     * rule below to mean what it says.
     *
     * `webDomain` is inherited: a browser puts it on the node that holds the page, not on every
     * input inside it.
     */
    private fun collect(node: ViewNode, inheritedDomain: String?, depth: Int, out: MutableList<Field>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FIELDS) return

        val domain = node.webDomain?.takeIf { it.isNotBlank() } ?: inheritedDomain
        fieldOf(node, out.size, domain)?.let(out::add)

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            collect(child, domain, depth + 1, out)
        }
    }

    /** A node we could fill, or null. */
    private fun fieldOf(node: ViewNode, index: Int, domain: String?): Field? {
        val id = node.autofillId ?: return null
        if (node.autofillType != View.AUTOFILL_TYPE_TEXT) return null
        // An off-screen field the user cannot see is exactly where a stolen password would be
        // routed, so it is not a fill target.
        if (node.visibility != View.VISIBLE) return null
        if (!isEditable(node)) return null

        val kind = classify(node)
        if (kind == Kind.Ignored) return null

        return Field(
            id = id,
            index = index,
            kind = kind,
            value = currentText(node),
            webDomain = domain,
            plainText = isPlainText(node.inputType),
        )
    }

    /**
     * Editable *and* focusable, per the framework's own definition of a field the user types into.
     * A WebView input is accepted on its `<input>` tag, since those nodes carry no class name.
     */
    private fun isEditable(node: ViewNode): Boolean {
        val tag = node.htmlInfo?.tag?.lowercase()
        if (tag == "input" || tag == "textarea") return true
        if (node.isFocusable) return true
        val className = node.className ?: return false
        return className.contains("EditText") || className.contains("AutoCompleteTextView")
    }

    /** What the field holds right now. Only a save request has anything here. */
    private fun currentText(node: ViewNode): String? {
        val value = node.autofillValue
        val text = when {
            value != null && value.isText -> value.textValue.toString()
            else -> node.text?.toString()
        }
        return text?.takeIf { it.isNotEmpty() }
    }

    // -- classification -------------------------------------------------------------------------

    private fun classify(node: ViewNode): Kind {
        hintKind(node.autofillHints?.asList())?.let { return it }
        inputTypeKind(node.inputType)?.let { return it }
        htmlKind(node.htmlInfo, node.inputType)?.let { return it }
        tokenKind(node.idEntry, node.inputType)?.let { return it }
        tokenKind(node.hint, node.inputType)?.let { return it }
        return Kind.Unknown
    }

    /** `View.AUTOFILL_HINT_*` plus the W3C `autocomplete` values browsers pass straight through. */
    private fun hintKind(hints: List<String?>?): Kind? {
        if (hints.isNullOrEmpty()) return null
        for (raw in hints) {
            val hint = raw?.trim()?.lowercase() ?: continue
            when (hint) {
                in PASSWORD_HINTS -> return Kind.Password
                in USERNAME_HINTS -> return Kind.Username
                in WEAK_USERNAME_HINTS -> return Kind.WeakUsername
                in IGNORED_HINTS -> return Kind.Ignored
            }
        }
        return null
    }

    private fun inputTypeKind(inputType: Int): Kind? {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> return Kind.Password

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> return Kind.Username
            }
        }
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return Kind.Password
        }
        if (cls == InputType.TYPE_CLASS_PHONE) return Kind.WeakUsername
        return null
    }

    private fun htmlKind(html: ViewStructure.HtmlInfo?, inputType: Int): Kind? {
        if (html == null) return null
        val tag = html.tag.lowercase()
        if (tag != "input" && tag != "textarea") return null

        val attributes = html.attributes ?: return null
        fun attribute(name: String): String? = attributes
            .firstOrNull { it != null && name.equals(it.first, ignoreCase = true) }
            ?.second
            ?.trim()
            ?.lowercase()

        when (val type = attribute("type")) {
            "password" -> return Kind.Password
            "email" -> return Kind.Username
            "tel" -> return Kind.WeakUsername
            // A control that holds no typed text, or one the user cannot see. Filling either is
            // pointless at best and a hidden exfiltration field at worst.
            "hidden", "checkbox", "radio", "submit", "button", "image", "reset", "file",
            "range", "color", "search", "date", "datetime-local", "month", "week", "time",
            -> return Kind.Ignored

            else -> if (type != null && type != "text" && type != "number" && type != "url") return Kind.Ignored
        }

        attribute("autocomplete")?.let { value ->
            hintKind(value.split(' ', ','))?.let { return it }
        }

        return tokenKind(attribute("name"), inputType)
            ?: tokenKind(attribute("id"), inputType)
            ?: tokenKind(attribute("aria-label"), inputType)
            ?: tokenKind(attribute("placeholder"), inputType)
    }

    /**
     * The last resort: what the field is *called*.
     *
     * Password tokens are checked first and kept strict — "password", "pwd", never a bare "pass" —
     * because the failure mode here is typing the master's own secret into a field that shows it.
     * A field whose `inputType` already says it is an address, a name or an email can never be
     * promoted to a password by its name alone.
     */
    private fun tokenKind(raw: String?, inputType: Int): Kind? {
        val text = raw?.takeIf { it.isNotBlank() }?.let { VaultRepository.normalizeForSearch(it) } ?: return null
        if (PASSWORD_TOKENS.any { text.contains(it) } && !isDefinitelyNotPassword(inputType)) return Kind.Password
        if (USERNAME_TOKENS.any { text.contains(it) }) return Kind.Username
        if (WEAK_USERNAME_TOKENS.any { text.contains(it) }) return Kind.WeakUsername
        return null
    }

    private fun isDefinitelyNotPassword(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) return true
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            -> true

            else -> false
        }
    }

    /** A text box that is not masked — the shape a user name field has when nothing labels it. */
    private fun isPlainText(inputType: Int): Boolean {
        if (inputType == 0) return true // WebView inputs routinely report nothing here.
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        return inputTypeKind(inputType) != Kind.Password
    }

    // -- resolution -----------------------------------------------------------------------------

    /**
     * The user name that belongs to the password field.
     *
     * Preference order: a labelled field above the password, then any labelled field, then a phone
     * number, and finally the unlabelled text box directly above the password — the shape of every
     * login form that never got around to declaring its hints.
     */
    private fun pickUsername(fields: List<Field>, passwordIndex: Int): Field? {
        val named = fields.filter { it.kind == Kind.Username }
        named.lastOrNull { it.index < passwordIndex }?.let { return it }
        named.firstOrNull()?.let { return it }

        val weak = fields.filter { it.kind == Kind.WeakUsername }
        weak.lastOrNull { it.index < passwordIndex }?.let { return it }
        weak.firstOrNull()?.let { return it }

        if (passwordIndex == Int.MAX_VALUE) return null
        val preceding = fields.lastOrNull { it.index < passwordIndex } ?: return null
        return preceding.takeIf { it.kind == Kind.Unknown && it.plainText }
    }

    /**
     * Keeps one entry per [AutofillId] at its first position, but with the newest value: a save
     * request replays the same screen once per fill it made, and only the last copy has been typed
     * into.
     */
    private fun dedupe(fields: List<Field>): List<Field> {
        val byId = LinkedHashMap<AutofillId, Field>()
        for (field in fields) {
            val existing = byId[field.id]
            byId[field.id] = if (existing == null) {
                field
            } else {
                Field(
                    id = existing.id,
                    index = existing.index,
                    // A later pass that finally recognised the field wins over "unknown".
                    kind = if (existing.kind == Kind.Unknown) field.kind else existing.kind,
                    value = field.value ?: existing.value,
                    webDomain = existing.webDomain ?: field.webDomain,
                    plainText = existing.plainText,
                )
            }
        }
        return byId.values.sortedBy { it.index }
    }

    /**
     * App or web — the decision the whole anti-phishing story rests on.
     *
     * A `webDomain` is only believed from a package [BrowserRegistry] recognises. Any app can write
     * `bank.example` into its view nodes; only a browser has a reason to, and only the package name
     * is vouched for by the platform.
     */
    private fun targetFor(packageName: String, webDomain: String?): AutofillTarget =
        AutofillTarget.of(packageName = packageName, webDomain = webDomain)
    

    // -- vocabulary -----------------------------------------------------------------------------

    private val PASSWORD_HINTS = setOf(
        "password", "passwordauto", "current-password", "new-password", "newpassword",
        "current_password", "new_password",
    )

    private val USERNAME_HINTS = setOf(
        "username", "user-name", "user_name", "userid", "user-id", "login",
        "email", "emailaddress", "email-address",
    )

    private val WEAK_USERNAME_HINTS = setOf(
        "phone", "phonenumber", "phonenational", "tel", "tel-national",
    )

    /** Hints that name something a login never asks for. */
    private val IGNORED_HINTS = setOf(
        "creditcardnumber", "creditcardsecuritycode", "creditcardexpirationdate",
        "creditcardexpirationday", "creditcardexpirationmonth", "creditcardexpirationyear",
        "cc-number", "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year", "cc-name",
        "postaladdress", "postalcode", "address-line1", "address-line2", "postal-code",
        "smsotpcode", "sms-otp", "one-time-code", "birthdatefull", "birthdateday",
        "birthdatemonth", "birthdateyear", "gender",
    )

    // Normalised the same way the vault's own search is, so the Arabic entries match whatever
    // form of alef, ya or ta-marbuta an app happens to use in its labels.
    private val PASSWORD_TOKENS = normalized(
        "password", "passwd", "pwd", "passphrase",
        "كلمة السر", "كلمة المرور", "الرقم السري", "رمز الدخول",
    )

    private val USERNAME_TOKENS = normalized(
        "username", "user_name", "user-name", "userid", "user id", "user", "email", "e-mail",
        "mail", "login", "signin", "sign-in", "account", "identifier",
        "اسم المستخدم", "المستخدم", "مستخدم", "البريد", "بريد", "الحساب", "حساب", "الدخول",
    )

    private val WEAK_USERNAME_TOKENS = normalized(
        "phone", "mobile", "msisdn", "جوال", "هاتف", "محمول",
    )

    private fun normalized(vararg tokens: String): List<String> =
        tokens.map { VaultRepository.normalizeForSearch(it) }
}
