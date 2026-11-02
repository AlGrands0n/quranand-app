package com.salahqibla.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Stores recitation files in filesDir/audio/<reciter>/<globalAyah>.mp3.
 * The web page plays them through https://appassets.androidplatform.net/audio/...
 * which WebViewAssetLoader maps to this folder.
 */
object AudioStore {
    private val worker = Executors.newSingleThreadExecutor()

    private fun root(ctx: Context) = File(ctx.filesDir, "audio").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun dirFor(ctx: Context, reciter: String) = File(root(ctx), safe(reciter)).apply { mkdirs() }

    /** Comma-separated global ayah numbers already on the device. */
    fun downloadedIds(ctx: Context, reciter: String): String {
        val names = File(root(ctx), safe(reciter)).list() ?: return ""
        return names.mapNotNull { it.removeSuffix(".mp3").toIntOrNull() }.joinToString(",")
    }

    /**
     * Queues the files in [json] (an array of {g, url}). Runs one at a time in the background
     * and reports progress through [progress]: done, total, failed, finished.
     */
    fun enqueue(ctx: Context, reciter: String, json: String, progress: (Int, Int, Int, Boolean) -> Unit): Boolean {
        val items = try { JSONArray(json) } catch (e: Exception) { return false }
        val dir = dirFor(ctx, reciter)
        val total = items.length()
        worker.execute {
            var done = 0
            var failed = 0
            for (i in 0 until total) {
                val item = items.getJSONObject(i)
                val target = File(dir, "${item.getInt("g")}.mp3")
                if (!target.exists() && !fetch(item.getString("url"), target)) failed++
                done++
                if (done % 5 == 0 || done == total) progress(done, total, failed, false)
            }
            progress(done, total, failed, true)
        }
        return true
    }

    fun delete(ctx: Context, reciter: String) {
        File(root(ctx), safe(reciter)).deleteRecursively()
    }

    private fun fetch(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                tmp.delete()
                false
            } else {
                conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }
}
