package app.getpursue.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.getpursue.R
import app.getpursue.ui.activities.GroupDetailActivity

/**
 * BroadcastReceiver for 15-minute pre-session slot reminders.
 *
 * Receives ACTION_SLOT_REMINDER with slot metadata and posts a local notification.
 * BOOT_COMPLETED re-scheduling is handled here when the device restarts.
 */
class SlotAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SLOT_REMINDER = "app.getpursue.SLOT_REMINDER"
        const val EXTRA_SLOT_ID = "extra_slot_id"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_SCHEDULED_START = "extra_scheduled_start"

        private const val CHANNEL_ID = "pursue_default"
        private const val TAG = "SlotAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SLOT_REMINDER -> handleReminder(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> {
                // On reboot, reschedule pending alarms from stored slots.
                // In a full production implementation we'd query local storage
                // or re-fetch from the server. Logged here for future wiring.
                Log.d(TAG, "BOOT_COMPLETED received — slot alarm rescheduling is a no-op until local persistence is added.")
            }
        }
    }

    private fun handleReminder(context: Context, intent: Intent) {
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: return
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: return
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: context.getString(R.string.app_name)

        Log.d(TAG, "Slot reminder fired: slot=$slotId group=$groupId")

        val contentIntent = Intent(context, GroupDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
            putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            slotId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(R.string.slot_reminder_notification_body, groupName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pursue_logo)
            .setContentTitle(context.getString(R.string.slot_reminder_notification_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationId = slotId.hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
