package io.zerosettle.justone.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "justone_reminders"
private const val WORK_NAME = "justone_eod_reminder"
private const val NOTIF_ID = 4201

object Reminders {
    /** Schedule (or reschedule) the daily reminder for [hour]:[minute] device-local time. */
    fun schedule(ctx: Context, hour: Int, minute: Int) {
        ensureChannel(ctx)
        val now = LocalDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMs = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
            System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    internal fun ensureChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT,
        )
        ContextCompat.getSystemService(ctx, NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Don't break your streak")
                .setContentText("Log today's habits before the day ends.")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        }
        // Reschedule for the same time tomorrow.
        val now = LocalDateTime.now()
        Reminders.schedule(ctx, now.hour, now.minute)
        return Result.success()
    }
}
