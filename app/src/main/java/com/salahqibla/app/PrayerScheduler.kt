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
import android.net.Uri
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
    // Sound is fixed when a channel is created, so the id changes whenever the sound does.
    const val CH_PRAYER = "adhan_alarm"
    const val CH_SERVICE = "bubble"
    const val CH_PLAYBACK = "playback"
    const val CH_DOWNLOADS = "downloads"

    private const val OLD_PRAYER_CHANNEL = "prayer"
    private const val PREFS = "miqat"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_IDS = "scheduled"
    private const val KEY_BUBBLE = "bubble"
    private const val KEY_BGPLAY = "bgplay"
    const val ACTION_PRAYER = "com.miqat.app.PRAYER"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_PRAYER_CHANNEL)

        // Adhan channel: plays through the alarm stream, so it is audible even when
        // the ringer is on vibrate or the notification volume is low.
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val prayer = NotificationChannel(CH_PRAYER, ctx.getString(R.string.ch_prayer), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_prayer_desc)
            setSound(adhanUri(ctx), alarmAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val service = NotificationChannel(CH_SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
            description = ctx.getString(R.string.ch_service_desc)
            setShowBadge(false)
        }
        val playback = NotificationChannel(CH_PLAYBACK, ctx.getString(R.string.ch_playback), NotificationManager.IMPORTANCE_LOW)
        val downloads = NotificationChannel(CH_DOWNLOADS, ctx.getString(R.string.ch_downloads), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannels(listOf(prayer, service, playback, downloads))
    }

    /**
     * The adhan sound: res/raw/adhan.mp3 when the file is added to the project,
     * otherwise the system alarm tone.
     */
    private fun adhanUri(ctx: Context): Uri {
        val id = ctx.resources.getIdentifier("adhan", "raw", ctx.packageName)
        return if (id != 0) Uri.parse("android.resource://${ctx.packageName}/$id")
        else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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
            val items = obj.optJSONArray("items")
            val now = System.currentTimeMillis()
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.getJSONObject(i)
                val at = item.getLong("t")
                if (at <= now) continue
                val id = item.getInt("id")
                val pi = alarmIntent(ctx, id, item.getString("title"), item.optString("body"), true) ?: continue
                // Inexact but allowed during Doze, so no exact-alarm permission is needed.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                scheduled.add(id.toString())
            }
        }
        p.edit().putStringSet(KEY_IDS, scheduled).apply()
    }
}

/** Shows the prayer notification when its alarm fires. */
class PrayerReceiver : BroadcastReceiver() {
    @Suppress("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: ""
        val id = intent.getIntExtra("id", 0)
        PrayerScheduler.ensureChannels(ctx)
        if (!PrayerScheduler.notificationsGranted(ctx)) return

        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        // Sound and vibration come from the channel, so they follow the adhan setting.
        val notification = NotificationCompat.Builder(ctx, PrayerScheduler.CH_PRAYER)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, notification)
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
