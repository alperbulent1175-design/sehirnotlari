package com.sehirnotlari

/** A Gemini response is only offered as a note when it contains visible content. */
data class AiSummary(val title: String, val content: String) {
    companion object {
        fun parse(raw: String?, fallbackTitle: String): AiSummary {
            val text = raw?.trim().orEmpty()
            require(text.isNotEmpty()) { "Empty AI response" }
            val title = if (text.contains("BAŞLIK:")) text.substringAfter("BAŞLIK:").substringBefore("İÇERİK:").trim() else ""
            val content = if (text.contains("İÇERİK:")) text.substringAfter("İÇERİK:").trim() else text
            require(content.isNotEmpty()) { "Empty AI content" }
            return AiSummary(title.ifEmpty { fallbackTitle }.take(120), content.take(12_000))
        }
    }
}
