package com.example.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.VocabularyEntry
import java.io.File
import java.util.UUID

object VocabularyManager {

    // In-memory cache keyed by documentId, preserving chronological insertion order
    private val memoryStore = mutableMapOf<String, MutableList<VocabularyEntry>>()

    private fun getVocabDir(context: Context): File {
        return File(context.filesDir, "vocabulary").apply { mkdirs() }
    }

    fun sanitizeFilename(title: String): String {
        val clean = title.replace(Regex("[^a-zA-Z0-9_ -]"), "").trim().replace(" ", "_")
        return if (clean.isBlank()) "Book" else clean
    }

    fun getVocabularyFile(context: Context, documentId: String, bookTitle: String): File {
        val sanitized = sanitizeFilename(bookTitle)
        return File(getVocabDir(context), "${sanitized}_Vocabulary.txt")
    }

    @Synchronized
    fun getEntries(context: Context, documentId: String, bookTitle: String): List<VocabularyEntry> {
        val cached = memoryStore[documentId]
        if (cached != null) return cached.toList()

        // Load from disk if available
        val file = getVocabularyFile(context, documentId, bookTitle)
        val loaded = if (file.exists()) {
            parseFromTxtFile(file, documentId)
        } else {
            mutableListOf()
        }
        memoryStore[documentId] = loaded
        return loaded.toList()
    }

    @Synchronized
    fun addWord(
        context: Context,
        documentId: String,
        bookTitle: String,
        word: String,
        pageNumber: Int,
        contextSentence: String = ""
    ): Pair<VocabularyEntry, Boolean> { // returns (entry, isDuplicate)
        val list = memoryStore.getOrPut(documentId) { mutableListOf() }
        val cleanWord = word.trim()
        val normalized = cleanWord.lowercase()

        val existing = list.find { it.normalizedWord == normalized }
        val isDuplicate = existing != null

        val entry = VocabularyEntry(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            word = cleanWord,
            normalizedWord = normalized,
            pageNumber = pageNumber,
            contextSentence = contextSentence,
            timestamp = System.currentTimeMillis()
        )

        list.add(entry)
        saveToTxtFile(context, documentId, bookTitle, list)
        return Pair(entry, isDuplicate)
    }


    @Synchronized
    fun editWord(
        context: Context,
        documentId: String,
        bookTitle: String,
        entryId: String,
        newWord: String
    ): VocabularyEntry? {
        val list = memoryStore[documentId] ?: return null
        val idx = list.indexOfFirst { it.id == entryId }
        if (idx == -1) return null

        val old = list[idx]
        val updated = old.copy(
            word = newWord.trim(),
            normalizedWord = newWord.trim().lowercase()
        )
        list[idx] = updated
        saveToTxtFile(context, documentId, bookTitle, list)
        return updated
    }

    // Retained orphaned notes when word entries are deleted but user chose to keep notes
    private val orphanedNotes = mutableMapOf<String, MutableList<VocabularyEntry>>()

    @Synchronized
    fun deleteEntries(
        context: Context,
        documentId: String,
        bookTitle: String,
        entryIds: Set<String>,
        retainNotes: Boolean = false
    ): List<VocabularyEntry> {
        val list = memoryStore[documentId] ?: return emptyList()
        val toRemove = list.filter { entryIds.contains(it.id) }
        if (retainNotes) {
            val orphans = orphanedNotes.getOrPut(documentId) { mutableListOf() }
            orphans.addAll(toRemove.filter { !it.meaning.isNullOrBlank() || it.synonyms.isNotEmpty() })
        }
        list.removeAll { entryIds.contains(it.id) }
        saveToTxtFile(context, documentId, bookTitle, list)
        return list.toList()
    }

    @Synchronized
    fun enrichEntry(
        context: Context,
        documentId: String,
        bookTitle: String,
        entryId: String,
        result: DictionaryResult
    ): VocabularyEntry? {
        val list = memoryStore[documentId] ?: return null
        val idx = list.indexOfFirst { it.id == entryId }
        if (idx == -1) return null

        val old = list[idx]
        val updated = old.copy(
            meaning = result.meaning ?: old.meaning,
            synonyms = if (result.synonyms.isNotEmpty()) result.synonyms else old.synonyms,
            antonyms = if (result.antonyms.isNotEmpty()) result.antonyms else old.antonyms,
            exampleSentence = result.exampleSentence ?: old.exampleSentence,
            hindiMeaning = result.hindiMeaning ?: old.hindiMeaning,
            lookupSource = result.source,
            requestedInfoType = result.infoType.label
        )
        list[idx] = updated
        saveToTxtFile(context, documentId, bookTitle, list)
        return updated
    }

