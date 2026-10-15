package com.example

import com.example.data.model.DocumentFormat
import com.example.data.model.ReaderFont
import com.example.data.model.ReaderPreferences
import com.example.data.model.ReaderTheme
import com.example.data.model.ReadingLanguage
import com.example.data.model.VoicePreset
import com.example.service.DocumentParserService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `sample documents include Hindi literature and scripts`() {
        val samples = DocumentParserService.getSampleDocuments()
        assertTrue(samples.size >= 4)
        assertEquals("पंच परमेश्वर", samples[0].title)
        assertTrue(samples[0].rawContent.contains("जुम्मन शेख"))
        assertEquals("The Neon Detective", samples[1].title)
    }

    @Test
    fun `spread pair computation follows exact odd-even pattern`() {
        // Page 1 is on left, page 2 on right for Spread 0
        val page1 = 1
        val left1 = if (page1 % 2 != 0) page1 else page1 - 1
        assertEquals(1, left1)
        assertEquals(2, left1 + 1)

        // Page 4 is on right of Spread 1 (pages 3 and 4)
        val page4 = 4
        val left4 = if (page4 % 2 != 0) page4 else page4 - 1
        assertEquals(3, left4)
        assertEquals(4, left4 + 1)
    }

    @Test
    fun `default preferences support multilingual Indian languages and natural voice presets`() {
        val prefs = ReaderPreferences()
        assertEquals(ReaderFont.LITERATA, prefs.font)
        assertEquals(ReaderTheme.PAPER, prefs.theme)
        assertEquals(19f, prefs.fontSizeSp)
        assertEquals(1.0f, prefs.speechRate)
        assertEquals(ReadingLanguage.HINDI, prefs.language)
        assertEquals(VoicePreset.FEMALE_NARRATOR, prefs.voicePreset)
    }

    @Test
    fun `multilingual languages contain Indian languages`() {
        val hindi = ReadingLanguage.HINDI
        assertEquals("hi-IN", hindi.code)
        assertEquals("en-IN", ReadingLanguage.ENGLISH_INDIA.code)
        assertEquals("ta-IN", ReadingLanguage.TAMIL.code)
    }
}
