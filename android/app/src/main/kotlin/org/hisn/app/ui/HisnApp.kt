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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.hisn.app.R
import org.hisn.app.data.Prefs
import org.hisn.app.data.VaultState
import org.hisn.app.ui.screens.CreateVaultScreen
import org.hisn.app.ui.screens.EntryDetailScreen
import org.hisn.app.ui.screens.EntryEditScreen
import org.hisn.app.ui.screens.EntryListScreen
import org.hisn.app.ui.screens.SettingsScreen
import org.hisn.app.ui.screens.SyncScreen
import org.hisn.app.ui.screens.UnlockScreen
import org.hisn.app.ui.screens.WelcomeScreen
import java.util.UUID

object Routes {
    const val WELCOME = "welcome"
    const val UNLOCK = "unlock"
    const val CREATE = "create"
    const val LIST = "entries"
    const val DETAIL = "entries/{uuid}"
    const val EDIT_NEW = "edit"
    const val EDIT = "edit/{uuid}"
    const val SETTINGS = "settings"
    const val SYNC = "sync"

    const val ARG_UUID = "uuid"

    fun detail(uuid: UUID) = "entries/$uuid"
    fun edit(uuid: UUID) = "edit/$uuid"

    /** Screens that legitimately exist while no vault is open; locking must not tear them down. */
    val OUTSIDE_VAULT = setOf(WELCOME, UNLOCK, CREATE)
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
    // The welcome gate and the creation screen show nothing decrypted and exist precisely
    // while no vault is open, so they are exempt. Keyed on the back-stack state as well as
    // the vault state, so a restore after process death — where the NavController's stack
    // arrives after the first composition — is re-checked instead of missed.
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(status.state, backStackEntry) {
        val unlocked = status.state == VaultState.Unlocked
        val current = backStackEntry?.destination?.route
        if (unlocked && current == Routes.UNLOCK) {
            navController.navigate(Routes.LIST) {
                popUpTo(Routes.UNLOCK) { inclusive = true }
            }
        } else if (!unlocked && current != null && current !in Routes.OUTSIDE_VAULT) {
            // القفل يمسح المكدس كله لا يعلوه: popUpTo على وجهةٍ سبق أن أُزيلت لا يفعل شيئًا،
            // فكانت شاشة القفل تُكدَّس فوق القائمة — والرجوع يكشف ما تحتها للحظة ثم يعيد
            // التكديس فلا يخرج من التطبيق أبدًا. مسح المخطط كاملًا يجعلها الوجهة الوحيدة.
            navController.navigate(Routes.UNLOCK) {
                popUpTo(navController.graph.id) { inclusive = true }
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
        // Decided once, before the graph exists: a synchronous read of the same tiny mirror
        // file MainActivity already reads before the first frame, so it is warm — no
        // DataStore, no runBlocking.
        val startDestination = remember {
            if (Prefs.fastOnboarded(context)) Routes.UNLOCK else Routes.WELCOME
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    viewModel = viewModel,
                    onEnter = {
                        navController.navigate(Routes.UNLOCK) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.UNLOCK) {
                UnlockScreen(
                    viewModel = viewModel,
                    onCreateVault = { navController.navigate(Routes.CREATE) },
                )
            }

            composable(Routes.CREATE) {
                CreateVaultScreen(
                    viewModel = viewModel,
                    onCreated = {
                        // The same landing as a successful unlock: the vault is open, so the
                        // whole pre-vault stack is replaced by the entry list.
                        navController.navigate(Routes.LIST) {
                            popUpTo(Routes.UNLOCK) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
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
