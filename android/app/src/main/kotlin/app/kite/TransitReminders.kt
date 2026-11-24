package app.kite

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TransitReminders {
    private const val CHANNEL = "transit-reminders"
    const val OPEN_PLAN = "app.kite.OPEN_TRANSIT_PLAN"
    const val PLAN = "plan"
    private const val ALARM = "app.kite.TRANSIT_DEPARTURE_ALARM"
    private const val FIVE_MINUTES = 5 * 60_000L

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canScheduleExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun toggleDeparture(context: Context, plan: SavedPlan) {
        val now = System.currentTimeMillis()
        val depart = plan.transitDeparture?.takeIf { it > now } ?: return
        val updated = plan.copy(reminderAt = if (plan.reminderAt?.let { it > now } == true) null
            else (depart - FIVE_MINUTES).coerceAtLeast(now + 1000))
        AppSettings.savedPlans = AppSettings.savedPlans.map { if (it.id == plan.id) updated else it }
        cancel(context, plan.id)
        schedule(context, updated)
    }

    fun schedule(context: Context, plan: SavedPlan) {
        val time = plan.reminderAt?.takeIf { it > System.currentTimeMillis() } ?: return
        val mgr = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context, plan.id)
        if (canScheduleExactly(context) &&
            runCatching { mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending) }.isSuccess) return
        mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, id))
    }

    private fun alarm(context: Context, id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, TransitReminderReceiver::class.java).setAction(ALARM)
            .setData(Uri.Builder().scheme("kite").authority("transit-reminder").appendPath(id).build()).putExtra(PLAN, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun onAlarm(context: Context, id: String) {
        val plan = AppSettings.savedPlans.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val reminder = plan.reminderAt ?: return
        if (now < reminder - 1000) return
        AppSettings.savedPlans = AppSettings.savedPlans.map { if (it.id == id) it.copy(reminderAt = null) else it }
        if (plan.transitDeparture?.let { it >= now } == true) {
            notify(context, "Time to leave", plan.name, id)
        }
    }

    fun exit(text: String) = notify(KiteApp.app, "Upcoming exit", text, null)

    private fun notify(context: Context, title: String, text: String, plan: String?) {
        if (!canNotify(context)) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Transit reminders", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (plan != null) intent.setAction(OPEN_PLAN).putExtra(PLAN, plan)
        val conversation = PendingIntent.getActivity(context, plan?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shortcut_car).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(conversation)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).build()
        runCatching { mgr.notify("kite-transit", plan?.hashCode() ?: 0, notification) }
    }
}

class TransitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                AppSettings.savedPlans.forEach { TransitReminders.schedule(context, it) }
            else -> intent.getStringExtra(TransitReminders.PLAN)?.let { TransitReminders.onAlarm(context, it) }
        }
    }
}
