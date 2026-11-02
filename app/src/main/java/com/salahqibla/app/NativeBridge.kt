package com.salahqibla.app

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Exposed to the web page as `window.Android`.
 * Methods run on a background thread, so anything touching the UI is posted to the main thread.
 */
class NativeBridge(private val activity: MainActivity, private val webView: WebView) {

    private val ui = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun requestNotifications() {
        activity.requestNotificationPermission()
    }

    @JavascriptInterface
    fun notificationsGranted(): Boolean = PrayerScheduler.notificationsGranted(activity)

    @JavascriptInterface
    fun schedulePrayers(json: String) {
        PrayerScheduler.save(activity, json)
    }

    /** Returns "ok" when the bubble is active, or "needs" when the overlay permission is missing. */
    @JavascriptInterface
    fun setBubble(enabled: Boolean): String {
        if (!enabled) {
            PrayerScheduler.setBubbleEnabled(activity, false)
            UnlockService.stop(activity)
            return "ok"
        }
        if (!Settings.canDrawOverlays(activity)) {
            ui.post {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return "needs"
        }
        PrayerScheduler.setBubbleEnabled(activity, true)
        UnlockService.start(activity)
        return "ok"
    }

    @JavascriptInterface
    fun downloadedIds(reciter: String): String = AudioStore.downloadedIds(activity, reciter)

    @JavascriptInterface
    fun downloadAyahs(reciter: String, json: String): Boolean =
        AudioStore.enqueue(activity, reciter, json) { done, total, failed, finished ->
            ui.post {
                webView.evaluateJavascript(
                    "window.__dl&&window.__dl($done,$total,$failed,$finished)",
                    null
                )
            }
        }

    @JavascriptInterface
    fun deleteAudio(reciter: String) {
        AudioStore.delete(activity, reciter)
    }
}
