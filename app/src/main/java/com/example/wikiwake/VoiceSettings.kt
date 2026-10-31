package com.example.wikiwake

import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object VoiceSettings {
    const val KEY_VOICE = "voice_name"
    const val KEY_RATE = "speech_rate"
    const val KEY_PITCH = "speech_pitch"

    // Stable order, so "Voice 2" stays the same voice between visits.
    fun voicesFor(engine: TextToSpeech, language: String): List<Voice> =
        (engine.voices ?: emptySet())
            .filter { it.locale.language == language && !it.features.orEmpty().contains("notInstalled") }
            .sortedBy { it.name }

    fun label(voice: Voice, index: Int): String {
        val kind = if (voice.isNetworkConnectionRequired) "online" else "offline"
        return "Voice ${index + 1} · $kind"
    }

    private val samples = mapOf(
        "en" to "Good morning! Here is today's article.",
        "es" to "¡Buenos días! Este es el artículo de hoy.",
        "fr" to "Bonjour ! Voici l'article du jour.",
        "de" to "Guten Morgen! Hier ist der heutige Artikel.",
        "it" to "Buongiorno! Ecco l'articolo di oggi.",
        "pt" to "Bom dia! Aqui está o artigo de hoje.",
        "nl" to "Goedemorgen! Hier is het artikel van vandaag.",
        "sv" to "God morgon! Här är dagens artikel.",
        "no" to "God morgen! Her er dagens artikkel.",
        "da" to "God morgen! Her er dagens artikel.",
        "fi" to "Hyvää huomenta! Tässä on päivän artikkeli.",
        "pl" to "Dzień dobry! Oto dzisiejszy artykuł.",
        "cs" to "Dobré ráno! Tady je dnešní článek.",
        "hu" to "Jó reggelt! Itt a mai cikk.",
        "ro" to "Bună dimineața! Iată articolul de azi.",
        "el" to "Καλημέρα! Αυτό είναι το σημερινό άρθρο.",
        "tr" to "Günaydın! İşte bugünün makalesi.",
        "ru" to "Доброе утро! Вот сегодняшняя статья.",
        "uk" to "Доброго ранку! Ось сьогоднішня стаття.",
        "ar" to "صباح الخير! هذه مقالة اليوم.",
        "he" to "בוקר טוב! הנה הכתבה של היום.",
        "fa" to "صبح بخیر! این مقاله امروز است.",
        "hi" to "सुप्रभात! यह आज का लेख है।",
        "bn" to "শুভ সকাল! এটি আজকের নিবন্ধ।",
        "id" to "Selamat pagi! Ini artikel hari ini.",
        "vi" to "Chào buổi sáng! Đây là bài viết hôm nay.",
        "th" to "อรุณสวัสดิ์! นี่คือบทความของวันนี้",
        "ja" to "おはようございます。今日の記事です。",
        "ko" to "좋은 아침입니다! 오늘의 문서입니다.",
        "zh" to "早上好！这是今天的文章。",
        "ca" to "Bon dia! Aquest és l'article d'avui."
    )

    fun sample(language: String): String = samples[language] ?: samples.getValue("en")

    fun apply(engine: TextToSpeech, preferences: SharedPreferences, language: String) {
        val saved = preferences.getString(KEY_VOICE, "").orEmpty()
        if (saved.isNotEmpty()) {
            voicesFor(engine, language).firstOrNull { it.name == saved }?.let { engine.voice = it }
        }
        engine.setSpeechRate(preferences.getInt(KEY_RATE, 100) / 100f)
        engine.setPitch(preferences.getInt(KEY_PITCH, 100) / 100f)
    }
}
