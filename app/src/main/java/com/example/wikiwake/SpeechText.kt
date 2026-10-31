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
