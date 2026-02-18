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

  it('should push messages via push function', async () => {
    const { store, push } = createAtmosphereStore(baseRequest, mock.atmosphere);
    const unsub = store.subscribe(() => {});
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    push('test-msg');
    expect(mock.mockSub.push).toHaveBeenCalledWith('test-msg');
    unsub();
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
