/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createAtmosphereStore } from '../../../src/hooks/svelte/atmosphere';
import { createRoomStore } from '../../../src/hooks/svelte/room';
import { createPresenceStore } from '../../../src/hooks/svelte/presence';
import { createOfflineQueueStore } from '../../../src/hooks/svelte/offlineQueue';
import type { OfflineQueueStoreState } from '../../../src/hooks/svelte/offlineQueue';
import { createMessageHistoryStore } from '../../../src/hooks/svelte/messageHistory';
import { createOptimisticStore } from '../../../src/hooks/svelte/optimistic';
import { createChatStore } from '../../../src/hooks/svelte/chat';
import type { OptimisticStoreState } from '../../../src/hooks/svelte/optimistic';
import type { AtmosphereRequest, Subscription, RoomMessage } from '../../../src/types';
import { Atmosphere } from '../../../src/core/atmosphere';

function createMockSubscription(): Subscription & { pushed: string[] } {
  const pushed: string[] = [];
  return {
    id: 'test-sub',
    state: 'connected' as const,
    pushed,
    push: vi.fn((msg: string | object | ArrayBuffer) => {
      pushed.push(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }),
    close: vi.fn(async () => {}),
    suspend: vi.fn(),
    resume: vi.fn(async () => {}),
    on: vi.fn(),
    off: vi.fn(),
  };
}

function createMockAtmosphere() {
  const mockSub = createMockSubscription();
  let handlers: Record<string, (...args: unknown[]) => void> = {};

  const atmosphere = {
    subscribe: vi.fn(async (_req: AtmosphereRequest, h: Record<string, unknown>) => {
      handlers = h as typeof handlers;
      return mockSub;
    }),
  } as unknown as Atmosphere;

  return {
    atmosphere,
    mockSub,
    handlers: () => handlers,
    triggerMessage: (body: string) => handlers.message?.({ responseBody: body }),
    triggerOpen: () => handlers.open?.(),
    triggerClose: () => handlers.close?.(),
    triggerError: (err: Error) => handlers.error?.(err),
    triggerReconnect: () => handlers.reconnect?.(),
  };
}

const baseRequest: AtmosphereRequest = {
  url: 'ws://localhost:8080/chat',
  transport: 'websocket',
};

function streamingFrame(type: string, seq: number, data?: string): string {
  return JSON.stringify({ type, data, sessionId: 'chat-session', seq });
}

describe('Svelte: createAtmosphereStore', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should start disconnected before any subscriber', () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    let state: unknown;
    const unsub = store.subscribe((v) => { state = v; });
    // Initial synchronous value before connection resolves
    expect((state as { state: string }).state).toBe('disconnected');
    unsub();
  });

  it('should connect on first subscriber', async () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    const values: unknown[] = [];
    const unsub = store.subscribe((v) => values.push({ ...v }));
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalledOnce());
    unsub();
  });

  it('should update state to connected on open', async () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    let latest: { state: string } = { state: '' };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerOpen();
    expect(latest.state).toBe('connected');
    unsub();
  });

  it('should update data on message', async () => {
    const { store } = createAtmosphereStore<string>(baseRequest, mock.atmosphere);
    let latest: { data: string | null } = { data: null };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerMessage('hello');
    expect(latest.data).toBe('hello');
    unsub();
  });

  it('should set error state on error', async () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    let latest: { state: string; error: Error | null } = { state: '', error: null };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerError(new Error('fail'));
    expect(latest.state).toBe('error');
    expect(latest.error?.message).toBe('fail');
    unsub();
  });

  it('should wrap subscribe failure in Error via catch block', async () => {
    // When atmosphere.subscribe() rejects with a string, the catch block wraps it
    const failingAtmosphere = {
      subscribe: vi.fn(async () => { throw 'connection refused'; }),
    } as unknown as Atmosphere;

    const { store } = createAtmosphereStore(baseRequest, failingAtmosphere);
    let latest: { state: string; error: Error | null } = { state: '', error: null };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(latest.state).toBe('error'));
    expect(latest.error).toBeInstanceOf(Error);
    expect(latest.error?.message).toBe('connection refused');
    unsub();
  });

  it('should set reconnecting state', async () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    let latest: { state: string } = { state: '' };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerReconnect();
    expect(latest.state).toBe('reconnecting');
    unsub();
  });

  it('should disconnect when all subscribers leave', async () => {
    const { store } = createAtmosphereStore(baseRequest, mock.atmosphere);
    const unsub = store.subscribe(() => {});
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    unsub();
    expect(mock.mockSub.close).toHaveBeenCalled();
  });

  it('should close connection if unsubscribed during connect', async () => {
    // Simulate a slow connection: subscribe() returns a promise that resolves
    // after a delay, giving the subscriber time to unsubscribe.
    const mockSub = createMockSubscription();
    let resolveSubscribe: (sub: typeof mockSub) => void;
    const slowAtmosphere = {
      subscribe: vi.fn(() => new Promise<typeof mockSub>((resolve) => {
        resolveSubscribe = resolve;
      })),
    } as unknown as Atmosphere;

    const { store } = createAtmosphereStore(baseRequest, slowAtmosphere);

    // Subscribe (triggers connect, which is now in-flight)
    const unsub = store.subscribe(() => {});

    // Immediately unsubscribe (triggers disconnect while connect is awaiting)
    unsub();

    // Now resolve the slow connection
    resolveSubscribe!(mockSub);

    // Give the async connect() a tick to finish
    await vi.waitFor(() => expect(mockSub.close).toHaveBeenCalled());

    // The subscription should have been closed because disconnect() was called
    // while connect() was still awaiting atmosphere.subscribe()
  });

  it('should push messages via push function', async () => {
    const { store, push } = createAtmosphereStore(baseRequest, mock.atmosphere);
    const unsub = store.subscribe(() => {});
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    push('test-msg');
    expect(mock.mockSub.push).toHaveBeenCalledWith('test-msg');
    unsub();
  });
});

