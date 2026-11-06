package com.sehirnotlari.data

/** Records bridge writes so that a write cut off by a crash can be proven at the next start. */
class NoteWriteJournal(
    private val dao: NoteJournalDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Blocking; call on the bridge thread after [RoomNoteWriteSequencer.nextTicket] and before queueing the write. */
    fun recordUpsert(ticket: Long, city: String, noteId: String, draft: RoomNoteDraft) =
        record(ticket, city, noteId, NoteWriteJournalEntry.UPSERT, NoteFingerprint.of(draft))

    fun recordDelete(ticket: Long, city: String, noteId: String) =
        record(ticket, city, noteId, NoteWriteJournalEntry.DELETE, null)

    /** Blocking; call on the bridge thread before the page writes `tur_v10` (see [RoomNoteRestorer]). */
    fun recordRestore(ticket: Long, city: String, noteId: String, draft: RoomNoteDraft) =
        record(ticket, city, noteId, NoteWriteJournalEntry.RESTORE, NoteFingerprint.of(draft))

    /** Leaves an entry alone if startup reconciliation already resolved it. */
    suspend fun resolve(ticket: Long, ack: RoomWriteAck) = dao.resolvePending(
        ticket,
        when (ack) {
            RoomWriteAck.Applied -> NoteWriteJournalEntry.APPLIED
            RoomWriteAck.IgnoredStale -> NoteWriteJournalEntry.IGNORED
            is RoomWriteAck.Rejected -> NoteWriteJournalEntry.REJECTED
        },
        clock()
    )

    suspend fun failed(ticket: Long) = dao.resolvePending(ticket, NoteWriteJournalEntry.FAILED, clock())

    /** The page could not store the notes of a recorded restore; nothing is left to restore. */
    suspend fun cancelled(ticket: Long) = dao.resolvePending(ticket, NoteWriteJournalEntry.SUPERSEDED, clock())

    private fun record(ticket: Long, city: String, noteId: String, op: String, fingerprint: String?) =
        dao.insert(
            NoteWriteJournalEntry(
                ticket = ticket,
                noteKey = RoomNoteKeys.key(city, noteId),
                city = city,
                op = op,
                fingerprint = fingerprint,
                state = NoteWriteJournalEntry.PENDING,
                createdAt = clock(),
            )
        )
}
