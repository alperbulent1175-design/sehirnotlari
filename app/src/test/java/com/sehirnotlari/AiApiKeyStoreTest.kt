package com.sehirnotlari

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiKeyStoreTest {
    @Test fun acceptsPlausibleKeysWithoutWhitespaceOrControlCharacters() {
        assertTrue(AiApiKeyStore.valid("AIzaSyExampleKey123456789012345678"))
        assertFalse(AiApiKeyStore.valid("short"))
        assertFalse(AiApiKeyStore.valid("AIzaSyExampleKey123456789012345678\n"))
        assertFalse(AiApiKeyStore.valid("a".repeat(257)))
    }
}
