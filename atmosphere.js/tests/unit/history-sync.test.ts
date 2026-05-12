import { describe, it, expect } from 'vitest';
import { MessageHistorySync } from '../../src/history/message-history-sync';
import type { HistoryStorage } from '../../src/history/message-history-sync';

/**
 * Client-side cursor for the server's history-sync protocol.
 * Server-side coverage lives in modules/cpr's RoomTest +
 * RoomProtocolCodecTest.
 */
describe('MessageHistorySync', () => {
  it('starts with lastSeenId=0 when no storage is configured', () => {
    const sync = new MessageHistorySync();
    expect(sync.lastSeenId).toBe(0);
  });

  it('advances the cursor on a strictly-greater incoming id', () => {
    const sync = new MessageHistorySync();
    expect(sync.observe({ id: 5 })).toBe(true);
    expect(sync.lastSeenId).toBe(5);
    expect(sync.observe({ id: 8 })).toBe(true);
    expect(sync.lastSeenId).toBe(8);
  });

  it('does not regress on out-of-order arrivals', () => {
    const sync = new MessageHistorySync();
    sync.observe({ id: 10 });
    expect(sync.observe({ id: 7 })).toBe(false);
    expect(sync.lastSeenId).toBe(10);
  });

  it('ignores messages without an id (legacy/non-history rooms)', () => {
    const sync = new MessageHistorySync();
    expect(sync.observe({ type: 'presence', memberId: 'alice' })).toBe(false);
    expect(sync.observe({})).toBe(false);
    expect(sync.observe(null)).toBe(false);
    expect(sync.observe(undefined)).toBe(false);
    expect(sync.lastSeenId).toBe(0);
  });

  it('ignores duplicate observations of the same id', () => {
    const sync = new MessageHistorySync();
    sync.observe({ id: 4 });
    expect(sync.observe({ id: 4 })).toBe(false);
    expect(sync.lastSeenId).toBe(4);
  });

  it('reset clears the cursor and the storage key', () => {
    const store = makeInMemoryStorage();
    const sync = new MessageHistorySync({ storage: store });
    sync.observe({ id: 9 });
    expect(store.getItem('atmosphere:lastSeenId')).toBe('9');

    sync.reset();
    expect(sync.lastSeenId).toBe(0);
    expect(store.getItem('atmosphere:lastSeenId')).toBeNull();
  });

  it('hydrates from storage when present', () => {
    const store = makeInMemoryStorage();
    store.setItem('atmosphere:lastSeenId', '42');
    const sync = new MessageHistorySync({ storage: store });
    expect(sync.lastSeenId).toBe(42);
  });

  it('honors a custom storage key', () => {
    const store = makeInMemoryStorage();
    const sync = new MessageHistorySync({ storage: store, storageKey: 'room:lobby:since' });
    sync.observe({ id: 12 });
    expect(store.getItem('room:lobby:since')).toBe('12');
    expect(store.getItem('atmosphere:lastSeenId')).toBeNull();
  });

  it('persists the cursor on every advance', () => {
    const store = makeInMemoryStorage();
    const sync = new MessageHistorySync({ storage: store });
    sync.observe({ id: 1 });
    expect(store.getItem('atmosphere:lastSeenId')).toBe('1');
    sync.observe({ id: 4 });
    expect(store.getItem('atmosphere:lastSeenId')).toBe('4');
    sync.observe({ id: 3 }); // older, no write
    expect(store.getItem('atmosphere:lastSeenId')).toBe('4');
  });

  it('ignores corrupted storage values', () => {
    const store = makeInMemoryStorage();
    store.setItem('atmosphere:lastSeenId', 'not-a-number');
    const sync = new MessageHistorySync({ storage: store });
    expect(sync.lastSeenId).toBe(0);
  });

  it('survives storage write failures without dropping the in-memory cursor', () => {
    const failingStorage: HistoryStorage = {
      getItem: () => null,
      setItem: () => { throw new Error('quota exceeded'); },
      removeItem: () => { throw new Error('quota exceeded'); },
    };
    const sync = new MessageHistorySync({ storage: failingStorage });
    expect(() => sync.observe({ id: 7 })).not.toThrow();
    expect(sync.lastSeenId).toBe(7);
  });
});

function makeInMemoryStorage(): HistoryStorage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => (map.has(k) ? map.get(k)! : null),
    setItem: (k, v) => { map.set(k, v); },
    removeItem: (k) => { map.delete(k); },
  };
}
