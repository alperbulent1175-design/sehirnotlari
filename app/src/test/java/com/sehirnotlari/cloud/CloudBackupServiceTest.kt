package com.sehirnotlari.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The backup rules against an in-memory store that, like a Firestore batch, writes all or nothing. */
class CloudBackupServiceTest {
    private class Stored(val fields: Map<String, Any>, val createdAt: Long, val chunks: MutableList<ByteArray>)

    private class FakeStore : CloudBackupStore {
        val users = mutableMapOf<String, MutableMap<String, Stored>>()
        var serverTime = 1_000L
        var offline = false
        var failCreate = false
        /** The write lands on the server but its answer is lost (timeout, killed app). */
        var loseCreateAnswer = false
        var corruptOnCreate = false
        var failDelete = false
        var creates = 0
        var deletes = 0

        private fun online() { if (offline) throw CloudBackupException(CloudErrors.forCode("UNAVAILABLE")) }
        fun of(uid: String) = users.getOrPut(uid) { mutableMapOf() }

        override suspend fun list(uid: String): List<BackupMeta> {
            online()
            return of(uid).mapNotNull { (id, s) -> CloudBackupFormat.metaFrom(id, s.fields, s.createdAt) }
        }

        override suspend fun create(uid: String, backup: PreparedBackup) {
            online()
            if (failCreate) throw CloudBackupException(CloudErrors.forCode("UNAVAILABLE"))
            creates++
            serverTime += 1_000
            val chunks = backup.chunks.map { it.copyOf() }.toMutableList()
            if (corruptOnCreate) chunks[0][0] = (chunks[0][0] + 1).toByte()
            of(uid)[backup.id] = Stored(backup.metaFields(), serverTime, chunks)
            if (loseCreateAnswer) throw CloudBackupException(CloudErrors.TIMEOUT)
        }

        override suspend fun chunks(uid: String, meta: BackupMeta): List<ByteArray> {
            online()
            return of(uid)[meta.id]?.chunks?.map { it.copyOf() } ?: throw CloudBackupException("Yedek eksik veya bozuk")
        }

        override suspend fun delete(uid: String, meta: BackupMeta) {
            online()
            if (failDelete) throw CloudBackupException(CloudErrors.forCode("UNAVAILABLE"))
            deletes++
            of(uid).remove(meta.id)
        }
    }

    private val store = FakeStore()
    private var now = 1_730_000_000_000L
    private val service = CloudBackupService(store, clock = { now }, random = Random(7))
    private fun notes(n: Int) = """{"Bursa":[${(1..n).joinToString(",") { """{"id":$it,"title":"Not $it"}""" }}]}"""
    private val day = AutoBackupPolicy.INTERVAL_MS

    private suspend fun manual(json: String = notes(3), uid: String = "alice") =
        service.backup(uid, json, emptyList(), "1.4", null).also { now += 60_000 }

    @Test
    fun manualBackupIsWrittenReadBackAndListed() = runTest {
        val out = manual()
        val backup = out.backup!!
        assertEquals(3, backup.noteCount)
        assertNull(out.skipped)
        assertEquals(listOf(backup.id), service.list("alice").map { it.id })
        val restored = service.fetch("alice", backup.id)
        assertEquals(3, restored.meta.noteCount)
        assertTrue(restored.notesJson.contains("Not 3"))
    }

    @Test
    fun keepsOnlyTheNewestFiveCompleteBackups() = runTest {
        val ids = (1..7).map { manual(notes(it)).backup!!.id }
        val listed = service.list("alice")
        assertEquals(5, listed.size)
        assertEquals(ids.takeLast(5).reversed(), listed.map { it.id })
    }

