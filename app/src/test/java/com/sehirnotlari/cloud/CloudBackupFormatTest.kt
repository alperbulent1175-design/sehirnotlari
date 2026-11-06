package com.sehirnotlari.cloud

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupFormatTest {
    private val notes = """{"Bursa":[{"id":1730000000001,"title":"Gezi","content":"Ulu Cami"},{"id":"abc","title":"Şişli’de ıhlamur 🙂"}],"Rize":[{"id":7}],"Van":[]}"""
    private val id = "b-1730000000000-0000abcd"

    private fun prepare(json: String = notes, deleted: List<DeletedNote> = emptyList(), now: Long = 1_730_000_000_000L, backupId: String = id) =
        CloudBackupFormat.prepare(json, deleted, "1.4", now, backupId)

    private fun meta(p: PreparedBackup, createdAt: Long = 1_730_000_000_500L) = BackupMeta(
        p.id, createdAt, p.noteCount, p.cityCount, p.deletedCount, p.byteLength, p.sha256, p.chunks.size, p.appVersion,
    )

    private fun payload(p: PreparedBackup) = String(p.chunks.fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8)

    @Test
    fun keepsTheStoredNotesByteForByteWithIdsAndCounts() {
        val p = prepare()
        assertEquals(3, p.noteCount)
        assertEquals(2, p.cityCount)
        assertEquals(1, p.chunks.size)
        val text = payload(p)
        assertTrue(text.contains("\"notes\":$notes,"))
        val json = JSONObject(text)
        assertEquals(CloudBackupFormat.FORMAT, json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(p.byteLength, text.toByteArray(Charsets.UTF_8).size)
        assertEquals(CloudBackupFormat.sha256(text.toByteArray(Charsets.UTF_8)), p.sha256)
    }

    @Test
    fun carriesDeletionRecordsButNotOnesOfNotesThatAreBack() {
        val p = prepare(deleted = listOf(DeletedNote("Rize", "7", 5L), DeletedNote("Muş", "9", 6L), DeletedNote("Ağrı", "1", 4L)))
        assertEquals(2, p.deletedCount)
        val deleted = JSONObject(payload(p)).getJSONArray("deleted")
        assertEquals("Ağrı", deleted.getJSONObject(0).getString("city"))
        assertEquals("Muş", deleted.getJSONObject(1).getString("city"))
        assertEquals(6L, deleted.getJSONObject(1).getLong("deletedAt"))
    }

    @Test
    fun splitsLargeBackupsIntoPiecesThatFitAFirestoreDocument() {
        val big = "x".repeat(400_000)
        val json = """{"Bursa":[${(1..5).joinToString(",") { """{"id":$it,"content":"$big"}""" }}]}"""
        val p = prepare(json)
        assertEquals(3, p.chunks.size)
        assertTrue(p.chunks.all { it.size <= CloudBackupFormat.CHUNK_BYTES })
        assertEquals(p.byteLength, p.chunks.sumOf { it.size })
        assertEquals(JSONObject(json).toString(), CloudBackupFormat.verify(meta(p), p.chunks).notesJson)
    }

    @Test
    fun refusesWhatShouldNotBecomeABackup() {
        assertTrue(assertThrows(CloudBackupException::class.java) { prepare("{}") }.empty)
        assertTrue(assertThrows(CloudBackupException::class.java) { prepare("""{"Van":[]}""") }.empty)
        assertFalse(assertThrows(CloudBackupException::class.java) { prepare("[1]") }.empty)
        assertThrows(CloudBackupException::class.java) { prepare("{bozuk") }
        assertThrows(CloudBackupException::class.java) { prepare("""{"Bursa":{"id":1}}""") }
        assertThrows(CloudBackupException::class.java) { prepare("""{"Bursa":[1]}""") }
        assertThrows(CloudBackupException::class.java) { prepare("""{" ":[{"id":1}]}""") }
        val huge = """{"Bursa":[{"id":1,"content":"${"x".repeat(CloudBackupFormat.MAX_BYTES)}"}]}"""
        assertTrue(assertThrows(CloudBackupException::class.java) { prepare(huge) }.message!!.contains("5 MB"))
    }

    @Test
    fun verifyAcceptsOnlyTheExactBackupItsMetaDescribes() {
        val p = prepare(deleted = listOf(DeletedNote("Muş", "9", 6L)))
        val m = meta(p)
        val restored = CloudBackupFormat.verify(m, p.chunks)
        assertEquals(JSONObject(notes).toString(), restored.notesJson)
        assertEquals(listOf(DeletedNote("Muş", "9", 6L)), restored.deleted)
        val tampered = p.chunks[0].copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        listOf(
            { CloudBackupFormat.verify(m, listOf(tampered)) },
            { CloudBackupFormat.verify(m, emptyList()) },
            { CloudBackupFormat.verify(m, p.chunks + listOf(ByteArray(1))) },
            { CloudBackupFormat.verify(m.copy(byteLength = m.byteLength + 1), p.chunks) },
            { CloudBackupFormat.verify(m.copy(noteCount = 4, sha256 = m.sha256), p.chunks) },
            { CloudBackupFormat.verify(m, listOf(p.chunks[0].copyOf(p.chunks[0].size - 1))) },
        ).forEach { check ->
            assertTrue(assertThrows(CloudBackupException::class.java) { check() }.message!!.contains("bozuk"))
        }
    }

    @Test
    fun aBackupFromANewerFormatAsksForAnUpdate() {
        val text = """{"format":"${CloudBackupFormat.FORMAT}","version":2,"notes":{"Bursa":[{"id":1}]},"deleted":[]}"""
        val bytes = text.toByteArray()
        val m = BackupMeta(id, 1L, 1, 1, 0, bytes.size, CloudBackupFormat.sha256(bytes), 1, "9.0")
        assertTrue(assertThrows(CloudBackupException::class.java) { CloudBackupFormat.verify(m, listOf(bytes)) }.message!!.contains("güncelleyin"))
        val other = text.replace(CloudBackupFormat.FORMAT, "baska-uygulama").replace("\"version\":2", "\"version\":1").toByteArray()
        val m2 = m.copy(byteLength = other.size, sha256 = CloudBackupFormat.sha256(other))
        assertThrows(CloudBackupException::class.java) { CloudBackupFormat.verify(m2, listOf(other)) }
    }

    @Test
    fun listsOnlyCompleteConsistentMetaDocuments() {
        val p = prepare()
        val fields = p.metaFields()
        assertEquals(meta(p, 42L), CloudBackupFormat.metaFrom(id, fields, 42L))
        // Firestore returns whole numbers as Long.
        val longs = fields.mapValues { (_, v) -> if (v is Int) v.toLong() else v }
        assertEquals(meta(p, 42L), CloudBackupFormat.metaFrom(id, longs, 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields, null))
        assertNull(CloudBackupFormat.metaFrom("../x", fields, 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("status" to "pending"), 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("format" to "x"), 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("version" to 2), 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("chunkCount" to 2), 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("sha256" to "nothex"), 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields - "noteCount", 42L))
        assertNull(CloudBackupFormat.metaFrom(id, fields + ("byteLength" to CloudBackupFormat.MAX_BYTES + 1), 42L))
    }

    @Test
    fun keepsTheNewestFiveAndNeverTheOlderOnes() {
        val p = prepare()
        val all = (1..7).map { meta(p, it * 1000L).copy(id = "b-${1730000000000 + it}-0000abcd") }.shuffled()
        val gone = CloudBackupFormat.beyondKept(all)
        assertEquals(listOf(2000L, 1000L), gone.map { it.createdAt })
        assertEquals(emptyList<BackupMeta>(), CloudBackupFormat.beyondKept(all.take(5)))
    }

    @Test
    fun contentHashIgnoresTheMomentButNotTheNotes() {
        val a = prepare(now = 1L, backupId = "b-0000000000001-00000001")
        val b = prepare(now = 2L, backupId = "b-0000000000002-00000002")
        assertEquals(a.contentHash, b.contentHash)
        assertNotEquals(a.sha256, b.sha256)
        assertNotEquals(a.contentHash, prepare(notes.replace("Ulu Cami", "Ulu Camii")).contentHash)
        assertNotEquals(a.contentHash, prepare(deleted = listOf(DeletedNote("Muş", "9", 6L))).contentHash)
    }

    @Test
    fun idsAreRandomAndSafeAsDocumentNames() {
        val one = CloudBackupFormat.newId(1_730_000_000_000L, kotlin.random.Random(1))
        assertTrue(CloudBackupFormat.ID.matches(one))
        assertNotEquals(one, CloudBackupFormat.newId(1_730_000_000_000L, kotlin.random.Random(2)))
    }

    @Test
    fun splitKeepsEveryByte() {
        val bytes = ByteArray(CloudBackupFormat.CHUNK_BYTES * 2 + 3) { it.toByte() }
        val parts = CloudBackupFormat.split(bytes)
        assertEquals(listOf(CloudBackupFormat.CHUNK_BYTES, CloudBackupFormat.CHUNK_BYTES, 3), parts.map { it.size })
        assertArrayEquals(bytes, parts.fold(ByteArray(0)) { a, b -> a + b })
    }

    @Test
    fun automaticBackupRunsOnlyWhenOnDueAndChanged() {
        val day = AutoBackupPolicy.INTERVAL_MS
        assertEquals(AutoSkip.DISABLED, AutoBackupPolicy.localSkip(false, 10 * day, 0L, null, "h"))
        assertEquals(AutoSkip.NOT_DUE, AutoBackupPolicy.localSkip(true, 10 * day, 10 * day - 1000, "x", "h"))
        assertEquals(AutoSkip.UNCHANGED, AutoBackupPolicy.localSkip(true, 10 * day, 8 * day, "h", "h"))
        assertNull(AutoBackupPolicy.localSkip(true, 10 * day, 8 * day, "x", "h"))
        assertNull(AutoBackupPolicy.localSkip(true, 10 * day, 0L, null, "h"))
        // A clock set back must not block backups forever.
        assertNull(AutoBackupPolicy.localSkip(true, 10 * day, 20 * day, "x", "h"))
    }

    @Test
    fun automaticBackupDoesNotPushGoodBackupsOutAfterAMassDeletion() {
        val p = prepare()
        val newest = meta(p, 2000L).copy(noteCount = 10)
        val older = meta(p, 1000L).copy(noteCount = 100, id = "b-1730000000009-0000abcd")
        assertEquals(AutoSkip.FEWER_NOTES, AutoBackupPolicy.remoteSkip(4, listOf(older, newest)))
        assertNull(AutoBackupPolicy.remoteSkip(5, listOf(older, newest)))
        assertNull(AutoBackupPolicy.remoteSkip(1, emptyList()))
    }
}