describe('Svelte: createChatStore', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  let nextId: number;

  beforeEach(() => {
    mock = createMockAtmosphere();
    nextId = 0;
  });

  it('appends a user/assistant pair and streams assistant text', async () => {
    const { store, append, destroy } = createChatStore({
      request: baseRequest,
      instance: mock.atmosphere,
      generateId: () => `svelte-chat-${nextId++}`,
    });
    let latest: { messages: Array<{ role: string; content: string; status?: string }> } = { messages: [] };
    const unsub = store.subscribe((value) => { latest = value; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    await Promise.resolve();

    append('hello');
    expect(mock.mockSub.push).toHaveBeenCalledWith('hello');
    expect(latest.messages.map((message) => message.role)).toEqual(['user', 'assistant']);
    expect(latest.messages[1].status).toBe('streaming');

    mock.triggerMessage(streamingFrame('streaming-text', 1, 'Salut'));
    await vi.waitFor(() => expect(latest.messages[1].content).toBe('Salut'));
    expect(latest.messages[1].status).toBe('streaming');

    mock.triggerMessage(streamingFrame('complete', 2));
    await vi.waitFor(() => expect(latest.messages[1].status).toBe('complete'));

    unsub();
    destroy();
  });

  it('handleSubmit trims input and reset restores initial messages', async () => {
    const initialMessages = [{ id: 'system-1', role: 'system' as const, content: 'ready' }];
    const { store, setInput, handleSubmit, reset, destroy } = createChatStore({
      request: baseRequest,
      instance: mock.atmosphere,
      initialMessages,
      generateId: () => `svelte-chat-${nextId++}`,
    });
    let latest: { messages: unknown[]; input: string } = { messages: [], input: '' };
    const unsub = store.subscribe((value) => { latest = value; });
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    await Promise.resolve();

    setInput('  hi from form  ');
    handleSubmit({ preventDefault: vi.fn() });
    expect(latest.input).toBe('');
    expect(mock.mockSub.push).toHaveBeenCalledWith('hi from form');

    reset();
    expect(latest.messages).toEqual(initialMessages);

    unsub();
    destroy();
  });
});

describe('Svelte: createRoomStore', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  const member = { id: 'user-1' };

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should start with joined=false', () => {
    const { store } = createRoomStore(baseRequest, 'lobby', member, mock.atmosphere);
    let latest: { joined: boolean } = { joined: true };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    expect(latest.joined).toBe(false);
    unsub();
  });

  it('should send join message on subscribe', async () => {
    const { store } = createRoomStore(baseRequest, 'lobby', member, mock.atmosphere);
    const unsub = store.subscribe(() => {});
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));
    const sent = JSON.parse(mock.mockSub.pushed[0]) as RoomMessage;
    expect(sent.type).toBe('join');
    expect(sent.room).toBe('lobby');
    unsub();
  });

  it('should update joined and members on join response', async () => {
    const { store } = createRoomStore(baseRequest, 'lobby', member, mock.atmosphere);
    let latest: { joined: boolean; members: Array<{ id: string }> } = { joined: false, members: [] };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'join',
      room: 'lobby',
      data: [{ id: 'user-2' }],
    } as RoomMessage));

    expect(latest.joined).toBe(true);
    expect(latest.members.length).toBe(2); // self + user-2
    unsub();
  });

  it('should accumulate messages on broadcast', async () => {
    const { store } = createRoomStore<string>(baseRequest, 'lobby', member, mock.atmosphere);
    let latest: { messages: Array<{ data: string }> } = { messages: [] };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'broadcast',
      room: 'lobby',
      data: 'hello',
      member: { id: 'user-2' },
    } as RoomMessage));

    expect(latest.messages).toHaveLength(1);
    expect(latest.messages[0].data).toBe('hello');
    unsub();
  });

  it('should track presence join/leave', async () => {
    const { store } = createRoomStore(baseRequest, 'lobby', member, mock.atmosphere);
    let latest: { members: Array<{ id: string }> } = { members: [] };
    const unsub = store.subscribe((v) => { latest = v as typeof latest; });
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'join', member: { id: 'user-2' },
    } as RoomMessage));
    expect(latest.members.some((m) => m.id === 'user-2')).toBe(true);

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'leave', member: { id: 'user-2' },
    } as RoomMessage));
    expect(latest.members.some((m) => m.id === 'user-2')).toBe(false);
    unsub();
  });

  it('should cleanup on last unsubscribe', async () => {
    const { store } = createRoomStore(baseRequest, 'lobby', member, mock.atmosphere);
    const unsub = store.subscribe(() => {});
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));
    unsub();
    await vi.waitFor(() => expect(mock.mockSub.close).toHaveBeenCalled());
  });
});

