package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.model.ReadingLanguage
import com.example.data.model.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSentence = MutableStateFlow("")
    val currentSentence: StateFlow<String> = _currentSentence.asStateFlow()

    private val _equalizerBars = MutableStateFlow(listOf(0.2f, 0.4f, 0.6f, 0.3f, 0.5f))
    val equalizerBars: StateFlow<List<Float>> = _equalizerBars.asStateFlow()

    // Word-level progress & karaoke seeking state
    private val _activeWordIndex = MutableStateFlow<Int?>(null)
    val activeWordIndex: StateFlow<Int?> = _activeWordIndex.asStateFlow()

    private val _activePageNumber = MutableStateFlow<Int?>(null)
    val activePageNumber: StateFlow<Int?> = _activePageNumber.asStateFlow()

    // Generation token to discard stale callbacks
    private val generationCounter = AtomicLong(0)
    private var currentUtteranceId: String = ""

    // Active speed rate
    private var currentRate: Float = 1.0f
    private var activePageWords: List<String> = emptyList()
    private var activeFullText: String = ""
    private var activeStartWordOffset: Int = 0

    // Callback when speech completes for the active page
    var onPageFinished: (() -> Unit)? = null

    // STT State
    private var speechRecognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastVoiceCommand = MutableStateFlow<String?>(null)
    val lastVoiceCommand: StateFlow<String?> = _lastVoiceCommand.asStateFlow()

    var onVoiceCommandReceived: ((command: String) -> Unit)? = null

    private var visualizerJob: Job? = null
    private var wordProgressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.e("TtsManager", "Error initializing TTS: ${e.message}")
        }
        setupSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            applyLanguageAndVoice(ReadingLanguage.HINDI, VoicePreset.FEMALE_NARRATOR)
            setupProgressListener()
        } else {
            Log.e("TtsManager", "TTS initialization failed with code $status")
        }
    }

    fun applyLanguageAndVoice(language: ReadingLanguage, voicePreset: VoicePreset) {
        if (!isInitialized) return

        try {
            val locale = Locale.forLanguageTag(language.code)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }

            when (voicePreset) {
                VoicePreset.FEMALE_NARRATOR -> tts?.setPitch(1.15f)
                VoicePreset.MALE_NARRATOR -> tts?.setPitch(0.85f)
                VoicePreset.STORYTELLER -> tts?.setPitch(0.78f)
                VoicePreset.YOUTHFUL -> tts?.setPitch(1.22f)
            }
        } catch (e: Exception) {
            Log.w("TtsManager", "Could not set voice properties: ${e.message}")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != currentUtteranceId) return
                _isPlaying.value = true
                startVisualizer()
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != currentUtteranceId) return
                _isPlaying.value = false
                stopVisualizer()
                wordProgressJob?.cancel()

                scope.launch {
                    delay(100)
                    // Trigger automatic page advancement when page narration ends
                    onPageFinished?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId != currentUtteranceId) return
                _isPlaying.value = false
                stopVisualizer()
                wordProgressJob?.cancel()
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != currentUtteranceId) return
                // Map character offset in spoken substring to page word index
                mapCharacterOffsetToWord(start)
            }
        })
    }

    private fun mapCharacterOffsetToWord(charStartInSpokenText: Int) {
        if (activePageWords.isEmpty()) return
        val spokenWords = activePageWords.drop(activeStartWordOffset)
        var cumulativeLength = 0
        for ((idx, word) in spokenWords.withIndex()) {
            val nextLength = cumulativeLength + word.length + 1
            if (charStartInSpokenText in cumulativeLength..nextLength) {
                val absoluteWordIdx = activeStartWordOffset + idx
                _activeWordIndex.value = absoluteWordIdx
                _currentSentence.value = word
                return
            }
            cumulativeLength = nextLength
        }
    }

    /**
     * Live Speech Rate Change:
     * Immediately applies rate, stops old utterance, and resumes from the current word.
     */
    fun setLiveSpeechRate(newRate: Float, fullPageText: String? = null) {
        currentRate = newRate.coerceIn(0.5f, 2.0f)
        try {
            tts?.setSpeechRate(currentRate)
        } catch (e: Exception) {
            Log.w("TtsManager", "Error setting speech rate: ${e.message}")
        }

        if (_isPlaying.value) {
            val pageNum = _activePageNumber.value ?: 1
            val curWordIdx = _activeWordIndex.value ?: 0
            val textToUse = fullPageText ?: activeFullText
            if (textToUse.isNotBlank()) {
                seekToWord(pageNum, curWordIdx, textToUse, currentRate)
            }
        }
    }

    /**
     * Starts or seeks reading to a specific word on a specific page.
     * Cancels existing utterance and ensures accurate karaoke synchronization.
     */
    fun seekToWord(
        pageNumber: Int,
        wordIndex: Int,
        fullPageText: String,
        rate: Float = currentRate
    ) {
        if (fullPageText.isBlank()) return

        stopInternal()

        currentRate = rate.coerceIn(0.5f, 2.0f)
        activeFullText = fullPageText
        activePageWords = fullPageText.split(Regex("\\s+")).filter { it.isNotBlank() }

        val safeWordIndex = wordIndex.coerceIn(0, (activePageWords.size - 1).coerceAtLeast(0))
        activeStartWordOffset = safeWordIndex
        _activePageNumber.value = pageNumber
        _activeWordIndex.value = safeWordIndex

        val textToSpeak = activePageWords.subList(safeWordIndex, activePageWords.size).joinToString(" ")
        _currentSentence.value = activePageWords.getOrNull(safeWordIndex) ?: textToSpeak.take(40)

        val gen = generationCounter.incrementAndGet()
        val utteranceId = "STORYFLIP_${gen}_P${pageNumber}_W${safeWordIndex}"
        currentUtteranceId = utteranceId

        _isPlaying.value = true
        startVisualizer()
        startWordProgressSimulation(safeWordIndex, activePageWords, currentRate, gen)

        if (!isInitialized) {
            return
        }

        try {
            tts?.setSpeechRate(currentRate)
            val params = Bundle()
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e("TtsManager", "Failed to speak: ${e.message}")
        }
    }

    /**
     * Default reading: starts from first word of page.
     */
    fun speak(text: String, rate: Float = currentRate, pageNumber: Int = 1) {
        seekToWord(pageNumber, 0, text, rate)
    }

    /**
     * Provides synchronized word advancing timer in case onRangeStart is delayed or unsupported
     * on the specific Android TTS engine.
     */
    private fun startWordProgressSimulation(
        startWordIdx: Int,
        words: List<String>,
        rate: Float,
        generationId: Long
    ) {
        wordProgressJob?.cancel()
        wordProgressJob = scope.launch {
            var idx = startWordIdx
            while (isActive && idx < words.size && generationCounter.get() == generationId && _isPlaying.value) {
                _activeWordIndex.value = idx
                val word = words[idx]
                _currentSentence.value = word

                // Base reading speed: roughly 250 words per minute at 1.0x (~240ms per word)
                val baseMsPerWord = 240L
                val wordLengthFactor = (word.length.toFloat() / 5f).coerceIn(0.7f, 1.8f)
                val delayMs = ((baseMsPerWord * wordLengthFactor) / rate).toLong().coerceIn(80L, 800L)

                delay(delayMs)
                idx++
            }
        }
    }

    fun previewVoice(language: ReadingLanguage, voicePreset: VoicePreset) {
        applyLanguageAndVoice(language, voicePreset)
        val previewText = when (language) {
            ReadingLanguage.HINDI -> "नमस्ते, यह स्टोरीफ्लिप वाचक का पूर्वावलोकन है।"
            ReadingLanguage.BENGALI -> "নমস্কার, এটি আপনার কণ্ঠস্বর পূর্বরূপ।"
            ReadingLanguage.TAMIL -> "வணக்கம், இது உங்கள் குரல் மாதிரிக்காட்சி."
            else -> "Welcome to StoryFlip, listening in ${language.displayName}."
        }
        seekToWord(1, 0, previewText, currentRate)
    }

    fun pauseOrResume(textToResume: String, rate: Float = currentRate, pageNumber: Int = 1) {
        if (_isPlaying.value) {
            stop()
        } else {
            val startWord = _activeWordIndex.value ?: 0
            seekToWord(pageNumber, startWord, textToResume, rate)
        }
    }

    private fun stopInternal() {
        generationCounter.incrementAndGet()
        wordProgressJob?.cancel()
        visualizerJob?.cancel()
        try {
            tts?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop() {
        stopInternal()
        _isPlaying.value = false
        _activeWordIndex.value = null
        stopVisualizer()
    }

    private fun startVisualizer() {
        visualizerJob?.cancel()
        visualizerJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val newBars = listOf(
                    (0.2f + Math.random().toFloat() * 0.7f).coerceIn(0.1f, 1.0f),
                    (0.3f + Math.random().toFloat() * 0.7f).coerceIn(0.1f, 1.0f),
                    (0.5f + Math.random().toFloat() * 0.5f).coerceIn(0.1f, 1.0f),
                    (0.3f + Math.random().toFloat() * 0.6f).coerceIn(0.1f, 1.0f),
                    (0.2f + Math.random().toFloat() * 0.7f).coerceIn(0.1f, 1.0f)
                )
                _equalizerBars.value = newBars
                delay(120)
            }
        }
    }

    private fun stopVisualizer() {
        visualizerJob?.cancel()
        _equalizerBars.value = listOf(0.15f, 0.2f, 0.15f, 0.25f, 0.15f)
    }

    // ==========================================
    // SPEECH RECOGNITION (STT) VOICE COMMANDS
    // ==========================================

    private fun setupSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { _isListening.value = false }
                        override fun onError(error: Int) { _isListening.value = false }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognized = matches?.firstOrNull()?.lowercase() ?: return
                            _lastVoiceCommand.value = recognized
                            handleRecognizedCommand(recognized)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
        } catch (e: Exception) {
            Log.w("TtsManager", "Speech recognition setup: ${e.message}")
        }
    }

    fun startListeningForVoiceCommands() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            _isListening.value = false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        _isListening.value = false
    }

    private fun handleRecognizedCommand(text: String) {
        when {
            text.contains("next") || text.contains("अगला") || text.contains("आगे") -> {
                onVoiceCommandReceived?.invoke("NEXT")
            }
            text.contains("previous") || text.contains("back") || text.contains("पिछला") || text.contains("पीछे") -> {
                onVoiceCommandReceived?.invoke("PREV")
            }
            text.contains("pause") || text.contains("stop") || text.contains("रुको") -> {
                stop()
                onVoiceCommandReceived?.invoke("PAUSE")
            }
            text.contains("play") || text.contains("resume") || text.contains("read") || text.contains("पढ़ो") -> {
                if (activeFullText.isNotBlank()) {
                    speak(activeFullText, currentRate, _activePageNumber.value ?: 1)
                }
                onVoiceCommandReceived?.invoke("PLAY")
            }
        }
    }

    fun shutdown() {
        stop()
        stopListening()
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
    }
}
