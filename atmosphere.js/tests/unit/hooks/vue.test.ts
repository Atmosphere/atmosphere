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
