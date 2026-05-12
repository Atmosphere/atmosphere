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
import type { AtmosphereRequest, Subscription, RoomMessage } from '../../../src/types';
import { Atmosphere } from '../../../src/core/atmosphere';

// Capture onUnmounted callbacks so we can trigger cleanup in tests
const unmountCallbacks: Array<() => void> = [];
vi.mock('vue', async () => {
  const actual = await vi.importActual<typeof import('vue')>('vue');
  return {
    ...actual,
    onUnmounted: (fn: () => void) => { unmountCallbacks.push(fn); },
  };
});

// Import AFTER mock is set up
const { useAtmosphere } = await import('../../../src/hooks/vue/useAtmosphere');
const { useRoom } = await import('../../../src/hooks/vue/useRoom');
const { usePresence } = await import('../../../src/hooks/vue/usePresence');
const { useStreaming } = await import('../../../src/hooks/vue/useStreaming');
const { useOfflineQueue } = await import('../../../src/hooks/vue/useOfflineQueue');
const { useMessageHistory } = await import('../../../src/hooks/vue/useMessageHistory');

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

describe('Vue: useAtmosphere', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
    unmountCallbacks.length = 0;
  });

  it('should start disconnected', () => {
    const { state } = useAtmosphere(baseRequest, mock.atmosphere);
    expect(state.value).toBe('disconnected');
  });

  it('should connect automatically', async () => {
    useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalledOnce());
  });

  it('should update state on open', async () => {
    const { state } = useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerOpen();
    expect(state.value).toBe('connected');
  });

  it('should update data on message', async () => {
    const { data } = useAtmosphere<string>(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerMessage('hello');
    expect(data.value).toBe('hello');
  });

  it('should set error on error', async () => {
    const { state, error } = useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerError(new Error('oops'));
    expect(state.value).toBe('error');
    expect(error.value?.message).toBe('oops');
  });

  it('should wrap subscribe failure in Error via catch block', async () => {
    // When atmosphere.subscribe() rejects with a string, the catch block wraps it
    const failingAtmosphere = {
      subscribe: vi.fn(async () => { throw 'connection refused'; }),
    } as unknown as Atmosphere;

    const { state, error } = useAtmosphere(baseRequest, failingAtmosphere);
    await vi.waitFor(() => expect(state.value).toBe('error'));
    expect(error.value).toBeInstanceOf(Error);
    expect(error.value?.message).toBe('connection refused');
  });

  it('should set reconnecting state', async () => {
    const { state } = useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    mock.triggerReconnect();
    expect(state.value).toBe('reconnecting');
  });

  it('should push messages', async () => {
    const { push } = useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    push('test');
    expect(mock.mockSub.push).toHaveBeenCalledWith('test');
  });

  it('should close subscription on unmount', async () => {
    useAtmosphere(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    expect(unmountCallbacks).toHaveLength(1);
    unmountCallbacks[0]();
    expect(mock.mockSub.close).toHaveBeenCalled();
  });
});

describe('Vue: useRoom', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  const member = { id: 'user-1' };

  beforeEach(() => {
    mock = createMockAtmosphere();
    unmountCallbacks.length = 0;
  });

  it('should start with joined=false', () => {
    const { joined } = useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    expect(joined.value).toBe(false);
  });

  it('should send join message', async () => {
    useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));
    const sent = JSON.parse(mock.mockSub.pushed[0]) as RoomMessage;
    expect(sent.type).toBe('join');
    expect(sent.room).toBe('lobby');
  });

  it('should update joined and members on join response', async () => {
    const { joined, members } = useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'join', room: 'lobby', data: [{ id: 'user-2' }],
    } as RoomMessage));

    expect(joined.value).toBe(true);
    expect(members.value.length).toBe(2);
  });

  it('should accumulate messages', async () => {
    const { messages } = useRoom<string>(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'broadcast', room: 'lobby', data: 'hi', member: { id: 'user-2' },
    } as RoomMessage));

    expect(messages.value).toHaveLength(1);
    expect(messages.value[0].data).toBe('hi');
  });

  it('should track presence join and leave', async () => {
    const { members } = useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'join', member: { id: 'user-2' },
    } as RoomMessage));
    expect(members.value.some((m) => m.id === 'user-2')).toBe(true);

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'leave', member: { id: 'user-2' },
    } as RoomMessage));
    expect(members.value.some((m) => m.id === 'user-2')).toBe(false);
  });

  it('should set error on error', async () => {
    const { error } = useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    // Error handler is on the room's internal subscribe, not the top-level handlers
    // Trigger via AtmosphereRooms' error propagation
    const subscribeCall = (mock.atmosphere.subscribe as ReturnType<typeof vi.fn>).mock.calls[0];
    const handlers = subscribeCall[1] as { error: (err: Error) => void };
    handlers.error(new Error('room error'));
    expect(error.value?.message).toBe('room error');
  });

  it('should cleanup on unmount', async () => {
    useRoom(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.atmosphere.subscribe).toHaveBeenCalled());
    expect(unmountCallbacks).toHaveLength(1);
    unmountCallbacks[0]();
    expect(mock.mockSub.close).toHaveBeenCalled();
  });
});

