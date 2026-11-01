package com.example.wikiwake

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

// Optional pool of pre-downloaded articles so the alarm works without internet.
object OfflineMode {
    const val ENABLED = "offline_mode"
    const val POOL_SIZE = 10
    private const val REFILL_JOB = 7001
    private const val DAILY_JOB = 7002
    private const val FILE_NAME = "offline_pool.json"
    private const val MAX_FETCH_ATTEMPTS = 30

    private class Entry(val article: WikipediaArticle, val language: String, val fingerprint: String)

    private val lock = Any()

    fun enabled(preferences: SharedPreferences) = preferences.getBoolean(ENABLED, false)

    fun language(preferences: SharedPreferences): String =
        preferences.getString(MainActivity.LANGUAGE, "").orEmpty().trim().ifEmpty { Locale.getDefault().language }
            .lowercase(Locale.ROOT)

    // Any change to these four settings makes cached articles stale.
    fun fingerprint(preferences: SharedPreferences): String = listOf(
        language(preferences),
        preferences.getString(MainActivity.TOPICS, "").orEmpty().trim().lowercase(Locale.ROOT),
        preferences.getInt(MainActivity.RANDOMNESS, 0),
        preferences.getInt(MainActivity.POPULARITY, 0)
    ).joinToString("|")

    fun readyCount(context: Context, preferences: SharedPreferences): Int = synchronized(lock) {
        val fingerprint = fingerprint(preferences)
        load(context).count { it.fingerprint == fingerprint }
    }

    // Prefers articles matching current settings; never returns one in another language.
    fun take(context: Context, preferences: SharedPreferences): WikipediaArticle? = synchronized(lock) {
        val entries = load(context).toMutableList()
        val fingerprint = fingerprint(preferences)
        val language = language(preferences)
        val entry = entries.firstOrNull { it.fingerprint == fingerprint }
            ?: entries.firstOrNull { it.language == language }
            ?: return null
        entries.remove(entry)
        save(context, entries)
        entry.article
    }

    fun clear(context: Context) = synchronized(lock) {
        File(context.filesDir, FILE_NAME).delete()
        imageDir(context).deleteRecursively()
    }

    private fun imageDir(context: Context) = File(context.filesDir, "offline_images")

    private fun imageFile(context: Context, language: String, title: String) =
        File(imageDir(context), "${(language + title).hashCode()}.jpg")

    // Loads the stored thumbnail of an article just taken from the pool, then deletes it.
    fun takeImage(context: Context, article: WikipediaArticle, language: String): Bitmap? = synchronized(lock) {
        val file = imageFile(context, language, article.title)
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        file.delete()
        bitmap
    }

    fun scheduleRefill(context: Context) = schedule(context, REFILL_JOB, periodic = false)

    fun ensureDaily(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(DAILY_JOB) == null) schedule(context, DAILY_JOB, periodic = true)
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(REFILL_JOB)
        scheduler.cancel(DAILY_JOB)
    }

    private fun schedule(context: Context, id: Int, periodic: Boolean) {
        val builder = JobInfo.Builder(id, ComponentName(context, OfflineRefillService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
        if (periodic) builder.setPeriodic(24L * 60 * 60 * 1000)
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
    }

    // Blocking; runs on a background thread.
    fun refill(context: Context) {
        val preferences = context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)
        if (!enabled(preferences)) return
        val fingerprint = fingerprint(preferences)
        val language = language(preferences)
        val topics = preferences.getString(MainActivity.TOPICS, "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
        val randomness = preferences.getInt(MainActivity.RANDOMNESS, 0)
        val popularity = preferences.getInt(MainActivity.POPULARITY, 0)
        val client = WikipediaClient()
        var failures = 0
        var attempts = 0
        while (attempts++ < MAX_FETCH_ATTEMPTS && failures < 3) {
            val (valid, known) = synchronized(lock) {
                val entries = load(context)
                entries.count { it.fingerprint == fingerprint } to entries.map { it.article.title }.toSet()
            }
            if (valid >= POOL_SIZE) break
            val history = preferences.getStringSet(MainActivity.READ_TITLES, emptySet()).orEmpty() + known
            try {
                val article = client.fetch(language, topics, history, randomness, popularity)
                val image = article.imageUrl?.let { client.fetchBitmap(it) }
                synchronized(lock) {
                    val entries = load(context).toMutableList()
                    if (entries.none { it.article.title == article.title && it.language == language }) {
                        entries += Entry(article, language, fingerprint)
                        if (image != null) {
                            imageDir(context).mkdirs()
                            imageFile(context, language, article.title).outputStream().use {
                                image.compress(Bitmap.CompressFormat.JPEG, 80, it)
                            }
                        }
                        save(context, entries)
                    }
                }
            } catch (error: IOException) {
                failures++
            }
        }
        prune(context, preferences)
    }

    // Drops other languages always, and stale settings once the pool is full again.
    private fun prune(context: Context, preferences: SharedPreferences) = synchronized(lock) {
        val fingerprint = fingerprint(preferences)
        val language = language(preferences)
        val entries = load(context).filter { it.language == language }
        val fresh = entries.filter { it.fingerprint == fingerprint }
        val kept = if (fresh.size >= POOL_SIZE) fresh else fresh + (entries - fresh.toSet()).take(POOL_SIZE - fresh.size)
        save(context, kept)
        val keep = kept.map { imageFile(context, it.language, it.article.title).name }.toSet()
        imageDir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun load(context: Context): List<Entry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Entry(
                    WikipediaArticle(
                        item.getString("title"),
                        item.getString("extract"),
                        item.getString("url"),
                        item.optString("image").ifEmpty { null }
                    ),
                    item.getString("language"),
                    item.getString("fingerprint")
                )
            }
        } catch (error: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("title", it.article.title)
                    .put("extract", it.article.extract)
                    .put("url", it.article.url)
                    .put("image", it.article.imageUrl.orEmpty())
                    .put("language", it.language)
                    .put("fingerprint", it.fingerprint)
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }
}

class OfflineRefillService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                OfflineMode.refill(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
