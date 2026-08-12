package org.hisn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.hisn.app.R
import org.hisn.app.data.VaultState
import org.hisn.app.ui.screens.EntryDetailScreen
import org.hisn.app.ui.screens.EntryEditScreen
import org.hisn.app.ui.screens.EntryListScreen
import org.hisn.app.ui.screens.SettingsScreen
import org.hisn.app.ui.screens.SyncScreen
import org.hisn.app.ui.screens.UnlockScreen
import java.util.UUID

object Routes {
    const val UNLOCK = "unlock"
    const val LIST = "entries"
    const val DETAIL = "entries/{uuid}"
    const val EDIT_NEW = "edit"
    const val EDIT = "edit/{uuid}"
    const val SETTINGS = "settings"
    const val SYNC = "sync"

    const val ARG_UUID = "uuid"

    fun detail(uuid: UUID) = "entries/$uuid"
    fun edit(uuid: UUID) = "edit/$uuid"
}

@Composable
fun HisnApp(viewModel: VaultViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val status by viewModel.status.collectAsState()

    // Every failure and confirmation from the view model surfaces here; nothing is dropped.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is UiEvent.Message ->
                    if (event.args.isEmpty()) {
                        context.getString(event.message)
                    } else {
                        context.getString(event.message, *event.args.toTypedArray())
                    }

                is UiEvent.CopiedWithTimer -> {
                    val copied = context.getString(event.message)
                    val note = context.getString(
                        R.string.msg_clipboard_clears_in,
                        event.seconds.toString(),
                    )
                    "$copied — $note"
                }

                is UiEvent.Failure -> {
                    val headline = context.getString(event.message)
                    if (event.detail.isNullOrBlank()) headline else "$headline — ${event.detail}"
                }
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // Locking is a state change, not a navigation: whenever the vault closes, the whole
    // browsing stack must go with it so no screen can keep rendering decrypted material.
    LaunchedEffect(status.state) {
        val unlocked = status.state == VaultState.Unlocked
        val current = navController.currentBackStackEntry?.destination?.route
        if (unlocked && current == Routes.UNLOCK) {
            navController.navigate(Routes.LIST) {
                popUpTo(Routes.UNLOCK) { inclusive = true }
            }
        } else if (!unlocked && current != null && current != Routes.UNLOCK) {
            navController.navigate(Routes.UNLOCK) {
                popUpTo(Routes.UNLOCK) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Feeds the idle timer from anywhere in the app. The Initial pass is used and the
            // event is never consumed, so this observes touches without stealing any.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.noteActivity()
                    }
                }
            }
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.UNLOCK,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.UNLOCK) {
                UnlockScreen(viewModel = viewModel)
            }

            composable(Routes.LIST) {
                EntryListScreen(
                    viewModel = viewModel,
                    onOpenEntry = { navController.navigate(Routes.detail(it)) },
                    onCreateEntry = { navController.navigate(Routes.EDIT_NEW) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                )
            }

            composable(Routes.DETAIL) { backStackEntry ->
                val uuid = backStackEntry.arguments?.getString(Routes.ARG_UUID).toUuidOrNull()
                if (uuid == null) {
                    navController.popBackStackOnce()
                } else {
                    EntryDetailScreen(
                        viewModel = viewModel,
                        entryUuid = uuid,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(Routes.edit(it)) },
                    )
                }
            }

            composable(Routes.EDIT_NEW) {
                EntryEditScreen(
                    viewModel = viewModel,
                    entryUuid = null,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }

            composable(Routes.EDIT) { backStackEntry ->
                val uuid = backStackEntry.arguments?.getString(Routes.ARG_UUID).toUuidOrNull()
                if (uuid == null) {
                    navController.popBackStackOnce()
                } else {
                    EntryEditScreen(
                        viewModel = viewModel,
                        entryUuid = uuid,
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SYNC) {
                SyncScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

private fun String?.toUuidOrNull(): UUID? =
    this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** A malformed route argument can only mean a stale deep link; step back rather than crash. */
@Composable
private fun NavHostController.popBackStackOnce() {
    LaunchedEffect(Unit) { popBackStack() }
}
