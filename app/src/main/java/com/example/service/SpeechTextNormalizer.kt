package com.example.service

/**
 * Unified Speech-Text Normalizer for TTS.
 * 
 * Protects the original visible document: visible text remains completely unchanged.
 * Normalizes only the text passed to Text-to-Speech:
 * - Strips decorative and formatting symbols (markdown, asterisks, underscores, decorative dashes).
 * - Normalizes arithmetic and ratio operators:
 *     "5/10" -> "5 out of 10"
 *     "5\10" -> "5 out of 10"
 *     "5÷10" -> "5 divided by 10"
 *     "5 + 2" -> "5 plus 2"
 *     "5 - 2" or "5 − 2" -> "5 minus 2"
 *     "-5" or "−5" -> "negative 5"
 *     "5–10" or "5-10" (range) -> "5 to 10"
 *     "cat-mouse" -> "cat mouse" (no "dash" pronounced)
 */
object SpeechTextNormalizer {

    data class NormalizedSpeech(
        val spokenText: String,
        val sourceWordMap: List<SourceWordMapping>
    )

    data class SourceWordMapping(
        val sourceWordIndex: Int,
        val originalWord: String,
        val spokenWord: String,
        val spokenCharStart: Int,
        val spokenCharEnd: Int
    )

    fun normalize(text: String): NormalizedSpeech {
        if (text.isBlank()) {
            return NormalizedSpeech("", emptyList())
        }

        val originalWords = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val mappings = mutableListOf<SourceWordMapping>()
        val spokenBuilder = StringBuilder()

        for ((idx, rawWord) in originalWords.withIndex()) {
            val normalizedToken = normalizeSingleToken(rawWord)
            if (normalizedToken.isNotBlank()) {
                val start = spokenBuilder.length
                if (spokenBuilder.isNotEmpty()) {
                    spokenBuilder.append(" ")
                }
                val tokenStart = spokenBuilder.length
                spokenBuilder.append(normalizedToken)
                val tokenEnd = spokenBuilder.length

                mappings.add(
                    SourceWordMapping(
                        sourceWordIndex = idx,
                        originalWord = rawWord,
                        spokenWord = normalizedToken,
                        spokenCharStart = tokenStart,
                        spokenCharEnd = tokenEnd
                    )
                )
            }
        }

        val spokenText = spokenBuilder.toString()
        return NormalizedSpeech(spokenText, mappings)
    }

    /**
     * Normalizes a single token/word contextually.
     */
    fun normalizeSingleToken(raw: String): String {
        var str = raw.trim()

        // 1. Remove markdown markers: **word**, *word*, __word__, _word_, `word`
        str = str.replace(Regex("^\\*\\*(.*?)\\*\\*$"), "$1")
        str = str.replace(Regex("^\\*(.*?)\\*$"), "$1")
        str = str.replace(Regex("^__(.*?)__$"), "$1")
        str = str.replace(Regex("^_(.*?)_$"), "$1")
        str = str.replace(Regex("^`(.*?)`$"), "$1")

        // 2. Decorative bullet points & formatting characters
        str = str.replace(Regex("^[•·*~#_—–-]+\$"), "")

        // 3. Negative numbers: -5 or −5 -> "negative 5"
        if (str.matches(Regex("^[-−]\\d+(\\.\\d+)?$"))) {
            return "negative " + str.substring(1)
        }

        // 4. Numeric operators & expressions
        // Ratios: 5/10 or 5\10 -> "5 out of 10"
        if (str.contains("/") || str.contains("\\")) {
            str = str.replace(Regex("(\\d+)[/\\\\](\\d+)"), "$1 out of $2")
        }

        // Division: 5÷10 -> "5 divided by 10"
        if (str.contains("÷")) {
            str = str.replace(Regex("(\\d+)÷(\\d+)"), "$1 divided by $2")
        }

        // Addition: 5+2 -> "5 plus 2"
        if (str.contains("+")) {
            str = str.replace(Regex("(\\d+)\\+(\\d+)"), "$1 plus $2")
        }

        // En-dash Numeric range: 5–10 -> "5 to 10"
        if (str.contains("–")) {
            str = str.replace(Regex("(\\d+)–(\\d+)"), "$1 to $2")
        }

        // Minus / subtraction vs range:
        // If 5-2 -> check if second number is larger (range) or smaller (minus), or contains minus
        if (str.contains("-") || str.contains("−")) {
            val hyphenMatch = Regex("(\\d+)[-−](\\d+)").find(str)
            if (hyphenMatch != null) {
                val n1 = hyphenMatch.groupValues[1].toIntOrNull()
                val n2 = hyphenMatch.groupValues[2].toIntOrNull()
                if (n1 != null && n2 != null) {
                    if (n2 > n1) {
                        str = str.replace(hyphenMatch.value, "$n1 to $n2")
                    } else {
                        str = str.replace(hyphenMatch.value, "$n1 minus $n2")
                    }
                }
            }
        }

        // 5. Hyphenated word separators: cat-mouse -> "cat mouse" (no "dash")
        if (str.matches(Regex("^[a-zA-Z]+[-–][a-zA-Z]+$"))) {
            str = str.replace(Regex("[-–]"), " ")
        }

        // Clean out stray decorative symbols while preserving letters, numbers, and standard sentence punctuation
        str = str.replace(Regex("[*#_~`^|\\[\\]{}<>]"), "")

        return str.trim()
    }
}
