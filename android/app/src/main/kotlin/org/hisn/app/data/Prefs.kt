package org.hisn.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * A single DataStore instance per process, guaranteed by the property delegate — creating a
 * second one for the same file throws at runtime, so every [Prefs] shares this one.
 */
private val Context.hisnSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "hisn_settings")

enum class ThemeMode {
    System, Dark, Light;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: System
    }
}

/**
 * Everything the settings screen owns. Defaults are the safe choices: lock quickly, hide
 * secrets, keep the clipboard short-lived.
 */
data class HisnSettings(
    /** Idle seconds before the vault locks itself. [AUTO_LOCK_NEVER] disables it, 0 locks on background. */
    val autoLockSeconds: Int = 60,
    val lockOnScreenOff: Boolean = true,
    /** Seconds a copied password stays on the clipboard; 0 keeps it until overwritten. */
    val clipboardClearSeconds: Int = 30,
    val hidePasswords: Boolean = true,
    /** FLAG_SECURE on every window: no screenshots, nothing in the recents thumbnail. */
    /**
     * حجب لقطة الشاشة — **مطفأ افتراضياً** (أمر المالك 2026-09-07: تُلغى إعاقة اللقطات
     * في العائلة كلها). بقي الإعداد في الشاشة لمن يريد رفعه بنفسه في مدير كلمات سرّ.
     */
    val blockScreenshots: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    /**
     * Interface language: "system", "ar" or "en". Applied in MainActivity.attachBaseContext,
     * which is why the value also has to be readable before the first frame.
     */
    val language: String = "system",
    /** Mirrors the user's intent; the actual wrapped key lives in [SecureStore]. */
    val biometricUnlock: Boolean = false,
    val searchIncludesRecycleBin: Boolean = false,
    /** Name announced to the desktop during sync. Defaults to the hardware model. */
    val deviceName: String = "",
    /** Offer to sync with the last paired desktop right after unlocking. */
    val syncAfterUnlock: Boolean = false,
)

/** DataStore-backed settings. Every setter suspends; [settings] emits on every change. */
class Prefs(context: Context) {

    private val store = context.applicationContext.hisnSettingsStore

    /**
     * A tiny synchronous mirror of the two values that must be readable before the first
     * frame — the interface language ([android.app.Activity.attachBaseContext]) and the
     * welcome-gate flag (the nav graph's start destination). Written alongside every
     * DataStore persist; DataStore remains the source of truth for everything else.
     */
    private val fastPrefs =
        context.applicationContext.getSharedPreferences(FAST_FILE, Context.MODE_PRIVATE)

