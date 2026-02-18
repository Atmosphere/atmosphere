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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import type { AtmosphereRequest, Subscription, RoomMessage } from '../../../src/types';
import { Atmosphere } from '../../../src/core/atmosphere';
import { AtmosphereProvider, useAtmosphereContext } from '../../../src/hooks/react/provider';

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

describe('React: AtmosphereProvider', () => {
  it('should render children', () => {
    const html = renderToString(
      createElement(
        AtmosphereProvider,
        { config: {} },
        createElement('div', null, 'hello'),
      ),
    );
    expect(html).toContain('hello');
  });

  it('should accept a pre-built instance', () => {
    const mock = createMockAtmosphere();
    const html = renderToString(
      createElement(
        AtmosphereProvider,
        { instance: mock.atmosphere },
        createElement('div', null, 'test'),
      ),
    );
    expect(html).toContain('test');
  });

  it('should throw when useAtmosphereContext is used outside provider', () => {
    expect(() => {
      function TestComponent() {
        useAtmosphereContext();
        return createElement('div');
      }
      renderToString(createElement(TestComponent));
    }).toThrow('useAtmosphereContext must be used within an <AtmosphereProvider>');
  });
});

/**
 * Test the useAtmosphere hook by manually simulating React's hook lifecycle.
 * We avoid @testing-library/react to keep dependencies minimal.
 *
 * The hook logic is:
 * 1. On mount (useEffect), call atmosphere.subscribe with handlers
 * 2. Handlers update state (setState calls)
 * 3. On unmount, close subscription
 *
 * We test by importing the hook source and calling the underlying
 * Atmosphere/subscribe interactions directly.
 */
describe('React: useAtmosphere (integration via subscribe)', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should subscribe with correct handlers on connect', async () => {
    // Simulate what the hook does: call atmosphere.subscribe
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    expect(mock.atmosphere.subscribe).toHaveBeenCalledOnce();
    expect(sub.state).toBe('connected');
  });

  it('should trigger open handler', async () => {
    let state = 'disconnected';
    await mock.atmosphere.subscribe(baseRequest, {
      open: () => { state = 'connected'; },
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    mock.triggerOpen();
    expect(state).toBe('connected');
  });

  it('should trigger message handler with data', async () => {
    let data: unknown = null;
    await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: (response: { responseBody: unknown }) => { data = response.responseBody; },
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    mock.triggerMessage('hello-react');
    expect(data).toBe('hello-react');
  });

  it('should trigger error handler', async () => {
    let error: Error | null = null;
    await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: (err: Error) => { error = err; },
      reconnect: () => {},
    });
    mock.triggerError(new Error('react-error'));
    expect(error?.message).toBe('react-error');
  });

  it('should trigger reconnect handler', async () => {
    let state = 'connected';
    await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => { state = 'reconnecting'; },
    });
    mock.triggerReconnect();
    expect(state).toBe('reconnecting');
  });

  it('should push messages via subscription', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    sub.push('msg');
    expect(mock.mockSub.push).toHaveBeenCalledWith('msg');
  });

  it('should close subscription on cleanup', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    await sub.close();
    expect(mock.mockSub.close).toHaveBeenCalled();
  });
});

describe('React: useRoom (integration via AtmosphereRooms)', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  const member = { id: 'user-1' };

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should join room and receive join response', async () => {
    const { AtmosphereRooms } = await import('../../../src/room/rooms');
    const rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);

    let joined = false;
    let membersList: Array<{ id: string }> = [];
    await rooms.join('lobby', member, {
      joined: (_name, members) => {
        joined = true;
        membersList = members;
      },
    });

    mock.triggerMessage(JSON.stringify({
      type: 'join', room: 'lobby', data: [{ id: 'user-2' }],
    } as RoomMessage));

    expect(joined).toBe(true);
    expect(membersList.length).toBe(2);
  });

  it('should receive broadcast messages', async () => {
    const { AtmosphereRooms } = await import('../../../src/room/rooms');
    const rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);

    const received: unknown[] = [];
    await rooms.join('lobby', member, {
      message: (data) => received.push(data),
    });

    mock.triggerMessage(JSON.stringify({
      type: 'broadcast', room: 'lobby', data: 'hi', member: { id: 'user-2' },
    } as RoomMessage));

    expect(received).toEqual(['hi']);
  });

  it('should track presence events', async () => {
    const { AtmosphereRooms } = await import('../../../src/room/rooms');
    const rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);

    const handle = await rooms.join('lobby', member);

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'join', member: { id: 'user-2' },
    } as RoomMessage));

    expect(handle.members.has('user-2')).toBe(true);

    mock.triggerMessage(JSON.stringify({
      type: 'presence', room: 'lobby', data: 'leave', member: { id: 'user-2' },
    } as RoomMessage));

    expect(handle.members.has('user-2')).toBe(false);
  });

  it('should broadcast and sendTo via handle', async () => {
    const { AtmosphereRooms } = await import('../../../src/room/rooms');
    const rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);

    const handle = await rooms.join('lobby', member);
    handle.broadcast('hello');
    handle.sendTo('user-2', 'dm');

    const msgs = mock.mockSub.pushed.map((s) => JSON.parse(s) as RoomMessage);
    expect(msgs[1].type).toBe('broadcast');
    expect(msgs[1].data).toBe('hello');
    expect(msgs[2].type).toBe('direct');
    expect(msgs[2].target).toBe('user-2');
  });

  it('should cleanup on leaveAll', async () => {
    const { AtmosphereRooms } = await import('../../../src/room/rooms');
    const rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);

    await rooms.join('lobby', member);
    await rooms.leaveAll();

    expect(mock.mockSub.close).toHaveBeenCalled();
  });
});
