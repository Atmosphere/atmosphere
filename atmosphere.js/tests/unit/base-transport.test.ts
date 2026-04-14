import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { BaseTransport } from '../../src/transports/base';
import type { AtmosphereRequest, SubscriptionHandlers, AtmosphereResponse } from '../../src/types';
import { OfflineQueue } from '../../src/queue/offline-queue';

/** Concrete subclass of BaseTransport for testing protected methods. */
class TestTransport extends BaseTransport<string> {
  connectFn: (() => Promise<void>) | null = null;
  disconnectFn: (() => Promise<void>) | null = null;

  get name() { return 'test'; }

  async connect(): Promise<void> {
    if (this.connectFn) return this.connectFn();
    this._state = 'connected';
  }

  async disconnect(): Promise<void> {
    if (this.disconnectFn) return this.disconnectFn();
    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    this.applyOutgoing(message);
  }

  // Expose protected methods for testing
  doNotifyOpen(response: AtmosphereResponse<string>): void {
    this.notifyOpen(response);
  }

  doNotifyMessage(response: AtmosphereResponse<string>): void {
    this.notifyMessage(response);
  }

  doNotifyClose(response: AtmosphereResponse<string>): void {
    this.notifyClose(response);
  }

  doNotifyError(error: Error): void {
    this.notifyError(error);
  }

  doApplyOutgoing(data: string | ArrayBuffer) {
    return this.applyOutgoing(data);
  }

  doApplyIncoming(body: string) {
    return this.applyIncoming(body);
  }

  doIsMaxRequestReached() {
    return this.isMaxRequestReached();
  }

  incrementRequestCount() {
    this._requestCount++;
  }
}

function makeResponse(overrides: Partial<AtmosphereResponse<string>> = {}): AtmosphereResponse<string> {
  return {
    status: 200,
    reasonPhrase: 'OK',
    responseBody: '',
    messages: [],
    headers: {},
    state: 'open',
    transport: 'test',
    error: null,
    request: { url: 'ws://localhost/test', transport: 'websocket' },
    ...overrides,
  };
}

