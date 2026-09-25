package org.hisn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.drop
import org.hisn.app.data.Prefs
import org.hisn.app.data.ThemeMode
import org.hisn.app.ui.HisnApp
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.theme.HisnTheme
import java.util.Locale

/**
 * The single activity.
 *
 * It extends [FragmentActivity] because `androidx.biometric`'s prompt is hosted by a
 * fragment; a plain ComponentActivity cannot show it.
 */
class MainActivity : FragmentActivity() {

    private lateinit var viewModel: VaultViewModel

    /** The screen turning off is the strongest hint that the user has walked away. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) viewModel.onScreenOff()
        }
    }

    private var receiverRegistered = false

    /** The language the resources of this activity instance were resolved with. */
    private var appliedLanguage: String = "system"

    /**
     * Applies the stored interface language ([org.hisn.app.data.HisnSettings.language])
     * before any resource is resolved. The value comes from the tiny synchronous mirror
     * [Prefs] maintains alongside every persist, so the very first frame is in the right
     * language without blocking startup on a DataStore read.
     */
    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = Prefs.fastLanguage(newBase)
        super.attachBaseContext(newBase.withAppLanguage(appliedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // محرك تذكير الدعم الموحد (سنة العائلة): يسجل أول استخدام ويطلق التذكير الشهري
        // حيث تكون الإشعارات متاحة أصلاً — لا يطلب إذناً أبداً.
        org.hisn.app.support.SupportReminder.onAppOpened(this)

        // Keeps passwords out of screenshots, screen recordings and the recents thumbnail.
        // Set before the first frame, then reconciled with the stored preference below, so
        // there is never a window where the app is capturable by default.
        applySecureFlag(block = true)
        // Edge-to-edge the family way: transparent bars on every version, content drawn
        // behind them, ownership of the pixels decided by insets rather than bar colours.
        // Called again below once the stored theme preference is known.
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[VaultViewModel::class.java]

        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = when (settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            // The bar icons follow the *chosen* theme, not the device's: a light in-app
            // theme on a dark device (and vice versa) must still get readable icons, on
            // every Android version. The bars themselves stay transparent throughout.
            SideEffect {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }

            LaunchedEffect(settings.blockScreenshots) {
                applySecureFlag(settings.blockScreenshots)
            }

            // The language is baked into the resources at attachBaseContext, so changing it
            // means recreating the activity. drop(1) skips whatever value is current when
            // collection starts — possibly the not-yet-loaded default — so only an actual
            // change, made on the welcome screen or elsewhere, triggers the recreate.
            LaunchedEffect(Unit) {
                viewModel.settings.drop(1).collect { loaded ->
                    if (loaded.language != appliedLanguage) recreate()
                }
            }

            HisnTheme(themeMode = settings.theme) {
                HisnApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
        if (!receiverRegistered) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            receiverRegistered = false
        }
        // Backing out of the app destroys the ViewModel and every timer it owns, so a
        // finishing activity locks the vault right now — lock() also clears the clipboard.
        if (isFinishing) {
            viewModel.lock()
            return
        }
        // A rotation is not the user leaving, so it must not start the auto-lock countdown.
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    private fun Context.withAppLanguage(tag: String): Context {
        // "system" (or anything unrecognised) keeps the device locale untouched.
        if (tag != "ar" && tag != "en") return this
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return createConfigurationContext(config)
    }

    private fun applySecureFlag(block: Boolean) {
        if (block) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
