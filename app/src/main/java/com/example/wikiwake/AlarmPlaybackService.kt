package com.example.wikiwake

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.io.IOException
import java.util.Locale

class AlarmPlaybackService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechEngine: TextToSpeech? = null
    private var fetchThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var playbackId = 0
    private var speechChunks: List<Pair<Int, String>> = emptyList()
    private var speechChunkIndex = 0
    private var fromAlarm = false
    private var fallbackPlayer: MediaPlayer? = null
    private var currentArticle: WikipediaArticle? = null
    private var currentImage: Bitmap? = null
    private var greet = false
    private var spokenText = ""
    @Volatile private var paused = false
    @Volatile private var spokenOffset = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishPlayback()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            togglePause()
            return START_NOT_STICKY
        }

        playbackId++
        val requestId = playbackId
        if (intent?.action != ACTION_NEXT) {
            fromAlarm = intent?.getBooleanExtra(EXTRA_FROM_ALARM, false) == true
        }
        greet = fromAlarm && intent?.action != ACTION_NEXT
        paused = false
        currentArticle = null
        currentImage = null
        ReadingSession.prepare()
        fetchThread?.interrupt()
        stopSpeechEngine()
        releaseFallbackPlayer()
        AlarmNotifications.ensureChannel(this)
        startForeground(
            AlarmNotifications.NOTIFICATION_ID,
            android.app.Notification.Builder(this, AlarmNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Wiki Wake")
                .setContentText("Finding today's Wikipedia article…")
                .setCategory(android.app.Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .build()
        )
        acquireWakeLock()

        val preferences = getSharedPreferences(MainActivity.PREFERENCES, MODE_PRIVATE)
        val language = preferences.getString(MainActivity.LANGUAGE, "").orEmpty()
        val topics = preferences.getString(MainActivity.TOPICS, "").orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val randomness = preferences.getInt(MainActivity.RANDOMNESS, 0)
        val history = preferences.getStringSet(MainActivity.READ_TITLES, emptySet()).orEmpty().toSet()

        fetchThread = Thread {
            try {
                val article = fetchWithRetry(language, topics, history, randomness)
                val image = article.imageUrl?.let {
                    try { WikipediaClient().fetchBitmap(it) } catch (error: Exception) { null }
                }
                mainHandler.post {
                    if (requestId == playbackId) {
                        currentImage = image
                        startSpeaking(article, requestId, history)
                    }
                }
            } catch (error: IOException) {
                mainHandler.post {
                    if (requestId == playbackId) fail(error.message ?: "Check your internet connection.")
                }
            } catch (error: org.json.JSONException) {
                mainHandler.post {
                    if (requestId == playbackId) fail("Wikipedia returned an unreadable article.")
                }
            }
        }.apply {
            name = "WikiWakeArticleFetch"
            start()
        }
        return START_NOT_STICKY
    }

    // Alarms retry so a brief network hiccup doesn't skip the article.
    private fun fetchWithRetry(language: String, topics: List<String>, history: Set<String>, randomness: Int): WikipediaArticle {
        val attempts = if (fromAlarm) 4 else 1
        var lastError: IOException? = null
        for (attempt in 1..attempts) {
            try {
                return WikipediaClient().fetch(language, topics, history, randomness)
            } catch (error: IOException) {
                lastError = error
                if (attempt < attempts) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        throw IOException("Cancelled")
                    }
                }
            }
        }
        throw lastError ?: IOException("Couldn't load an article.")
    }

    private fun startSpeaking(article: WikipediaArticle, requestId: Int, history: Set<String>) {
        val preferences = getSharedPreferences(MainActivity.PREFERENCES, MODE_PRIVATE)
        currentArticle = article
        postNotification()

        val languageCode = preferences.getString(MainActivity.LANGUAGE, "").orEmpty()
            .ifBlank { Locale.getDefault().language }
        speechEngine = TextToSpeech(this) { status ->
            mainHandler.post {
                if (requestId != playbackId) return@post
                val engine = speechEngine ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    fail("Text-to-speech couldn't start. Check your installed voice.")
                    return@post
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val languageResult = engine.setLanguage(Locale.forLanguageTag(languageCode))
                if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    fail("Install a $languageCode text-to-speech voice in Android settings.")
                    return@post
                }
                VoiceSettings.apply(engine, preferences, Locale.forLanguageTag(languageCode).language)

                val body = limitReadingTime(
                    SpeechText.clean(article.title, article.extract),
                    preferences.getInt(MainActivity.MAX_MINUTES, 0)
                )
                spokenText = if (greet) {
                    SpeechText.intro(Locale.forLanguageTag(languageCode).language) + "\n\n" + body
                } else body
                speechChunks = splitForSpeech(spokenText, TextToSpeech.getMaxSpeechInputLength())
                ReadingSession.begin(article.title, article.url, spokenText, currentImage)
                preferences.edit()
                    .putStringSet(MainActivity.READ_TITLES, (history + article.title).toSet())
                    .apply()
                speechChunkIndex = 0
                spokenOffset = 0
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        val index = utteranceId?.substringAfterLast('-')?.toIntOrNull() ?: return
                        val offset = speechChunks.getOrNull(index - 1)?.first ?: return
                        if (!paused) spokenOffset = offset + start
                        ReadingSession.postUpdate(offset + start, offset + end)
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (requestId == playbackId && !paused) speakNextChunk(requestId)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (requestId == playbackId && !paused) fail("Text-to-speech playback failed.")
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            if (requestId == playbackId && !paused) fail("Text-to-speech playback failed.")
                        }
                    }
                })
                speakNextChunk(requestId)
            }
        }
    }

    private fun postNotification() {
        val article = currentArticle ?: return
        AlarmNotifications.showAlarm(
            this,
            article.title,
            if (paused) "Paused" else "Reading today's Wikipedia article",
            article.url,
            currentImage,
            paused,
            controls = true
        )
    }

    // TTS can't pause, so stop and later restart from the word being spoken.
    private fun togglePause() {
        val engine = speechEngine ?: return
        if (currentArticle == null || speechChunks.isEmpty()) return
        if (!paused) {
            paused = true
            engine.stop()
        } else {
            paused = false
            val from = spokenOffset.coerceIn(0, spokenText.length)
            speechChunks = splitForSpeech(spokenText.substring(from), TextToSpeech.getMaxSpeechInputLength())
                .map { (offset, chunk) -> (offset + from) to chunk }
            speechChunkIndex = 0
            speakNextChunk(playbackId)
        }
        ReadingSession.setPaused(paused)
        postNotification()
    }

    private fun speakNextChunk(requestId: Int) {
        val engine = speechEngine ?: return
        if (requestId != playbackId) return
        if (speechChunkIndex >= speechChunks.size) {
            finishPlayback()
            return
        }
        val chunk = speechChunks[speechChunkIndex++].second
        val result = engine.speak(
            chunk,
            TextToSpeech.QUEUE_FLUSH,
null,
            "wikiwake-$requestId-${speechChunkIndex}"
        )
        if (result == TextToSpeech.ERROR) fail("Text-to-speech couldn't read the article.")
    }

    private fun splitForSpeech(text: String, maxLength: Int): List<Pair<Int, String>> {
        val limit = maxLength.coerceAtLeast(500)
        val chunks = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < text.length) {
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            val raw = text.substring(start, end)
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) chunks += (start + raw.length - raw.trimStart().length) to trimmed
            start = end
        }
        return chunks
    }

    // Cuts at a word boundary but keeps line breaks so headings stay visible in the reader.
    private fun limitReadingTime(text: String, maxMinutes: Int): String {
        if (maxMinutes <= 0) return text
        val wordLimit = maxMinutes * WORDS_PER_MINUTE
        val words = Regex("\\S+").findAll(text).toList()
        return if (words.size > wordLimit) text.substring(0, words[wordLimit - 1].range.last + 1) else text
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WikiWake:article")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    // Real alarms must still wake the user when no article can be read.
    private fun startFallbackRingtone(message: String) {
        if (fallbackPlayer != null) return
        stopSpeechEngine()
        ReadingSession.finish()
        AlarmNotifications.showAlarm(this, "Wiki Wake alarm", "No article today: $message")
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            fallbackPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmPlaybackService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (error: Exception) {
            releaseFallbackPlayer()
            AlarmNotifications.showError(this, message)
            finishPlayback()
        }
    }

    private fun releaseFallbackPlayer() {
        fallbackPlayer?.release()
        fallbackPlayer = null
    }

    private fun fail(message: String) {
        if (fromAlarm) {
            startFallbackRingtone(message)
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        AlarmNotifications.showError(this, message)
        finishPlayback()
    }

    private fun stopSpeechEngine() {
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
    }

    private fun finishPlayback() {
        playbackId++
        ReadingSession.finish()
        fetchThread?.interrupt()
        fetchThread = null
        stopSpeechEngine()
        releaseFallbackPlayer()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSpeechEngine()
        releaseFallbackPlayer()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.wikiwake.STOP_READING"
        const val ACTION_TOGGLE_PAUSE = "com.example.wikiwake.TOGGLE_PAUSE"
        const val ACTION_NEXT = "com.example.wikiwake.NEXT_ARTICLE"
        const val EXTRA_FROM_ALARM = "from_alarm"
        private const val WORDS_PER_MINUTE = 150
        private const val RETRY_DELAY_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
