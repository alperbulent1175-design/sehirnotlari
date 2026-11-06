// Firestore rules for cloud backups, against the local emulator only (`npm test`; never deploys).
import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';

const CHUNK = 700000;
let env;

before(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080').split(':');
  env = await initializeTestEnvironment({
    projectId: 'demo-sehirnotlari',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host, port: Number(port) },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const blob = (bytes) => firebase.firestore.Blob.fromUint8Array(bytes);
const now = () => firebase.firestore.FieldValue.serverTimestamp();
let seq = 0;
const newId = () => `b-${String(1730000000000 + (++seq)).padStart(13, '0')}-${randomBytes(4).toString('hex')}`;

/** A payload of [size] bytes split like CloudBackupFormat.split, and its meta fields. */
function backup(size = 1200) {
  const bytes = randomBytes(size);
  const chunks = [];
  for (let i = 0; i < size; i += CHUNK) chunks.push(bytes.subarray(i, Math.min(i + CHUNK, size)));
  const meta = {
    format: 'sehirnotlari-cloud-backup', version: 1, status: 'complete', createdAt: now(),
    noteCount: 5, cityCount: 4, deletedCount: 1, byteLength: size,
    sha256: createHash('sha256').update(bytes).digest('hex'), chunkCount: chunks.length, appVersion: '1.4',
  };
  return { meta, chunks };
}

/** The app's write: meta document and every piece in one batch. */
function write(store, uid, id, { meta, chunks }, { skip = [], extra = [] } = {}) {
  const ref = store.doc(`users/${uid}/backups/${id}`);
  const batch = store.batch();
  if (meta) batch.set(ref, meta);
  chunks.forEach((c, i) => { if (!skip.includes(i)) batch.set(ref.collection('chunks').doc(String(i)), { index: i, data: blob(c) }); });
  extra.forEach(([name, data]) => batch.set(ref.collection('chunks').doc(name), data));
  return batch.commit();
}

async function seed(uid, id, b = backup()) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const store = ctx.firestore();
    const ref = store.doc(`users/${uid}/backups/${id}`);
    await ref.set({ ...b.meta, createdAt: firebase.firestore.Timestamp.now() });
    await Promise.all(b.chunks.map((c, i) => ref.collection('chunks').doc(String(i)).set({ index: i, data: blob(c) })));
  });
  return b;
}

describe('the owner', () => {
  test('creates a complete backup in one batch', async () => {
    await assertSucceeds(write(db('alice'), 'alice', newId(), backup()));
  });

  test('creates the largest backup the app makes (8 pieces, 5 MiB)', async () => {
    await assertSucceeds(write(db('alice'), 'alice', newId(), backup(5 * 1024 * 1024)));
  });

  test('reads, lists and deletes their backups', async () => {
    const id = newId();
    const b = await seed('alice', id);
    const store = db('alice');
    await assertSucceeds(store.collection('users/alice/backups').get());
    await assertSucceeds(store.doc(`users/alice/backups/${id}/chunks/0`).get());
    const ref = store.doc(`users/alice/backups/${id}`);
    const batch = store.batch();
    batch.delete(ref);
    b.chunks.forEach((_, i) => batch.delete(ref.collection('chunks').doc(String(i))));
    await assertSucceeds(batch.commit());
  });
});

describe('other people', () => {
  test('cannot read, list or delete someone else\'s backups', async () => {
    const id = newId();
    await seed('alice', id);
    const bob = db('bob');
    await assertFails(bob.collection('users/alice/backups').get());
    await assertFails(bob.doc(`users/alice/backups/${id}`).get());
    await assertFails(bob.doc(`users/alice/backups/${id}/chunks/0`).get());
    await assertFails(bob.doc(`users/alice/backups/${id}`).delete());
    await assertFails(bob.collectionGroup('backups').get());
    await assertFails(bob.collectionGroup('chunks').get());
  });

  test('cannot write into someone else\'s space', async () => {
    await assertFails(write(db('bob'), 'alice', newId(), backup()));
  });

  test('without signing in nothing is readable or writable', async () => {
    const id = newId();
    await seed('alice', id);
    await assertFails(db(null).doc(`users/alice/backups/${id}`).get());
    await assertFails(db(null).collection('users/alice/backups').get());
    await assertFails(write(db(null), 'alice', newId(), backup()));
  });
});