    fun saveToTxtFile(
        context: Context,
        documentId: String,
        bookTitle: String,
        entries: List<VocabularyEntry>
    ): File {
        val file = getVocabularyFile(context, documentId, bookTitle)
        val sb = StringBuilder()
        sb.append("Vocabulary — ").append(bookTitle).append("\n")
        sb.append("Source document: ").append(bookTitle).append("\n\n")

        // 1. Numbered Vocabulary Word List (Renumbered 1..N sequentially)
        for ((idx, entry) in entries.withIndex()) {
            sb.append("${idx + 1}. ").append(entry.word)
            if (entry.pageNumber > 0) {
                sb.append(" (Page ${entry.pageNumber})")
            }
            sb.append("\n")
        }

        // 2. Dictionary Notes & Lexical Enrichments
        sb.append("\n---\n")
        sb.append("Word Notes / Dictionary Results\n\n")

        val enrichedEntries = entries.filter {
            !it.meaning.isNullOrBlank() || it.synonyms.isNotEmpty() || it.antonyms.isNotEmpty() || !it.exampleSentence.isNullOrBlank() || !it.hindiMeaning.isNullOrBlank()
        }
        val orphans = orphanedNotes[documentId] ?: emptyList()

        if (enrichedEntries.isEmpty() && orphans.isEmpty()) {
            sb.append("[No dictionary lookups yet. Use 'Dictionary Lookup' in Edit Vocabulary.]\n")
        } else {
            for (entry in enrichedEntries) {
                sb.append("• ").append(entry.word).append("\n")
                if (!entry.requestedInfoType.isNullOrBlank()) {
                    sb.append("  Information Requested: ").append(entry.requestedInfoType).append("\n")
                }
                if (!entry.meaning.isNullOrBlank()) {
                    sb.append("  Meaning: ").append(entry.meaning).append("\n")
                }
                if (!entry.hindiMeaning.isNullOrBlank()) {
                    sb.append("  Hindi (हिन्दी): ").append(entry.hindiMeaning).append("\n")
                }
                if (entry.synonyms.isNotEmpty()) {
                    sb.append("  Synonyms: ").append(entry.synonyms.joinToString(", ")).append("\n")
                }
                if (entry.antonyms.isNotEmpty()) {
                    sb.append("  Antonyms: ").append(entry.antonyms.joinToString(", ")).append("\n")
                }
                if (!entry.exampleSentence.isNullOrBlank()) {
                    sb.append("  Example: ").append(entry.exampleSentence).append("\n")
                }
                if (!entry.lookupSource.isNullOrBlank()) {
                    sb.append("  Source: ").append(entry.lookupSource).append("\n")
                }
                sb.append("\n")
            }

            if (orphans.isNotEmpty()) {
                sb.append("[Retained Notes from Removed Words]\n")
                for (entry in orphans) {
                    sb.append("• ").append(entry.word).append(" (Removed word note)\n")
                    if (!entry.meaning.isNullOrBlank()) {
                        sb.append("  Meaning: ").append(entry.meaning).append("\n")
                    }
                    if (!entry.hindiMeaning.isNullOrBlank()) {
                        sb.append("  Hindi: ").append(entry.hindiMeaning).append("\n")
                    }
                    sb.append("\n")
                }
            }
        }

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun parseFromTxtFile(file: File, documentId: String): MutableList<VocabularyEntry> {
        val list = mutableListOf<VocabularyEntry>()
        val lines = file.readLines(Charsets.UTF_8)
        var readingList = true

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "---") {
                readingList = false
                continue
            }
            if (readingList && trimmed.matches(Regex("^\\d+\\.\\s+.*"))) {
                val rawWord = trimmed.replace(Regex("^\\d+\\.\\s+"), "")
                val word = rawWord.substringBefore(" (Page ").trim()
                val page = if (rawWord.contains(" (Page ")) {
                    rawWord.substringAfter(" (Page ").substringBefore(")").toIntOrNull() ?: 1
                } else 1
                list.add(
                    VocabularyEntry(
                        id = UUID.randomUUID().toString(),
                        documentId = documentId,
                        word = word,
                        normalizedWord = word.lowercase(),
                        pageNumber = page,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        return list
    }

    fun createShareIntent(context: Context, documentId: String, bookTitle: String): Intent {
        val file = getVocabularyFile(context, documentId, bookTitle)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Vocabulary — $bookTitle")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
