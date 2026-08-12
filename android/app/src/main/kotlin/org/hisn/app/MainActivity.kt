package org.hisn.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import org.hisn.app.ui.HisnApp
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.ThemeMode

/**
 * The single activity.
 *
 * It extends [FragmentActivity] because `androidx.biometric`'s prompt is hosted by a
 * fragment; a plain ComponentActivity cannot show it.
 */
class MainActivity : FragmentActivity() {

    private lateinit var viewModel: VaultViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps passwords out of screenshots, screen recordings and the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[VaultViewModel::class.java]

        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            HisnTheme(themeMode = settings.themeMode) {
                HisnApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // A rotation is not the user leaving, so it must not start the auto-lock countdown.
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }
}
