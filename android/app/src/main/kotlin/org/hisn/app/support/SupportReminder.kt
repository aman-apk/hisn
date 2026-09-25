/*
 * حصن (Hisn) — the Aman family support reminder, ported from Jezdan's engine.
 *
 * Family standards §5, current policy — monthly, plainly:
 *
 *  - the first reminder comes 30 days after first use — so never on a first launch;
 *  - then one every 30 days, anchored to the last one actually shown, on its own
 *    low-importance «دعم أمان» channel, no sound;
 *  - the body is the support line alone — no cadence talk, no counters, no guilt;
 *  - «ادعم» and the body land in the app; «لاحقًا» dismisses THIS notification only — the
 *    monthly cycle continues regardless. There is no in-app mute switch: the only off-switch
 *    is the system's own notification settings, which stay entirely in the user's hands and
 *    which we do not touch.
 *
 * PERMISSIONS, honestly: Hisn deliberately declares no POST_NOTIFICATIONS — a donation
 * reminder is no reason to ask for a runtime permission (Jezdan precedent). So this posts
 * only where the platform needs no permission (API 26–32); on 13+ areNotificationsEnabled()
 * is false for us and the reminder simply never fires. See the note in AndroidManifest.xml.
 *
 * State lives in its own SharedPreferences file, not in the settings DataStore: it is a
 * device fact, not a setting — nothing on the settings screen owns it, and it must not ride
 * along with anything the user exports or syncs. Days are civil epoch days (java.time is
 * native at minSdk 26), so the arithmetic matches the calendar the user lives by.
 */

package org.hisn.app.support

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.hisn.app.MainActivity
import org.hisn.app.R
import java.time.LocalDate

object SupportReminder {

    private const val FILE = "hisn_support"
    private const val KEY_FIRST_USE_DAY = "first_use_day"
    private const val KEY_LAST_REMINDER_DAY = "last_reminder_day"
    private const val KEY_EVER_UNLOCKED = "ever_unlocked"

    /** Once per process: a recreate() — language or theme change — must not run the check again. */
    @Volatile
    private var checkedThisProcess = false

    /** One channel for the whole family's reminders — same id as Jezdan's engine. */
    private const val CHANNEL_ID = "aman_support"
    private const val NOTIFICATION_ID = 4102
    const val ACTION_LATER = "org.hisn.app.support.LATER"

    /**
     * Intent extra carried by the notification's tap and «ادعم» intents. MainActivity ignores
     * it today; a future support screen can read it to land there directly, exactly as
     * Jezdan's does.
     */
    const val EXTRA_OPEN_SUPPORT = "hisn.open_support"

    /** §5 (current policy): «تذكير شهري» — one every 30 days, the first 30 days after first use. */
    private const val DAYS_BETWEEN_REMINDERS = 30L

    // NotificationPermission: POST_NOTIFICATIONS is deliberately NOT declared — notify() is
    // guarded by areNotificationsEnabled(), which is permanently false on 13+, so the call can
    // never be an unpermitted post.
    @SuppressLint("NotificationPermission")
    fun onAppOpened(context: Context) {
        if (checkedThisProcess) return
        checkedThisProcess = true

        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = LocalDate.now().toEpochDay()

        // The 30-day clock starts at first use — never a reminder on a first launch.
        var firstUse = prefs.getLong(KEY_FIRST_USE_DAY, 0L)
        if (firstUse == 0L) {
            firstUse = today
            prefs.edit().putLong(KEY_FIRST_USE_DAY, today).apply()
        }

        val lastReminder = prefs.getLong(KEY_LAST_REMINDER_DAY, 0L)
        val cycleStart = if (lastReminder != 0L) lastReminder else firstUse
        if (today - cycleStart < DAYS_BETWEEN_REMINDERS) return

        // No reminder before the vault has ever been opened successfully: an app that was
        // never actually used is not asked to be supported. Recorded by [noteUnlocked].
        if (!prefs.getBoolean(KEY_EVER_UNLOCKED, false)) return

        // Runtime-permission-safe: false wherever the user muted us — and always false on 13+
        // where the undeclared POST_NOTIFICATIONS blocks posting (see the header). Never throws.
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.support_channel_name))
                .setSound(null, null)
                .build()
        )
        manager.notify(NOTIFICATION_ID, buildNotification(context))
        prefs.edit().putLong(KEY_LAST_REMINDER_DAY, today).apply()
    }

    /** Recorded on every successful unlock; [onAppOpened] posts nothing until it has happened once. */
    fun noteUnlocked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EVER_UNLOCKED, true).apply()
    }

    /** «لاحقًا»: dismisses THIS notification only — the monthly cycle continues regardless. */
    internal fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(context: Context): Notification {
        // «ادعم» and the body both open the app directly — no trampolines (banned on 12+).
        // NEW_TASK is required from a notification context; SINGLE_TOP plus the manifest's
        // singleTask keeps a running instance from being stacked twice.
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_SUPPORT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, SupportReminderReceiver::class.java).setAction(ACTION_LATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The body is the family support line alone (standards §5) — it names nothing the
        // vault holds, so the notification is as private as the app behind it.
        val body = context.getString(R.string.support_notification_body)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.support_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.support_action_support), open)
            .addAction(0, context.getString(R.string.support_action_later), later)
            .build()
    }
}

/** Dismisses the current notification on «لاحقًا»; registered (not exported) in the manifest. */
class SupportReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SupportReminder.ACTION_LATER) {
            SupportReminder.dismiss(context)
        }
    }
}
