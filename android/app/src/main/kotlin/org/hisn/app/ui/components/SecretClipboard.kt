package org.hisn.app.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hisn.app.data.ClipboardClearReceiver

/**
 * Clipboard that forgets.
 *
 * Copying a secret puts it in a global buffer every other app can read, so Hisn marks the
 * clip sensitive (Android 13+ then keeps it out of the paste preview and the clipboard
 * history) and wipes it again after a timeout.
 *
 * The clearing job must outlive the screen that started the copy — the user typically
 * leaves the app immediately after copying — so this is owned by the view model and driven
 * by [scope], never by a composition-scoped coroutine scope.
 */
class SecretClipboard(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val manager: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var clearJob: Job? = null

    /**
     * @param clearAfterSeconds seconds until the clip is wiped; 0 or less keeps it forever.
     * @return false when the platform clipboard is unavailable, so the caller can tell the user.
     */
    fun copy(label: String, value: String, clearAfterSeconds: Int): Boolean {
        val clipboard = manager ?: return false
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)

        clearJob?.cancel()
        if (clearAfterSeconds > 0) {
            // Two layers: the coroutine wipes punctually while the process lives, and the
            // alarm's manifest receiver wipes (approximately) on time even if Android kills
            // the process first. Whichever fires second finds an already-empty clipboard.
            ClipboardClearReceiver.schedule(context, clearAfterSeconds * 1000L)
            clearJob = scope.launch {
                delay(clearAfterSeconds * 1000L)
                clearIfStillOurs(value)
                ClipboardClearReceiver.cancel(context)
            }
        } else {
            // A copy without a timeout supersedes the previous secret, so its alarm goes too.
            ClipboardClearReceiver.cancel(context)
        }
        return true
    }

    /** Called when the vault locks: a locked vault must not leave a secret on the clipboard. */
    fun clearNow() {
        clearJob?.cancel()
        clearJob = null
        ClipboardClearReceiver.cancel(context)
        val clipboard = manager ?: return
        wipe(clipboard)
    }

    private fun clearIfStillOurs(value: String) {
        val clipboard = manager ?: return
        val current = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        // Something else has been copied since; overwriting it would be rude and surprising.
        if (current != null && current != value) return
        wipe(clipboard)
    }

    private fun wipe(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            // Pre-28 has no clear API; an empty clip is the closest equivalent.
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