    @Test
    fun aFailedWriteDeletesNothing() = runTest {
        repeat(5) { manual(notes(it + 1)) }
        val before = service.list("alice").map { it.id }
        store.failCreate = true
        assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { manual(notes(9)) } }
        assertEquals(before, service.list("alice").map { it.id })
        assertEquals(0, store.deletes)
    }

    @Test
    fun aWriteWhoseAnswerIsLostIsNotReportedAsDoneAndPrunesNothing() = runTest {
        repeat(5) { manual(notes(it + 1)) }
        store.loseCreateAnswer = true
        assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { manual(notes(9)) } }
        assertEquals(0, store.deletes)
        // The atomic write did land whole: it is listed, intact, and the next backup prunes the extra one.
        assertEquals(6, service.list("alice").size)
        store.loseCreateAnswer = false
        manual(notes(10))
        assertEquals(5, service.list("alice").size)
    }

    @Test
    fun aBackupThatReadsBackDamagedIsRemovedAndTheOldOnesStay() = runTest {
        repeat(5) { manual(notes(it + 1)) }
        val before = service.list("alice").map { it.id }
        store.corruptOnCreate = true
        val e = assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { manual(notes(9)) } }
        assertTrue(e.message!!.contains("eski yedekleriniz korunuyor"))
        assertEquals(before, service.list("alice").map { it.id })
    }

    @Test
    fun offlineFailsBeforeAnythingIsWritten() = runTest {
        store.offline = true
        assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { manual() } }
        assertEquals(0, store.creates)
    }

    @Test
    fun aFailedPruneKeepsTheNewBackupAndIsReported() = runTest {
        repeat(5) { manual(notes(it + 1)) }
        store.failDelete = true
        val out = manual(notes(9))
        assertNotNull(out.backup)
        assertEquals(1, out.pruneFailed)
        assertEquals(6, service.list("alice").size)
    }

    @Test
    fun eachUserSeesOnlyTheirOwnBackups() = runTest {
        val a = manual(uid = "alice").backup!!.id
        manual(uid = "bob")
        assertEquals(listOf(a), service.list("alice").map { it.id })
        assertThrows(CloudBackupException::class.java) {
            kotlinx.coroutines.runBlocking { service.fetch("bob", a) }
        }
    }

    @Test
    fun automaticBackupSkipsWithoutWritingWhenOffDueOrUnchanged() = runTest {
        fun auto(enabled: Boolean, lastAt: Long, hash: String?) = CloudBackupService.AutoState(enabled, lastAt, hash)
        val first = service.backup("alice", notes(3), emptyList(), "1.4", auto(true, 0L, null))
        assertNotNull(first.backup)
        assertEquals(1, store.creates)
        now += 2 * day
        assertEquals(AutoSkip.DISABLED, service.backup("alice", notes(4), emptyList(), "1.4", auto(false, 0L, null)).skipped)
        assertEquals(AutoSkip.NOT_DUE, service.backup("alice", notes(4), emptyList(), "1.4", auto(true, now - 1000, null)).skipped)
        assertEquals(AutoSkip.UNCHANGED, service.backup("alice", notes(3), emptyList(), "1.4", auto(true, now - 2 * day, first.contentHash)).skipped)
        assertEquals(AutoSkip.EMPTY, service.backup("alice", "{}", emptyList(), "1.4", auto(true, 0L, null)).skipped)
        assertEquals(1, store.creates)
    }

    @Test
    fun automaticBackupAfterAMassDeletionKeepsTheGoodBackups() = runTest {
        manual(notes(20))
        val out = service.backup("alice", notes(3), emptyList(), "1.4", CloudBackupService.AutoState(true, 0L, null))
        assertEquals(AutoSkip.FEWER_NOTES, out.skipped)
        assertEquals(1, store.creates)
        // The user can still decide to back up fewer notes by hand.
        assertNotNull(manual(notes(3)).backup)
    }

    @Test
    fun manualBackupOfNoNotesIsRefused() = runTest {
        val e = assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { manual("{}") } }
        assertTrue(e.empty)
        assertEquals(0, store.creates)
    }

    @Test
    fun fetchRefusesUnknownAndDamagedBackups() = runTest {
        val id = manual().backup!!.id
        assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { service.fetch("alice", "../other") } }
        assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { service.fetch("alice", "b-1730000000000-ffffffff") } }
        store.of("alice")[id]!!.chunks[0][5] = 0
        val e = assertThrows(CloudBackupException::class.java) { kotlinx.coroutines.runBlocking { service.fetch("alice", id) } }
        assertTrue(e.message!!.contains("Notlarınız değişmedi"))
    }
}
