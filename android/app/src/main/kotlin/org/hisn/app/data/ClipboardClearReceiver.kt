package org.hisn.app.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Wipes the primary clip when the clipboard deadline arrives after the app's process has died.
 *
 * The in-process coroutine in [org.hisn.app.ui.components.SecretClipboard] is the primary
 * mechanism and cancels this alarm when it wins; the receiver is the safety net for the case
 * where Android killed the process before the timer fired — a copied password must not outlive
 * its timeout just because the app did.
 */
class ClipboardClearReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Android 10+ hides clip *contents* from background apps, so there is no way to check
        // the clip is still ours before wiping; clearing unconditionally errs on the safe side,
        // and the alarm only exists while a secret's timeout is pending.
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    companion object {
        /** One alarm per app: a new copy replaces the previous deadline, exactly like the clip. */
        private const val REQUEST_CODE = 7201

        /** A window this wide needs no exact-alarm permission; Android 12+ may stretch it. */
        private const val WINDOW_MILLIS = 60_000L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, ClipboardClearReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Arms the wipe alarm [delayMillis] from now.
         *
         * Deliberately inexact: an exact alarm ([AlarmManager.setExactAndAllowWhileIdle])
         * needs SCHEDULE_EXACT_ALARM on this target, and the vault permission guard — rightly
         * — refuses any new permission. A window of about a minute is exactly the precision a
         * safety net needs; the in-process coroutine remains the punctual layer.
         */
        fun schedule(context: Context, delayMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMillis,
                WINDOW_MILLIS,
                pendingIntent(context),
            )
        }

        /** Disarms the alarm; called when the in-process wipe won or the clip was replaced. */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = pendingIntent(context)
            alarmManager.cancel(intent)
            intent.cancel()
        }
    }
}