describe('BaseTransport', () => {
  let handlers: SubscriptionHandlers<string>;
  let transport: TestTransport;

  beforeEach(() => {
    vi.useFakeTimers();
    handlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
      reopen: vi.fn(),
      failureToReconnect: vi.fn(),
      clientTimeout: vi.fn(),
    };
    const request: AtmosphereRequest = {
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
    };
    transport = new TestTransport(request, handlers);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // ── state ──

  it('initial state is disconnected', () => {
    expect(transport.state).toBe('disconnected');
  });

  it('hasOpened is false initially', () => {
    expect(transport.hasOpened).toBe(false);
  });

  // ── notifyOpen ──

  it('first open calls handlers.open and sets hasOpened', () => {
    transport.doNotifyOpen(makeResponse());
    expect(handlers.open).toHaveBeenCalledTimes(1);
    expect(transport.hasOpened).toBe(true);
    expect(transport.state).toBe('connected');
  });

  it('second open calls handlers.reopen', () => {
    transport.doNotifyOpen(makeResponse());
    transport.doNotifyOpen(makeResponse());
    expect(handlers.open).toHaveBeenCalledTimes(1);
    expect(handlers.reopen).toHaveBeenCalledTimes(1);
  });

  // ── notifyMessage ──

  it('delivers messages normally', () => {
    transport.doNotifyOpen(makeResponse());
    transport.doNotifyMessage(makeResponse({ responseBody: 'hello' }));
    expect(handlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'hello' }),
    );
  });

  it('suppresses messages in suspended state', () => {
    transport.doNotifyOpen(makeResponse());
    transport.suspend();
    transport.doNotifyMessage(makeResponse({ responseBody: 'ignored' }));
    expect(handlers.message).not.toHaveBeenCalled();
  });

  // ── notifyClose ──

  it('sets state to closed', () => {
    transport.doNotifyOpen(makeResponse());
    transport.doNotifyClose(makeResponse());
    expect(transport.state).toBe('closed');
    expect(handlers.close).toHaveBeenCalled();
  });

  // ── notifyError ──

  it('sets state to error', () => {
    transport.doNotifyError(new Error('boom'));
    expect(transport.state).toBe('error');
    expect(handlers.error).toHaveBeenCalledWith(expect.any(Error));
  });

  // ── suspend / resume ──

  it('suspend only works from connected state', () => {
    // Not connected yet → suspend should not change state
    transport.suspend();
    expect(transport.state).toBe('disconnected');
  });

  it('resume only works from suspended state', async () => {
    // Not suspended → resume is a no-op
    await transport.resume();
    expect(transport.state).toBe('disconnected');
  });

  // ── connectWithTimeout ──

  it('connects normally without timeout', async () => {
    await transport.connectWithTimeout();
    expect(transport.state).toBe('connected');
  });

  it('rejects on timeout', async () => {
    transport.connectFn = () => new Promise(() => { /* never resolves */ });
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
      connectTimeout: 500,
    };
    const t = new TestTransport(request, handlers);
    t.connectFn = () => new Promise(() => { /* hangs */ });

    const connectPromise = t.connectWithTimeout();
    vi.advanceTimersByTime(600);
    await expect(connectPromise).rejects.toThrow('Connect timeout after 500ms');
  });

  // ── interceptors ──

  it('applies outgoing interceptors in order', () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
    };
    const interceptors = [
      { onOutgoing: (data: string | ArrayBuffer) => `[1]${data}` },
      { onOutgoing: (data: string | ArrayBuffer) => `[2]${data}` },
    ];
    const t = new TestTransport(request, handlers, interceptors);
    expect(t.doApplyOutgoing('msg')).toBe('[2][1]msg');
  });

  it('applies incoming interceptors in reverse order', () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
    };
    const interceptors = [
      { onIncoming: (body: string) => `[A]${body}` },
      { onIncoming: (body: string) => `[B]${body}` },
    ];
    const t = new TestTransport(request, handlers, interceptors);
    expect(t.doApplyIncoming('msg')).toBe('[A][B]msg');
  });

  // ── maxRequest ──

  it('isMaxRequestReached returns false when no limit', () => {
    expect(transport.doIsMaxRequestReached()).toBe(false);
  });

  it('isMaxRequestReached returns true when limit reached', () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
      maxRequest: 2,
    };
    const t = new TestTransport(request, handlers);
    t.incrementRequestCount();
    expect(t.doIsMaxRequestReached()).toBe(false);
    t.incrementRequestCount();
    expect(t.doIsMaxRequestReached()).toBe(true);
  });

  // ── inactivity timeout ──

  it('fires clientTimeout after inactivity', () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
      timeout: 1000,
    };
    const t = new TestTransport(request, handlers);
    t.doNotifyOpen(makeResponse());

    vi.advanceTimersByTime(1100);
    expect(handlers.clientTimeout).toHaveBeenCalled();
  });

  it('resets inactivity timer on message', () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost/test',
      transport: 'websocket',
      timeout: 1000,
    };
    const t = new TestTransport(request, handlers);
    t.doNotifyOpen(makeResponse());

    vi.advanceTimersByTime(800);
    t.doNotifyMessage(makeResponse()); // resets timer
    vi.advanceTimersByTime(800);
    expect(handlers.clientTimeout).not.toHaveBeenCalled();

    vi.advanceTimersByTime(300);
    expect(handlers.clientTimeout).toHaveBeenCalled();
  });

  // ── offline queue draining ──

  it('drains offline queue on reopen', () => {
    const queue = new OfflineQueue({ maxSize: 10, drainOnReconnect: true });
    queue.enqueue('msg1');
    queue.enqueue('msg2');

    transport.setOfflineQueue(queue);
    transport.doNotifyOpen(makeResponse()); // first open
    transport.doNotifyOpen(makeResponse()); // reopen → drains queue

    expect(queue.size).toBe(0);
    expect(handlers.reopen).toHaveBeenCalled();
  });
});
