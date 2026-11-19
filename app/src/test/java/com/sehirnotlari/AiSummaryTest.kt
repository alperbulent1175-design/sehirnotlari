package com.sehirnotlari

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiSummaryTest {
    @Test fun parsesLabeledAnswer() {
        val result = AiSummary.parse("BAŞLIK: Bursa\nİÇERİK: Ulu Cami'yi ziyaret edin.", "AI Özeti")
        assertEquals("Bursa", result.title)
        assertEquals("Ulu Cami'yi ziyaret edin.", result.content)
    }

    @Test fun preservesUnlabeledAnswerAsContent() {
        assertEquals("Kısa özet", AiSummary.parse("Kısa özet", "AI Özeti").content)
    }

    @Test fun refusesEmptyAnswer() {
        assertThrows(IllegalArgumentException::class.java) { AiSummary.parse("  ", "AI Özeti") }
        assertThrows(IllegalArgumentException::class.java) { AiSummary.parse("BAŞLIK: Bursa\nİÇERİK: ", "AI Özeti") }
    }
}
