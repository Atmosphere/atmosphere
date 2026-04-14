import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebSocketTransport } from '../../src/transports/websocket';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('WebSocketTransport – reconnect & advanced', () => {
  let mockWebSocket: any;
  let mockHandlers: SubscriptionHandlers;

  beforeEach(() => {
    vi.useFakeTimers();

    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
      failureToReconnect: vi.fn(),
      reopen: vi.fn(),
    };

    mockWebSocket = {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      send: vi.fn(),
      close: vi.fn(),
      readyState: WebSocket.OPEN,
      onopen: null as any,
      onmessage: null as any,
      onerror: null as any,
      onclose: null as any,
      binaryType: 'arraybuffer',
    };

    global.WebSocket = vi.fn(function () { return mockWebSocket; }) as any;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function createTransport(overrides: Partial<AtmosphereRequest> = {}): WebSocketTransport {
    return new WebSocketTransport(
      {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 100,
        ...overrides,
      },
      mockHandlers,
    );
  }

  // ── Reconnect scheduling ──

  it('schedules reconnect on close when reconnect=true', async () => {
    const transport = createTransport();
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    // Simulate close
    mockWebSocket.onclose?.({ code: 1006, reason: '' });

    expect(mockHandlers.reconnect).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ state: 'reconnecting' }),
    );
  });

  it('does not reconnect when reconnect=false', async () => {
    const transport = createTransport({ reconnect: false });
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    mockWebSocket.onclose?.({ code: 1006, reason: '' });

    expect(mockHandlers.reconnect).not.toHaveBeenCalled();
  });

  it('stops reconnecting after maxReconnectOnClose consecutive failures', async () => {
    // When reconnect attempts all fail (error before open), the counter
    // is never reset. maxReconnectOnClose limits consecutive failures.
    const mocks: any[] = [];

    global.WebSocket = vi.fn(function () {
      const ws = {
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        send: vi.fn(),
        close: vi.fn(),
        readyState: WebSocket.OPEN,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      mocks.push(ws);
      return ws;
    }) as any;

    const transport = createTransport({ maxReconnectOnClose: 2 });

    // Initial connect succeeds
    const connectPromise = transport.connect();
    mocks[0].onopen?.();
    await connectPromise;

    // Close → schedules reconnect (attempt 0 < 2), counter becomes 1
    mocks[0].onclose?.({ code: 1006, reason: '' });
    expect(mockHandlers.reconnect).toHaveBeenCalledTimes(1);

    // Timer fires → reconnect → new WS → immediately close again (no onopen)
    await vi.advanceTimersByTimeAsync(5000);
    // The new WS (mocks[1]) also closes before opening
    mocks[1]?.onclose?.({ code: 1006, reason: '' });
    // attempt 1 < 2, schedules another reconnect, counter becomes 2
    expect(mockHandlers.reconnect).toHaveBeenCalledTimes(2);

    // Timer fires → reconnect → new WS → close again
    await vi.advanceTimersByTimeAsync(10000);
    mocks[2]?.onclose?.({ code: 1006, reason: '' });
    // attempt 2 >= 2 → failureToReconnect
    expect(mockHandlers.failureToReconnect).toHaveBeenCalledTimes(1);
  });

  // ── Close event ──

  it('notifies close handler with code and reason', async () => {
    const transport = createTransport({ reconnect: false });
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    mockWebSocket.onclose?.({ code: 1000, reason: 'Normal closure' });

    expect(mockHandlers.close).toHaveBeenCalledWith(
      expect.objectContaining({
        status: 1000,
        reasonPhrase: 'Normal closure',
        state: 'closed',
      }),
    );
  });

  it('skips close handling when request.closed is true', async () => {
    const transport = createTransport({ reconnect: false, closed: true });
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    mockWebSocket.onclose?.({ code: 1000, reason: 'bye' });

    expect(mockHandlers.close).not.toHaveBeenCalled();
  });

  // ── HTTPS → WSS ──

  it('transforms HTTPS to WSS', async () => {
    const transport = createTransport({ url: 'https://example.com/chat' });
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    expect(global.WebSocket).toHaveBeenCalledWith(
      expect.stringContaining('wss://example.com/chat'),
    );
  });

  // ── Binary message handling ──

  it('passes binary messages directly to handler', async () => {
    const transport = createTransport();
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    const buffer = new ArrayBuffer(8);
    mockWebSocket.onmessage?.({ data: buffer });

    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({
        responseBody: buffer,
        state: 'messageReceived',
      }),
    );
  });

  // ── Disconnect clears reconnect timer ──

  it('disconnect clears pending reconnect timer', async () => {
    const transport = createTransport();
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    // Trigger reconnect
    mockWebSocket.onclose?.({ code: 1006, reason: '' });
    expect(mockHandlers.reconnect).toHaveBeenCalled();

    // Disconnect before timer fires
    await transport.disconnect();
    expect(transport.state).toBe('disconnected');
  });

  // ── Suspend/Resume ──

  it('suspend blocks message delivery', async () => {
    const transport = createTransport();
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    transport.suspend();
    expect(transport.state).toBe('suspended');

    mockWebSocket.onmessage?.({ data: 'should-be-ignored' });
    expect(mockHandlers.message).not.toHaveBeenCalled();
  });

  it('resume re-enables message delivery', async () => {
    const transport = createTransport();
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    transport.suspend();
    await transport.resume();

    mockWebSocket.onmessage?.({ data: 'after-resume' });
    expect(mockHandlers.message).toHaveBeenCalled();
  });

  // ── Interceptors ──

  it('applies outgoing interceptor to sent messages', async () => {
    const interceptor = {
      onOutgoing: (data: string | ArrayBuffer) =>
        typeof data === 'string' ? `[wrapped]${data}` : data,
    };
    const transport = new WebSocketTransport(
      { url: 'ws://localhost:8080/chat', transport: 'websocket' },
      mockHandlers,
      [interceptor],
    );
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    transport.send('hello');
    expect(mockWebSocket.send).toHaveBeenCalledWith('[wrapped]hello');
  });

  it('applies incoming interceptor to received messages', async () => {
    const interceptor = {
      onIncoming: (body: string) => body.toUpperCase(),
    };
    const transport = new WebSocketTransport(
      { url: 'ws://localhost:8080/chat', transport: 'websocket' },
      mockHandlers,
      [interceptor],
    );
    const connectPromise = transport.connect();
    mockWebSocket.onopen?.();
    await connectPromise;

    mockWebSocket.onmessage?.({ data: 'hello' });
    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'HELLO' }),
    );
  });
});