    private val defaultDeviceName: String = listOfNotNull(
        Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
        Build.MODEL?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { "Hisn" }

    val settings: Flow<HisnSettings> = store.data.catch { cause ->
        // A settings file that cannot be read must not take the app down with it; the defaults
        // are all safe, and the next write repairs the file.
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }.map { prefs ->
        HisnSettings(
            autoLockSeconds = prefs[Keys.AUTO_LOCK] ?: 60,
            lockOnScreenOff = prefs[Keys.LOCK_ON_SCREEN_OFF] ?: true,
            clipboardClearSeconds = prefs[Keys.CLIPBOARD_CLEAR] ?: 30,
            hidePasswords = prefs[Keys.HIDE_PASSWORDS] ?: true,
            blockScreenshots = prefs[Keys.BLOCK_SCREENSHOTS] ?: false,
            theme = ThemeMode.fromKey(prefs[Keys.THEME]),
            language = prefs[Keys.LANGUAGE] ?: "system",
            biometricUnlock = prefs[Keys.BIOMETRIC] ?: false,
            searchIncludesRecycleBin = prefs[Keys.SEARCH_RECYCLE_BIN] ?: false,
            deviceName = prefs[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultDeviceName,
            syncAfterUnlock = prefs[Keys.SYNC_AFTER_UNLOCK] ?: false,
        )
    }

    /** One-shot read, for code that cannot collect a flow (auto-lock checks, sync handshake). */
    suspend fun current(): HisnSettings = settings.first()

    suspend fun setAutoLockSeconds(seconds: Int) = put(Keys.AUTO_LOCK, seconds.coerceAtLeast(AUTO_LOCK_NEVER))

    suspend fun setLockOnScreenOff(enabled: Boolean) = put(Keys.LOCK_ON_SCREEN_OFF, enabled)

    suspend fun setClipboardClearSeconds(seconds: Int) = put(Keys.CLIPBOARD_CLEAR, seconds.coerceIn(0, 600))

    suspend fun setHidePasswords(hide: Boolean) = put(Keys.HIDE_PASSWORDS, hide)

    suspend fun setBlockScreenshots(block: Boolean) = put(Keys.BLOCK_SCREENSHOTS, block)

    suspend fun setTheme(mode: ThemeMode) = put(Keys.THEME, mode.name)

    suspend fun setLanguage(tag: String) {
        // The mirror first: the value must be readable synchronously on the very next launch.
        fastPrefs.edit().putString(FAST_KEY_LANGUAGE, tag).apply()
        put(Keys.LANGUAGE, tag)
    }

    /**
     * Whether the welcome gate has been passed. Deliberately not part of [HisnSettings]:
     * it is not a setting the settings screen owns, just a one-time launch fact.
     */
    suspend fun isOnboarded(): Boolean = store.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }.first()[Keys.ONBOARDED] ?: false

    suspend fun setOnboarded() {
        // Mirrored like the language: the start destination is decided before DataStore loads.
        fastPrefs.edit().putBoolean(FAST_KEY_ONBOARDED, true).apply()
        put(Keys.ONBOARDED, true)
    }

    suspend fun setBiometricUnlock(enabled: Boolean) = put(Keys.BIOMETRIC, enabled)

    suspend fun setSearchIncludesRecycleBin(include: Boolean) = put(Keys.SEARCH_RECYCLE_BIN, include)

    suspend fun setDeviceName(name: String) = put(Keys.DEVICE_NAME, name.trim().take(64))

    suspend fun setSyncAfterUnlock(enabled: Boolean) = put(Keys.SYNC_AFTER_UNLOCK, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private object Keys {
        val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        val LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val CLIPBOARD_CLEAR = intPreferencesKey("clipboard_clear_seconds")
        val HIDE_PASSWORDS = booleanPreferencesKey("hide_passwords")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val BIOMETRIC = booleanPreferencesKey("biometric_unlock")
        val SEARCH_RECYCLE_BIN = booleanPreferencesKey("search_recycle_bin")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val SYNC_AFTER_UNLOCK = booleanPreferencesKey("sync_after_unlock")
    }

    companion object {
        /** The synchronous mirror file — see [fastPrefs]. Deliberately tiny, holds no secrets. */
        private const val FAST_FILE = "hisn_lang"
        private const val FAST_KEY_LANGUAGE = "language"
        private const val FAST_KEY_ONBOARDED = "onboarded"

        /** The stored interface language, without touching DataStore — safe in attachBaseContext. */
        fun fastLanguage(context: Context): String =
            context.applicationContext.getSharedPreferences(FAST_FILE, Context.MODE_PRIVATE)
                .getString(FAST_KEY_LANGUAGE, null) ?: "system"

        /** Whether the welcome gate has been passed, without touching DataStore. */
        fun fastOnboarded(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FAST_FILE, Context.MODE_PRIVATE)
                .getBoolean(FAST_KEY_ONBOARDED, false)

        const val AUTO_LOCK_NEVER = -1

        /** Values offered by the settings screen, in seconds. */
        val AUTO_LOCK_CHOICES = listOf(0, 30, 60, 300, 900, AUTO_LOCK_NEVER)

        val CLIPBOARD_CLEAR_CHOICES = listOf(0, 15, 30, 60, 120)
    }
}