describe('Vue: usePresence', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  const member = { id: 'user-1' };

  beforeEach(() => {
    mock = createMockAtmosphere();
    unmountCallbacks.length = 0;
  });

  it('should expose members and count', async () => {
    const { members, count, isOnline } = usePresence(baseRequest, 'lobby', member, mock.atmosphere);
    await vi.waitFor(() => expect(mock.mockSub.pushed.length).toBeGreaterThan(0));

    // Simulate server confirming join with existing members
    mock.triggerMessage(JSON.stringify({
      type: 'join', room: 'lobby', data: [],
    } as RoomMessage));

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'join', member: { id: 'user-2' },
    } as RoomMessage));

    expect(members.value).toHaveLength(2); // self + user-2
    expect(count.value).toBe(2);
    expect(isOnline('user-2')).toBe(true);
    expect(isOnline('user-99')).toBe(false);
  });
});

describe('Vue: useOfflineQueue', () => {
  beforeEach(() => {
    unmountCallbacks.length = 0;
  });

  it('starts empty and surfaces reactive size + messages', () => {
    const { queue, messages, size, pendingCount } = useOfflineQueue<string>();
    expect(queue).toBeDefined();
    expect(size.value).toBe(0);
    expect(pendingCount.value).toBe(0);
    expect(messages.value).toEqual([]);
  });

  it('enqueue advances size reactively', () => {
    const { enqueue, size, messages } = useOfflineQueue<string>();
    enqueue('one');
    enqueue('two');
    expect(size.value).toBe(2);
    expect(messages.value.map((m) => m.data)).toEqual(['one', 'two']);
  });

  it('drain (called externally) flips messages from pending to sent', () => {
    const { queue, enqueue, size, pendingCount, pending } = useOfflineQueue<string>();
    enqueue('a');
    enqueue('b');
    const sent: string[] = [];
    queue.drain((d) => { sent.push(d); });
    expect(sent).toEqual(['a', 'b']);
    expect(size.value).toBe(0);
    expect(pendingCount.value).toBe(2);
    expect(pending.value.every((m) => m.state === 'sent')).toBe(true);
  });

  it('clear empties both buckets', () => {
    const { enqueue, track, clear, size, pendingCount } = useOfflineQueue<string>();
    enqueue('x');
    track('y');
    expect(size.value).toBe(1);
    expect(pendingCount.value).toBe(1);
    clear();
    expect(size.value).toBe(0);
    expect(pendingCount.value).toBe(0);
  });

  it('honors an externally-supplied OfflineQueue instance', async () => {
    const { OfflineQueue } = await import('../../../src/queue/offline-queue');
    const external = new OfflineQueue<string>({ maxSize: 3 });
    const { queue } = useOfflineQueue<string>({ instance: external });
    expect(queue).toBe(external);
  });
});

describe('Vue: useMessageHistory', () => {
  it('starts at 0 and advances on observe', () => {
    const { lastSeenId, observe } = useMessageHistory();
    expect(lastSeenId.value).toBe(0);
    observe({ id: 7 });
    expect(lastSeenId.value).toBe(7);
  });

  it('out-of-order observe does not regress the cursor', () => {
    const { lastSeenId, observe } = useMessageHistory();
    observe({ id: 5 });
    expect(observe({ id: 3 })).toBe(false);
    expect(lastSeenId.value).toBe(5);
  });

  it('reset returns to zero', () => {
    const { lastSeenId, observe, reset } = useMessageHistory();
    observe({ id: 9 });
    reset();
    expect(lastSeenId.value).toBe(0);
  });
});

describe('Vue: useStreaming lifecycle hooks', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
    unmountCallbacks.length = 0;
  });

  it('connectionState transitions through connecting → connected on open', async () => {
    const { connectionState } = useStreaming(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(connectionState.value).toBe('connecting'));
    mock.triggerOpen();
    await vi.waitFor(() => expect(connectionState.value).toBe('connected'));
  });

  it('connectionState becomes reconnecting on reconnect event', async () => {
    const { connectionState, isReconnecting } = useStreaming(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().open).toBeDefined());
    mock.triggerOpen();
    await vi.waitFor(() => expect(connectionState.value).toBe('connected'));
    mock.triggerReconnect();
    await vi.waitFor(() => expect(connectionState.value).toBe('reconnecting'));
    expect(isReconnecting.value).toBe(true);
  });

  it('connectionState becomes closed on close event', async () => {
    const { connectionState } = useStreaming(baseRequest, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().close).toBeDefined());
    mock.triggerClose();
    await vi.waitFor(() => expect(connectionState.value).toBe('closed'));
  });

  it('lifecycle callbacks fire on transport events', async () => {
    const onOpen = vi.fn();
    const onClose = vi.fn();
    const onReconnect = vi.fn();
    useStreaming(baseRequest, mock.atmosphere, { onOpen, onClose, onReconnect });
    await vi.waitFor(() => expect(mock.handlers().open).toBeDefined());

    mock.triggerOpen();
    mock.triggerReconnect();
    mock.triggerClose();

    await vi.waitFor(() => expect(onOpen).toHaveBeenCalled());
    expect(onReconnect).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
