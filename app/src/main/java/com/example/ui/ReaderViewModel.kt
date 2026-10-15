package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.DocumentFormat
import com.example.data.model.ReaderLayoutMode
import com.example.data.model.ReaderPage
import com.example.data.model.ReaderPreferences
import com.example.data.model.ReadingLanguage
import com.example.data.model.StoryboardScene
import com.example.data.model.StoryboardShot
import com.example.data.model.VoicePreset
import com.example.service.DocumentParserService
import com.example.service.GeminiStoryboardService
import com.example.service.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    LIBRARY,
    READER,
    STORYBOARD_STUDIO
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val docDao = db.documentDao()
    val ttsManager = TtsManager(application)

    // All documents in library
    val documents: StateFlow<List<DocumentEntity>> = docDao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active App Screen & Layout Mode
    private val _currentScreen = MutableStateFlow(AppScreen.LIBRARY)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _layoutMode = MutableStateFlow(ReaderLayoutMode.COVER_PORTRAIT)
    val layoutMode: StateFlow<ReaderLayoutMode> = _layoutMode.asStateFlow()

    // Automatic File Parsing & Stage State
    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _parsingStage = MutableStateFlow("")
    val parsingStage: StateFlow<String> = _parsingStage.asStateFlow()

    private val _parsingFilename = MutableStateFlow("")
    val parsingFilename: StateFlow<String> = _parsingFilename.asStateFlow()

    private var parsingJob: Job? = null

    // Active Document & Rendered Pages
    private val _activeDocument = MutableStateFlow<DocumentEntity?>(null)
    val activeDocument: StateFlow<DocumentEntity?> = _activeDocument.asStateFlow()

    private val _pages = MutableStateFlow<List<ReaderPage>>(emptyList())
    val pages: StateFlow<List<ReaderPage>> = _pages.asStateFlow()

    // Active Page (0-based index internally, 1-based for display)
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    // Selected Page Candidate (when tapped in landscape spread to preserve for portrait focus mode)
    private val _pendingFocusPageNumber = MutableStateFlow<Int?>(null)
    val pendingFocusPageNumber: StateFlow<Int?> = _pendingFocusPageNumber.asStateFlow()

    // Orientation Recommendation 3-second toast state
    private val _recommendationVisible = MutableStateFlow(false)
    val recommendationVisible: StateFlow<Boolean> = _recommendationVisible.asStateFlow()

    private val _recommendationTargetLandscape = MutableStateFlow(true)
    val recommendationTargetLandscape: StateFlow<Boolean> = _recommendationTargetLandscape.asStateFlow()

    // Common Long-press Control Panel
    private val _controlPanelVisible = MutableStateFlow(false)
    val controlPanelVisible: StateFlow<Boolean> = _controlPanelVisible.asStateFlow()

    // Reader Preferences
    private val _preferences = MutableStateFlow(ReaderPreferences())
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()

    // Word-level karaoke highlighting states from TtsManager
    val activeWordIndex: StateFlow<Int?> = ttsManager.activeWordIndex
    val activePageNumber: StateFlow<Int?> = ttsManager.activePageNumber

    // Storyboard State
    private val _storyboardScenes = MutableStateFlow<List<StoryboardScene>>(emptyList())
    val storyboardScenes: StateFlow<List<StoryboardScene>> = _storyboardScenes.asStateFlow()

    private val _isGeneratingStoryboard = MutableStateFlow(false)
    val isGeneratingStoryboard: StateFlow<Boolean> = _isGeneratingStoryboard.asStateFlow()

    init {
        // Pre-populate with sample documents if library is empty
        viewModelScope.launch {
            val samples = DocumentParserService.getSampleDocuments(getApplication())
            for (sample in samples) {
                docDao.insertDocument(sample)
            }
        }

        // Automatic page advancement when page narration ends
        ttsManager.onPageFinished = {
            viewModelScope.launch {
                advancePageFromTts()
            }
        }

        // Setup STT voice commands callback
        ttsManager.onVoiceCommandReceived = { command ->
            when (command) {
                "NEXT" -> onPageTurn(forward = true)
                "PREV" -> onPageTurn(forward = false)
                "PAUSE" -> ttsManager.stop()
                "PLAY" -> {
                    val activePage = _pages.value.getOrNull(_currentPageIndex.value)
                    if (activePage != null) {
                        ttsManager.seekToWord(
                            pageNumber = activePage.pageNumber,
                            wordIndex = 0,
                            fullPageText = activePage.text,
                            rate = _preferences.value.speechRate
                        )
                    }
                }
            }
        }
    }

    /**
     * Automatic File Ingestion without forced manual entry:
     * Reads URI stream, validates, extracts metadata, paginates, and opens directly to cover.
     */
    fun importFileFromUri(uri: Uri) {
        parsingJob?.cancel()
        _isParsing.value = true
        _parsingFilename.value = "Selected Document"
        _parsingStage.value = "Validating file..."

        parsingJob = viewModelScope.launch {
            val result = DocumentParserService.parseFromUri(
                context = getApplication(),
                uri = uri,
                onStageUpdate = { stage ->
                    _parsingStage.value = stage
                }
            )

            result.onSuccess { doc ->
                docDao.insertDocument(doc)
                _isParsing.value = false
                openDocument(doc)
            }.onFailure {
                _parsingStage.value = "Error parsing file: ${it.message ?: "Unknown error"}"
                _isParsing.value = false
            }
        }
    }

    fun cancelParsing() {
        parsingJob?.cancel()
        _isParsing.value = false
    }

    fun openDocument(document: DocumentEntity) {
        _activeDocument.value = document
        val parsedPages = DocumentParserService.paginate(document.rawContent)
        _pages.value = parsedPages

        // Restore to saved page or start at page 1 (0-indexed = 0)
        val initialPageIdx = ((document.lastReadPage - 1)).coerceIn(0, (parsedPages.size - 1).coerceAtLeast(0))
        _currentPageIndex.value = initialPageIdx

        // Initially present as closed hardcover in portrait mode
        _layoutMode.value = ReaderLayoutMode.COVER_PORTRAIT
        _currentScreen.value = AppScreen.READER
        _recommendationVisible.value = false
        _controlPanelVisible.value = false

        // Automatically prepare initial storyboard breakdown in the background
        viewModelScope.launch {
            val scenes = GeminiStoryboardService.generateLocalScriptBreakdown(document.rawContent)
            _storyboardScenes.value = scenes
        }
    }

    fun openBook() {
        _layoutMode.value = ReaderLayoutMode.PORTRAIT_SINGLE_PAGE
        showOrientationRecommendation(targetLandscape = true)
        updateReadingProgress()
    }

    fun closeBook() {
        ttsManager.stop()
        val doc = _activeDocument.value
        val curPageNum = _currentPageIndex.value + 1

        if (doc != null) {
            viewModelScope.launch {
                docDao.updateReadingProgress(doc.id, curPageNum)
                docDao.updateBookmark(doc.id, curPageNum)
                _activeDocument.value = doc.copy(
                    lastReadPage = curPageNum,
                    bookmarkPage = curPageNum,
                    hasProgress = true
                )
            }
        }

        _layoutMode.value = ReaderLayoutMode.COVER_PORTRAIT
        _controlPanelVisible.value = false
        _recommendationVisible.value = false
    }

    fun exitToLibrary() {
        closeBook()
        _currentScreen.value = AppScreen.LIBRARY
    }

    fun showOrientationRecommendation(targetLandscape: Boolean) {
        _recommendationTargetLandscape.value = targetLandscape
        _recommendationVisible.value = true
    }

    fun dismissRecommendation() {
        _recommendationVisible.value = false
    }

    fun switchLayout(toLandscape: Boolean) {
        _recommendationVisible.value = false

        if (toLandscape) {
            val curPage1Based = _currentPageIndex.value + 1
            val leftPage1Based = if (curPage1Based % 2 != 0) curPage1Based else (curPage1Based - 1).coerceAtLeast(1)
            _currentPageIndex.value = (leftPage1Based - 1).coerceAtLeast(0)
            _layoutMode.value = ReaderLayoutMode.LANDSCAPE_DOUBLE_PAGE
        } else {
            val targetPage = _pendingFocusPageNumber.value ?: (_currentPageIndex.value + 1)
            _currentPageIndex.value = (targetPage - 1).coerceAtLeast(0)
            _pendingFocusPageNumber.value = null
            _layoutMode.value = ReaderLayoutMode.PORTRAIT_SINGLE_PAGE
        }

        updateReadingProgress()
    }

    fun selectPageForFocus(pageNumber: Int) {
        _pendingFocusPageNumber.value = pageNumber
        showOrientationRecommendation(targetLandscape = false)
    }

    /**
     * Seeks and starts reading from an exact double-tapped word on a page.
     */
    fun seekToWord(pageNumber: Int, wordIndex: Int) {
        val safePageIdx = (pageNumber - 1).coerceIn(0, (_pages.value.size - 1).coerceAtLeast(0))
        _currentPageIndex.value = safePageIdx
        val page = _pages.value.getOrNull(safePageIdx) ?: return
        ttsManager.seekToWord(
            pageNumber = pageNumber,
            wordIndex = wordIndex,
            fullPageText = page.text,
            rate = _preferences.value.speechRate
        )
        updateReadingProgress()
    }

    /**
     * Automatic page advancement when speech on a page completes:
     * Continues seamlessly through sequential pages and spreads.
     */
    fun advancePageFromTts() {
        val isLandscape = _layoutMode.value == ReaderLayoutMode.LANDSCAPE_DOUBLE_PAGE
        val curPage1Based = _currentPageIndex.value + 1
        val total = _pages.value.size

        if (!isLandscape) {
            if (_currentPageIndex.value < total - 1) {
                val nextIdx = _currentPageIndex.value + 1
                _currentPageIndex.value = nextIdx
                updateReadingProgress()
                val nextPage = _pages.value.getOrNull(nextIdx)
                if (nextPage != null) {
                    ttsManager.seekToWord(
                        pageNumber = nextPage.pageNumber,
                        wordIndex = 0,
                        fullPageText = nextPage.text,
                        rate = _preferences.value.speechRate
                    )
                }
            } else {
                ttsManager.stop()
            }
        } else {
            val leftPageNum = if (curPage1Based % 2 != 0) curPage1Based else (curPage1Based - 1).coerceAtLeast(1)
            val rightPageNum = leftPageNum + 1
            val activeReadingPage = ttsManager.activePageNumber.value ?: leftPageNum

            if (activeReadingPage == leftPageNum && rightPageNum <= total) {
                val rightPage = _pages.value.find { it.pageNumber == rightPageNum }
                if (rightPage != null) {
                    ttsManager.seekToWord(
                        pageNumber = rightPage.pageNumber,
                        wordIndex = 0,
                        fullPageText = rightPage.text,
                        rate = _preferences.value.speechRate
                    )
                    return
                }
            }

            val nextSpreadLeft = leftPageNum + 2
            if (nextSpreadLeft <= total) {
                val nextLeftIdx = nextSpreadLeft - 1
                _currentPageIndex.value = nextLeftIdx
                updateReadingProgress()
                val nextLeftPage = _pages.value.find { it.pageNumber == nextSpreadLeft }
                if (nextLeftPage != null) {
                    ttsManager.seekToWord(
                        pageNumber = nextLeftPage.pageNumber,
                        wordIndex = 0,
                        fullPageText = nextLeftPage.text,
                        rate = _preferences.value.speechRate
                    )
                }
            } else {
                ttsManager.stop()
            }
        }
    }

    /**
     * Invoked when a page turn is completed by touch-curl gesture or button tap.
     */
    fun onPageTurn(forward: Boolean) {
        val total = _pages.value.size
        val isLandscape = _layoutMode.value == ReaderLayoutMode.LANDSCAPE_DOUBLE_PAGE
        val step = if (isLandscape) 2 else 1

        val newIndex = if (forward) {
            (_currentPageIndex.value + step).coerceAtMost((total - 1).coerceAtLeast(0))
        } else {
            (_currentPageIndex.value - step).coerceAtLeast(0)
        }

        if (newIndex != _currentPageIndex.value) {
            _currentPageIndex.value = newIndex
            _pendingFocusPageNumber.value = null
            updateReadingProgress()

            // Synchronize TTS if playing
            if (ttsManager.isPlaying.value) {
                val activePage = _pages.value.getOrNull(newIndex)
                if (activePage != null) {
                    ttsManager.seekToWord(
                        pageNumber = activePage.pageNumber,
                        wordIndex = 0,
                        fullPageText = activePage.text,
                        rate = _preferences.value.speechRate
                    )
                }
            }
        }
    }

    private fun updateReadingProgress() {
        val doc = _activeDocument.value ?: return
        val curPage1Based = _currentPageIndex.value + 1
        viewModelScope.launch {
            docDao.updateReadingProgress(doc.id, curPage1Based)
            _activeDocument.value = doc.copy(lastReadPage = curPage1Based, hasProgress = true)
        }
    }

    fun openControlPanel() {
        _controlPanelVisible.value = true
    }

    fun dismissControlPanel() {
        _controlPanelVisible.value = false
    }

    fun updatePreferences(newPrefs: ReaderPreferences) {
        _preferences.value = newPrefs
        ttsManager.applyLanguageAndVoice(newPrefs.language, newPrefs.voicePreset)
    }

    /**
     * Live speed change that seamlessly updates active speech without restarting from beginning.
     */
    fun onLiveSpeedChange(newSpeed: Float) {
        _preferences.value = _preferences.value.copy(speechRate = newSpeed)
        val activeText = _pages.value.getOrNull(_currentPageIndex.value)?.text ?: ""
        ttsManager.setLiveSpeechRate(newSpeed, activeText)
    }

    fun previewVoice(language: ReadingLanguage, voicePreset: VoicePreset) {
        ttsManager.previewVoice(language, voicePreset)
    }

    fun startListeningVoiceCommand() {
        ttsManager.startListeningForVoiceCommands()
    }

    fun toggleSpeech() {
        val activePage = _pages.value.getOrNull(_currentPageIndex.value) ?: return
        ttsManager.pauseOrResume(
            textToResume = activePage.text,
            rate = _preferences.value.speechRate,
            pageNumber = activePage.pageNumber
        )
    }

    fun navigateToStoryboardStudio() {
        _currentScreen.value = AppScreen.STORYBOARD_STUDIO
    }

    fun navigateBackToReader() {
        _currentScreen.value = AppScreen.READER
    }

    fun regenerateStoryboard() {
        val doc = _activeDocument.value ?: return
        _isGeneratingStoryboard.value = true
        viewModelScope.launch {
            val newScenes = GeminiStoryboardService.generateStoryboardFromScript(doc.rawContent)
            _storyboardScenes.value = newScenes
            _isGeneratingStoryboard.value = false
        }
    }

    fun addCustomShot(sceneNumber: Int, shot: StoryboardShot) {
        val current = _storyboardScenes.value.toMutableList()
        val sceneIdx = current.indexOfFirst { it.sceneNumber == sceneNumber }
        if (sceneIdx != -1) {
            val sc = current[sceneIdx]
            val updatedShots = sc.shots + shot
            current[sceneIdx] = sc.copy(shots = updatedShots)
            _storyboardScenes.value = current
        }
    }

    fun loadSample(index: Int) {
        viewModelScope.launch {
            val samples = DocumentParserService.getSampleDocuments(getApplication())
            val sample = samples.getOrNull(index) ?: samples.first()
            docDao.insertDocument(sample)
            openDocument(sample)
        }
    }

    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            docDao.deleteDocument(docId)
            if (_activeDocument.value?.id == docId) {
                _activeDocument.value = null
                _currentScreen.value = AppScreen.LIBRARY
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }
}