describe('Svelte: createPresenceStore', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  const member = { id: 'user-1' };

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should expose members and count', async () => {
    const store = createPresenceStore(baseRequest, 'lobby', member, mock.atmosphere);
    let latest: { joined: boolean; members: Array<{ id: string }>; count: number } =
      { joined: false, members: [], count: 0 };
    const unsub = store.subscribe((v) => { latest = v; });
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    // Simulate server confirming join with existing members
    mock.triggerMessage(JSON.stringify({
      type: 'join', room: 'lobby', data: [],
    } as RoomMessage));

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'join', member: { id: 'user-2' },
    } as RoomMessage));

    expect(latest.members).toHaveLength(2); // self + user-2
    expect(latest.count).toBe(2);
    unsub();
  });
});

describe('Svelte: createOfflineQueueStore', () => {
  it('exposes a readable store that fires on every queue mutation', () => {
    const offline = createOfflineQueueStore<string>();
    const seen: OfflineQueueStoreState<string>[] = [];
    const unsub = offline.store.subscribe((v) => seen.push(v));
    expect(seen).toHaveLength(1);            // initial snapshot
    expect(seen[0].size).toBe(0);

    offline.enqueue('hello');
    expect(seen.length).toBeGreaterThan(1);
    expect(seen[seen.length - 1].size).toBe(1);
    expect(seen[seen.length - 1].messages[0].data).toBe('hello');

    offline.clear();
    expect(seen[seen.length - 1].size).toBe(0);
    unsub();
  });

  it('drain transitions queued messages to sent and notifies', () => {
    const offline = createOfflineQueueStore<string>();
    let latest: OfflineQueueStoreState<string> | null = null;
    const unsub = offline.store.subscribe((v) => { latest = v; });

    offline.enqueue('one');
    offline.enqueue('two');
    expect(latest!.size).toBe(2);

    const sent: string[] = [];
    offline.queue.drain((d) => { sent.push(d); });

    expect(sent).toEqual(['one', 'two']);
    expect(latest!.size).toBe(0);
    expect(latest!.pendingCount).toBe(2);
    expect(latest!.pending.every((m) => m.state === 'sent')).toBe(true);
    unsub();
  });

  it('reuses an externally-supplied OfflineQueue instance', async () => {
    const { OfflineQueue } = await import('../../../src/queue/offline-queue');
    const external = new OfflineQueue<string>();
    const offline = createOfflineQueueStore<string>({ instance: external });
    expect(offline.queue).toBe(external);
  });
});

describe('Svelte: createMessageHistoryStore', () => {
  it('emits the current cursor on subscribe and on each advance', () => {
    const history = createMessageHistoryStore();
    const seen: number[] = [];
    const unsub = history.store.subscribe((v) => seen.push(v));
    expect(seen).toEqual([0]);
    history.observe({ id: 3 });
    history.observe({ id: 7 });
    history.observe({ id: 5 }); // older, no notify
    expect(seen).toEqual([0, 3, 7]);
    unsub();
  });

  it('reset emits zero', () => {
    const history = createMessageHistoryStore();
    const seen: number[] = [];
    history.store.subscribe((v) => seen.push(v));
    history.observe({ id: 4 });
    history.reset();
    expect(seen[seen.length - 1]).toBe(0);
  });
});

describe('Svelte: createOptimisticStore', () => {
  it('notifies on send/commit/rollback/clear with the right state transitions', () => {
    const opt = createOptimisticStore<string>();
    let latest: OptimisticStoreState<string> = { messages: [], inFlightCount: 0 };
    const unsub = opt.store.subscribe((v) => { latest = v; });

    const a = opt.send('a');
    expect(latest.messages).toHaveLength(1);
    expect(latest.inFlightCount).toBe(1);

    opt.commit(a.id);
    expect(latest.messages[0].state).toBe('confirmed');
    expect(latest.inFlightCount).toBe(0);

    const b = opt.send('b');
    opt.rollback(b.id, 'nope');
    const bRecord = latest.messages.find((m) => m.id === b.id)!;
    expect(bRecord.state).toBe('failed');
    expect(bRecord.error).toBe('nope');

    opt.clear();
    expect(latest.messages).toHaveLength(0);
    unsub();
  });
});
