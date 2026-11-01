package com.example.wikiwake

object SpeechText {
    private val heading = Regex("^\\s*(={2,6})\\s*(.*?)\\s*\\1\\s*$")
    private val citation = Regex("\\[(\\d+|[a-z]|citation needed|note \\d+)]", RegexOption.IGNORE_CASE)
    private val emptyParens = Regex("\\(\\s*[,;:]?\\s*\\)")
    private val spaces = Regex("[ \\t]+")
    private val spaceBeforePunct = Regex("\\s+([,.;:!?])")

    // Sections that are lists of links or references, not prose worth hearing.
    private val skippedSections = setOf(
        "references", "see also", "external links", "further reading", "notes", "footnotes",
        "sources", "bibliography", "citations", "gallery", "footnotes and references",
        "referencias", "véase también", "enlaces externos", "notas", "bibliografía",
        "références", "voir aussi", "liens externes", "notes et références", "bibliographie",
        "einzelnachweise", "literatur", "weblinks", "siehe auch", "anmerkungen",
        "note", "voci correlate", "altri progetti", "collegamenti esterni", "bibliografia",
        "referências", "ver também", "ligações externas", "notas e referências",
        "ссылки", "примечания", "литература", "см. также"
    )

    private val intros = mapOf(
        "en" to "Good morning. Your article for today is:",
        "es" to "Buenos días. Tu artículo de hoy es:",
        "fr" to "Bonjour. Votre article du jour est :",
        "de" to "Guten Morgen. Dein Artikel für heute ist:",
        "it" to "Buongiorno. Il tuo articolo di oggi è:",
        "pt" to "Bom dia. O seu artigo de hoje é:",
        "nl" to "Goedemorgen. Je artikel voor vandaag is:",
        "sv" to "God morgon. Din artikel för idag är:",
        "no" to "God morgen. Din artikkel for i dag er:",
        "da" to "God morgen. Din artikel for i dag er:",
        "fi" to "Hyvää huomenta. Päivän artikkelisi on:",
        "pl" to "Dzień dobry. Twój dzisiejszy artykuł to:",
        "cs" to "Dobré ráno. Váš dnešní článek je:",
        "hu" to "Jó reggelt. A mai cikked:",
        "ro" to "Bună dimineața. Articolul tău de azi este:",
        "el" to "Καλημέρα. Το άρθρο σου για σήμερα είναι:",
        "tr" to "Günaydın. Bugünkü makaleniz:",
        "ru" to "Доброе утро. Ваша статья на сегодня:",
        "uk" to "Доброго ранку. Ваша стаття на сьогодні:",
        "ar" to "صباح الخير. مقالتك لهذا اليوم هي:",
        "he" to "בוקר טוב. הכתבה שלך להיום היא:",
        "fa" to "صبح بخیر. مقاله امروز شما:",
        "hi" to "सुप्रभात। आज का आपका लेख है:",
        "bn" to "শুভ সকাল। আজকের আপনার নিবন্ধ:",
        "id" to "Selamat pagi. Artikel Anda hari ini adalah:",
        "vi" to "Chào buổi sáng. Bài viết hôm nay của bạn là:",
        "th" to "อรุณสวัสดิ์ บทความของคุณวันนี้คือ",
        "ja" to "おはようございます。今日の記事は、",
        "ko" to "좋은 아침입니다. 오늘의 문서는",
        "zh" to "早上好。你今天的文章是：",
        "ca" to "Bon dia. El teu article d'avui és:"
    )

    fun intro(language: String): String = intros[language] ?: intros.getValue("en")

    fun clean(title: String, raw: String): String {
        val out = StringBuilder()
        out.append(title).append(".\n\n")
        var skipping = false
        for (line in raw.lines()) {
            val match = heading.matchEntire(line)
            if (match != null) {
                val name = match.groupValues[2].trim()
                // A top-level (==) heading decides skipping; subsections inherit it.
                if (match.groupValues[1].length == 2) skipping = name.lowercase() in skippedSections
                if (!skipping && name.isNotEmpty()) out.append(name.trimEnd('.')).append(".\n")
                continue
            }
            if (skipping) continue
            val text = tidy(line)
            if (text.isNotEmpty()) out.append(text).append('\n')
        }
        return out.toString().trim()
    }

    private fun tidy(line: String): String =
        line.replace(citation, "")
            .replace(emptyParens, "")
            .replace(spaces, " ")
            .replace(spaceBeforePunct, "$1")
            .trim()
}
