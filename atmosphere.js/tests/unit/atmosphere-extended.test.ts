import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';
import { VERSION } from '../../src/version';

function createMockWs() {
  return {
    send: vi.fn(),
    close: vi.fn(),
    readyState: WebSocket.OPEN,
    onopen: null as ((ev: any) => void) | null,
    onmessage: null as ((ev: any) => void) | null,
    onerror: null as ((ev: any) => void) | null,
    onclose: null as ((ev: any) => void) | null,
    binaryType: 'arraybuffer',
  };
}

function installMockWs(mockWs: ReturnType<typeof createMockWs>) {
  global.WebSocket = vi.fn(function () {
    setTimeout(() => { mockWs.onopen?.({ type: 'open' }); }, 0);
    return mockWs as any;
  }) as any;
}

describe('Atmosphere – extended', () => {
  let atmosphere: Atmosphere;
  let mockWs: ReturnType<typeof createMockWs>;

  beforeEach(() => {
    mockWs = createMockWs();
    installMockWs(mockWs);
    atmosphere = new Atmosphere();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('version matches exported constant', () => {
    expect(atmosphere.version).toBe(VERSION);
  });

  // ── subscribe with handlers ──

  it('should invoke open handler on connect', async () => {
    const openFn = vi.fn();
    await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
      { open: openFn },
    );
    expect(openFn).toHaveBeenCalledWith(
      expect.objectContaining({ state: 'open', transport: 'websocket' }),
    );
  });

  it('should invoke message handler on incoming data', async () => {
    const messageFn = vi.fn();
    await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
      { message: messageFn },
    );
    mockWs.onmessage?.({ data: 'hello world' });
    expect(messageFn).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'hello world' }),
    );
  });

  // ── on/off event emitter ──

  it('on() listeners fire alongside handlers', async () => {
    const onMessage = vi.fn();
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    sub.on('message', onMessage);
    mockWs.onmessage?.({ data: 'test' });
    expect(onMessage).toHaveBeenCalledTimes(1);
  });

  it('off() removes the listener', async () => {
    const onMessage = vi.fn();
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    sub.on('message', onMessage);
    sub.off('message', onMessage);
    mockWs.onmessage?.({ data: 'test' });
    expect(onMessage).not.toHaveBeenCalled();
  });

  // ── push ──

  it('push sends string messages', async () => {
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    sub.push('hello');
    expect(mockWs.send).toHaveBeenCalledWith('hello');
  });

  it('push JSON-serializes objects', async () => {
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    sub.push({ type: 'ping' });
    expect(mockWs.send).toHaveBeenCalledWith('{"type":"ping"}');
  });

  // ── suspend / resume ──

  it('suspend transitions to suspended state', async () => {
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    expect(sub.state).toBe('connected');
    sub.suspend();
    expect(sub.state).toBe('suspended');
  });

  it('resume transitions back to connected', async () => {
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    sub.suspend();
    await sub.resume();
    expect(sub.state).toBe('connected');
  });

  // ── close ──

  it('close removes subscription from list', async () => {
    const sub = await atmosphere.subscribe(
      { url: 'ws://localhost/test', transport: 'websocket' },
    );
    expect(atmosphere.getSubscriptions()).toHaveLength(1);
    await sub.close();
    expect(atmosphere.getSubscriptions()).toHaveLength(0);
  });

  // ── config ──

  it('respects defaultTransport from config', async () => {
    const atm = new Atmosphere({ defaultTransport: 'websocket' });
    const sub = await atm.subscribe({ url: 'ws://localhost/test' });
    expect(sub.state).toBe('connected');
  });

  // ── fallback transport ──

  it('calls transportFailure when primary fails and fallback is set', async () => {
    // We verify that when the primary websocket fails and a fallback is
    // configured, the transportFailure handler is invoked with the reason.
    // We use websocket as both primary and fallback since SSE/long-polling
    // need browser APIs. The code checks `fallback !== transport` so we
    // need different names. Instead, we test just the handler invocation
    // by subscribing with a failing WS and a "long-polling" fallback that
    // also fails — the important thing is transportFailure fires.
    let callCount = 0;
    const failingWs = createMockWs();

    global.WebSocket = vi.fn(function () {
      callCount++;
      setTimeout(() => { failingWs.onerror?.({ type: 'error' }); }, 0);
      return failingWs as any;
    }) as any;

    const transportFailure = vi.fn();

    // Both websocket (primary) and long-polling (fallback) will fail,
    // but transportFailure should be called after the first failure.
    await expect(
      atmosphere.subscribe(
        {
          url: 'http://localhost/test',
          transport: 'websocket',
          fallbackTransport: 'long-polling',
        },
        { transportFailure },
      ),
    ).rejects.toThrow(); // fallback also fails

    expect(transportFailure).toHaveBeenCalledTimes(1);
    expect(transportFailure).toHaveBeenCalledWith(
      expect.stringContaining('WebSocket connection error'),
      expect.anything(),
    );
  });

  // ── getSubscriptions ──

  it('getSubscriptions tracks multiple subscriptions', async () => {
    await atmosphere.subscribe({ url: 'ws://localhost/test1', transport: 'websocket' });
    await atmosphere.subscribe({ url: 'ws://localhost/test2', transport: 'websocket' });
    expect(atmosphere.getSubscriptions()).toHaveLength(2);
  });

  it('subscription IDs are unique', async () => {
    const s1 = await atmosphere.subscribe({ url: 'ws://localhost/a', transport: 'websocket' });
    const s2 = await atmosphere.subscribe({ url: 'ws://localhost/b', transport: 'websocket' });
    expect(s1.id).not.toBe(s2.id);
  });

  // ── unsupported transport ──

  it('throws on unsupported transport', async () => {
    await expect(
      atmosphere.subscribe({ url: 'ws://localhost/test', transport: 'unknown' as any }),
    ).rejects.toThrow('Unsupported transport');
  });
});
