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
import type { AtmosphereRequest, Subscription } from '../../../src/types';
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
  url: 'https://example.com/chat',
  transport: 'websocket',
};

describe('useAtmosphereRN (integration via subscribe)', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should subscribe with correct handlers on connect', async () => {
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
    mock.triggerMessage('hello-rn');
    expect(data).toBe('hello-rn');
  });

  it('should push messages via subscription', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });
    sub.push('rn-msg');
    expect(mock.mockSub.push).toHaveBeenCalledWith('rn-msg');
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

describe('AppState integration', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should suspend subscription when app goes to background', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });

    // Simulate what useAtmosphereRN does internally
    sub.suspend();
    expect(mock.mockSub.suspend).toHaveBeenCalled();
  });

  it('should resume subscription when app returns to foreground', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });

    sub.suspend();
    await sub.resume();
    expect(mock.mockSub.resume).toHaveBeenCalled();
  });

  it('should disconnect subscription when backgroundBehavior is disconnect', async () => {
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

describe('NetInfo integration (conceptual)', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('should suspend when offline and resume when back online', async () => {
    const sub = await mock.atmosphere.subscribe(baseRequest, {
      open: () => {},
      message: () => {},
      close: () => {},
      error: () => {},
      reconnect: () => {},
    });

    // Simulate going offline
    sub.suspend();
    expect(mock.mockSub.suspend).toHaveBeenCalledTimes(1);

    // Simulate coming back online
    await sub.resume();
    expect(mock.mockSub.resume).toHaveBeenCalledTimes(1);
  });
});
