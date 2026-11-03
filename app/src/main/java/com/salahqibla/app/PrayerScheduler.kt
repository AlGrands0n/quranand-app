package com.salahqibla.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Keeps prayer alarms in sync with the schedule computed by the web page.
 * The page sends a JSON payload which is stored, so alarms can be rebuilt after a reboot.
 */
object PrayerScheduler {
    const val CH_PRAYER = "prayer_alert"             // silent: the adhan service plays the sound
    const val CH_PRAYER_FALLBACK = "prayer_fallback" // alarm tone, only if the adhan service cannot start
    const val CH_ADHAN_RUN = "adhan_run"             // quiet notification shown while the adhan plays
    const val CH_SERVICE = "bubble"
    const val CH_PLAYBACK = "playback"
    const val CH_DOWNLOADS = "downloads"

    private val OLD_CHANNELS = listOf("prayer", "adhan_alarm")
    private const val PREFS = "inaba"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_IDS = "scheduled"
    private const val KEY_BUBBLE = "bubble"
    private const val KEY_BGPLAY = "bgplay"
    const val ACTION_PRAYER = "com.inaba.app.PRAYER"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val prayer = NotificationChannel(CH_PRAYER, ctx.getString(R.string.ch_prayer), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_prayer_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val fallback = NotificationChannel(CH_PRAYER_FALLBACK, ctx.getString(R.string.ch_prayer_fallback), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_prayer_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val run = NotificationChannel(CH_ADHAN_RUN, ctx.getString(R.string.ch_adhan_run), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        val service = NotificationChannel(CH_SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
            description = ctx.getString(R.string.ch_service_desc)
            setShowBadge(false)
        }
        val playback = NotificationChannel(CH_PLAYBACK, ctx.getString(R.string.ch_playback), NotificationManager.IMPORTANCE_LOW)
        val downloads = NotificationChannel(CH_DOWNLOADS, ctx.getString(R.string.ch_downloads), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannels(listOf(prayer, fallback, run, service, playback, downloads))
    }

    fun notificationsGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun bubbleEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BUBBLE, false)

    fun setBubbleEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BUBBLE, on).apply()
    }

    fun bgPlayEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BGPLAY, false)

    fun setBgPlayEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BGPLAY, on).apply()
    }

    /** Called from the web page with the next few days of prayer times. */
    fun save(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_PAYLOAD, json).apply()
        reschedule(ctx)
    }

    /** A prayer notification. Silent on the normal path, because the adhan service plays the sound. */
    @Suppress("MissingPermission")
    fun postPrayerNotification(ctx: Context, id: Int, title: String, body: String, channel: String = CH_PRAYER) {
        if (!notificationsGranted(ctx)) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }

    private fun alarmIntent(ctx: Context, id: Int, title: String?, body: String?, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, PrayerReceiver::class.java)
            .setAction(ACTION_PRAYER)
            .putExtra("id", id)
            .putExtra("title", title)
            .putExtra("body", body)
        val flags = PendingIntent.FLAG_IMMUTABLE or
                (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, id, intent, flags)
    }

    fun reschedule(ctx: Context) {
        val p = prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)

        // Cancel the previous set, then schedule the new one.
        p.getStringSet(KEY_IDS, emptySet())?.forEach { raw ->
            raw.toIntOrNull()?.let { id -> alarmIntent(ctx, id, null, null, false)?.let { am.cancel(it) } }
        }

        val json = p.getString(KEY_PAYLOAD, null) ?: return
        val obj = JSONObject(json)
        val scheduled = mutableSetOf<String>()
        if (obj.optBoolean("enabled") && notificationsGranted(ctx)) {
            val arr = obj.optJSONArray("items")
            val now = System.currentTimeMillis()
            val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            // Newest first: the system displays the last alarm set as the "next alarm",
            // so the earliest one is set last.
            val due = (0 until (arr?.length() ?: 0))
                .mapNotNull { arr?.getJSONObject(it) }
                .filter { it.getLong("t") > now }
                .sortedByDescending { it.getLong("t") }
            for (item in due) {
                val id = item.getInt("id")
                val at = item.getLong("t")
                val pi = alarmIntent(ctx, id, item.getString("title"), item.optString("body"), true) ?: continue
                // An alarm clock is exact, works in Doze, and lets the adhan service start.
                // It shows an alarm icon in the status bar.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                scheduled.add(id.toString())
            }
        }
        p.edit().putStringSet(KEY_IDS, scheduled).apply()
    }
}

/** Fires at prayer time: starts the adhan service, or falls back to a notification with the alarm tone. */
class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: ""
        val id = intent.getIntExtra("id", 0)
        PrayerScheduler.ensureChannels(ctx)
        val svc = Intent(ctx, AdhanService::class.java)
            .putExtra("id", id)
            .putExtra("title", title)
            .putExtra("body", body)
        try {
            ctx.startForegroundService(svc)
        } catch (_: Exception) {
            // The system refused to start the service: show a notification with the alarm tone instead.
            PrayerScheduler.postPrayerNotification(ctx, id, title, body, PrayerScheduler.CH_PRAYER_FALLBACK)
        }
    }
}

/** Rebuilds alarms and the unlock bubble after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PrayerScheduler.reschedule(ctx)
        if (PrayerScheduler.bubbleEnabled(ctx) && Settings.canDrawOverlays(ctx)) {
            try { UnlockService.start(ctx) } catch (_: Exception) { /* the system may refuse background starts */ }
        }
    }
}
