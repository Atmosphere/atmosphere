import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';
import type { AtmosphereRequest } from '../../src/types';

describe('Atmosphere Transport Fallback', () => {
  let mockWs: any;
  let mockEventSource: any;

  beforeEach(() => {
    mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      readyState: WebSocket.OPEN,
      onopen: null,
      onmessage: null,
      onerror: null,
      onclose: null,
      binaryType: 'arraybuffer',
    };

    mockEventSource = {
      close: vi.fn(),
      onopen: null as ((ev: Event) => void) | null,
      onmessage: null as ((ev: MessageEvent) => void) | null,
      onerror: null as ((ev: Event) => void) | null,
    };
  });

  it('should use websocket transport by default', async () => {
    global.WebSocket = function () {
      setTimeout(() => { if (mockWs.onopen) mockWs.onopen({ type: 'open' }); }, 0);
      return mockWs;
    } as any;

    const atmosphere = new Atmosphere();
    const sub = await atmosphere.subscribe({
      url: 'http://localhost/test',
      transport: 'websocket',
    });

    expect(sub.state).toBe('connected');
    await sub.close();
  });

  it('should fallback to SSE when websocket fails', async () => {
    // WebSocket will fail
    global.WebSocket = function () {
      setTimeout(() => { if (mockWs.onerror) mockWs.onerror({ type: 'error' }); }, 0);
      return mockWs;
    } as any;

    // SSE will succeed
    global.EventSource = function () {
      setTimeout(() => { if (mockEventSource.onopen) mockEventSource.onopen({} as Event); }, 0);
      return mockEventSource;
    } as any;

    const atmosphere = new Atmosphere();
    const sub = await atmosphere.subscribe(
      {
        url: 'http://localhost/test',
        transport: 'websocket',
        fallbackTransport: 'sse',
      },
    );

    expect(sub.state).toBe('connected');
    await sub.close();
  });

  it('should fail when primary and no fallback configured', async () => {
    global.WebSocket = function () {
      setTimeout(() => { if (mockWs.onerror) mockWs.onerror({ type: 'error' }); }, 0);
      return mockWs;
    } as any;

    const atmosphere = new Atmosphere();

    await expect(
      atmosphere.subscribe({
        url: 'http://localhost/test',
        transport: 'websocket',
      }),
    ).rejects.toThrow();
  });

  it('should use SSE transport directly', async () => {
    global.EventSource = function () {
      setTimeout(() => { if (mockEventSource.onopen) mockEventSource.onopen({} as Event); }, 0);
      return mockEventSource;
    } as any;

    const atmosphere = new Atmosphere();
    const sub = await atmosphere.subscribe({
      url: 'http://localhost/test',
      transport: 'sse',
    });

    expect(sub.state).toBe('connected');
    await sub.close();
  });

  it('should use config-level fallback transport', async () => {
    global.WebSocket = function () {
      setTimeout(() => { if (mockWs.onerror) mockWs.onerror({ type: 'error' }); }, 0);
      return mockWs;
    } as any;

    global.EventSource = function () {
      setTimeout(() => { if (mockEventSource.onopen) mockEventSource.onopen({} as Event); }, 0);
      return mockEventSource;
    } as any;

    const atmosphere = new Atmosphere({ fallbackTransport: 'sse' });
    const sub = await atmosphere.subscribe({
      url: 'http://localhost/test',
      transport: 'websocket',
    });

    expect(sub.state).toBe('connected');
    await sub.close();
  });
});
