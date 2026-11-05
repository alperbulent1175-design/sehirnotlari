package com.sehirnotlari.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteEntityContractTest {
    @Test fun cityScopedIdsRemainDistinct() {
        val a = NoteEntity(id = "Bursa::123", city = "Bursa", title = "A")
        val b = NoteEntity(id = "Canakkale::123", city = "Canakkale", title = "B")
        assertNotEquals(a.id, b.id)
    }

    @Test fun fieldsRemainAvailable() {
        val note = NoteEntity(id = "Bursa::123", city = "Bursa", title = "Title", content = "Body", star = 4, favorite = true)
        assertEquals("Body", note.content)
        assertEquals(4, note.star)
        assertEquals(true, note.favorite)
    }
}