describe('incomplete or altered backups are refused', () => {
  test('a meta document without its pieces', async () => {
    await assertFails(write(db('alice'), 'alice', newId(), { ...backup(), chunks: [] }));
  });

  test('pieces without a meta document', async () => {
    await assertFails(write(db('alice'), 'alice', newId(), { ...backup(), meta: null }));
  });

  test('a missing middle piece', async () => {
    await assertFails(write(db('alice'), 'alice', newId(), backup(3 * CHUNK - 10), { skip: [1] }));
  });

  test('a piece beyond the recorded count', async () => {
    await assertFails(write(db('alice'), 'alice', newId(), backup(), { extra: [['1', { index: 1, data: blob(randomBytes(10)) }]] }));
  });

  test('pieces that are too large, mislabelled or not bytes', async () => {
    const store = db('alice');
    await assertFails(write(store, 'alice', newId(), backup(), { extra: [['0', { index: 0, data: blob(randomBytes(CHUNK + 1)) }]] }));
    const b = backup();
    const ref = (id) => store.doc(`users/alice/backups/${id}`);
    for (const piece of [{ index: 1, data: blob(b.chunks[0]) }, { index: 0, data: 'text' }, { index: 0, data: blob(b.chunks[0]), extra: 1 }]) {
      const id = newId();
      const batch = store.batch();
      batch.set(ref(id), b.meta);
      batch.set(ref(id).collection('chunks').doc('0'), piece);
      await assertFails(batch.commit());
    }
  });

  for (const [name, change] of [
    ['a status other than complete', { status: 'pending' }],
    ['another format', { format: 'baska' }],
    ['a newer version', { version: 2 }],
    ['a client-chosen time', { createdAt: firebase.firestore.Timestamp.fromMillis(1) }],
    ['an unknown field', { deviceModel: 'Pixel' }],
    ['a size that does not match the pieces', { byteLength: 800000 }],
    ['a malformed checksum', { sha256: 'abc' }],
    ['zero notes', { noteCount: 0 }],
    ['too many pieces', { chunkCount: 9 }],
  ]) {
    test(`a meta document with ${name}`, async () => {
      const b = backup();
      await assertFails(write(db('alice'), 'alice', newId(), { ...b, meta: { ...b.meta, ...change } }));
    });
  }

  test('a backup id that is not the app\'s format', async () => {
    await assertFails(write(db('alice'), 'alice', 'my-backup', backup()));
    await assertFails(write(db('alice'), 'alice', 'b-1730000000000-ABCDEF12', backup()));
  });
});

describe('a stored backup cannot be changed', () => {
  test('its meta document cannot be updated', async () => {
    const id = newId();
    await seed('alice', id);
    await assertFails(db('alice').doc(`users/alice/backups/${id}`).update({ noteCount: 6 }));
  });

  test('pieces cannot be replaced or added later', async () => {
    const id = newId();
    await seed('alice', id);
    const pieces = db('alice').collection(`users/alice/backups/${id}/chunks`);
    await assertFails(pieces.doc('0').set({ index: 0, data: blob(randomBytes(10)) }));
    await assertFails(pieces.doc('1').set({ index: 1, data: blob(randomBytes(10)) }));
  });

  test('a backup cannot be overwritten by a new one with the same id', async () => {
    const id = newId();
    await seed('alice', id);
    await assertFails(write(db('alice'), 'alice', id, backup()));
  });
});

test('everything outside users/{uid}/backups is denied', async () => {
  const store = db('alice');
  await assertFails(store.doc('users/alice').set({ name: 'x' }));
  await assertFails(store.doc('users/alice').get());
  await assertFails(store.doc('notes/alice').set({ a: 1 }));
  await assertFails(store.doc('users/alice/other/1').set({ a: 1 }));
});
