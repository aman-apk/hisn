package org.hisn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import org.hisn.app.data.ThemeMode
import org.hisn.app.ui.HisnApp
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.theme.HisnTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps passwords out of screenshots, screen recordings and the recents thumbnail.
        // Set before the first frame, then reconciled with the stored preference below, so
        // there is never a window where the app is capturable by default.
        applySecureFlag(block = true)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[VaultViewModel::class.java]

        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = when (settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            LaunchedEffect(settings.blockScreenshots) {
                applySecureFlag(settings.blockScreenshots)
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
        // A rotation is not the user leaving, so it must not start the auto-lock countdown.
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
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
