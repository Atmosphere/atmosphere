import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';
import { WebSocketTransport } from '../../src/transports/websocket';
import type { AtmosphereRequest, AtmosphereInterceptor } from '../../src/types';

describe('Missing Features', () => {
  let mockWs: any;
  let mockEventSource: any;

  beforeEach(() => {
    mockWs = {
      send: vi.fn(),
      close: vi.fn(),
      readyState: WebSocket.OPEN,
      onopen: null as any,
      onmessage: null as any,
      onerror: null as any,
      onclose: null as any,
      binaryType: 'arraybuffer',
    };

    mockEventSource = {
      close: vi.fn(),
      onopen: null as any,
      onmessage: null as any,
      onerror: null as any,
    };
  });

  describe('onTransportFailure', () => {
    it('should notify handler when primary transport fails and fallback is used', async () => {
      global.WebSocket = function () {
        setTimeout(() => { mockWs.onerror?.({ type: 'error' }); }, 0);
        return mockWs;
      } as any;

      global.EventSource = function () {
        setTimeout(() => { mockEventSource.onopen?.({} as Event); }, 0);
        return mockEventSource;
      } as any;

      const transportFailure = vi.fn();

      const atmosphere = new Atmosphere();
      await atmosphere.subscribe(
        {
          url: 'http://localhost/test',
          transport: 'websocket',
          fallbackTransport: 'sse',
        },
        { transportFailure },
      );

      expect(transportFailure).toHaveBeenCalledTimes(1);
      expect(transportFailure).toHaveBeenCalledWith(
        expect.stringContaining('WebSocket connection error'),
        expect.objectContaining({ transport: 'websocket' }),
      );
    });

    it('should NOT notify when primary transport succeeds', async () => {
      global.WebSocket = function () {
        setTimeout(() => { mockWs.onopen?.({ type: 'open' }); }, 0);
        return mockWs;
      } as any;

      const transportFailure = vi.fn();

      const atmosphere = new Atmosphere();
      await atmosphere.subscribe(
        { url: 'http://localhost/test', transport: 'websocket', fallbackTransport: 'sse' },
        { transportFailure },
      );

      expect(transportFailure).not.toHaveBeenCalled();
    });
  });

  describe('onReopen', () => {
    it('should call reopen on second open, not first', async () => {
      vi.useFakeTimers();
      const request: AtmosphereRequest = {
        url: 'ws://localhost/test',
        transport: 'websocket',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 100,
      };
      const open = vi.fn();
      const reopen = vi.fn();

      global.WebSocket = function () { return mockWs; } as any;

      const transport = new WebSocketTransport(request, { open, reopen });

      // First open
      const p1 = transport.connect();
      mockWs.onopen?.({ type: 'open' });
      await p1;

      expect(open).toHaveBeenCalledTimes(1);
      expect(reopen).not.toHaveBeenCalled();

      // Simulate close → triggers reconnect scheduling
      mockWs.onclose?.({ code: 1006, reason: '' });

      // Create new WS mock for reconnect
      const mockWs2: any = {
        send: vi.fn(), close: vi.fn(), readyState: WebSocket.OPEN,
        onopen: null, onmessage: null, onerror: null, onclose: null, binaryType: 'arraybuffer',
      };
      global.WebSocket = function () { return mockWs2; } as any;

      // Advance timers past reconnect delay
      await vi.advanceTimersByTimeAsync(2000);

      // Trigger second open
      mockWs2.onopen?.({ type: 'open' });

      expect(open).toHaveBeenCalledTimes(1); // Still only 1
      expect(reopen).toHaveBeenCalledTimes(1); // Called on reconnect

      vi.useRealTimers();
    });
  });

  describe('connectTimeout', () => {
    it('should reject if connect takes longer than connectTimeout', async () => {
      // WebSocket that never opens
      global.WebSocket = function () {
        return {
          send: vi.fn(), close: vi.fn(), readyState: 0,
          onopen: null, onmessage: null, onerror: null, onclose: null, binaryType: 'arraybuffer',
        };
      } as any;

      const atmosphere = new Atmosphere();

      await expect(
        atmosphere.subscribe({ url: 'http://localhost/test', transport: 'websocket', connectTimeout: 50 }),
      ).rejects.toThrow('Connect timeout after 50ms');
    });
  });

  describe('inactivity timeout', () => {
    it('should fire clientTimeout when no messages received within timeout', async () => {
      const clientTimeout = vi.fn();
      const request: AtmosphereRequest = {
        url: 'ws://localhost/test',
        transport: 'websocket',
        timeout: 5000,
      };

      global.WebSocket = function () { return mockWs; } as any;
      const transport = new WebSocketTransport(request, { clientTimeout });

      const p = transport.connect();
      mockWs.onopen?.({ type: 'open' });
      await p;

      // Now install fake timers after connection is established
      vi.useFakeTimers();
      // The inactivity timer was started with real setTimeout, so advance won't help.
      // We need to trigger it: manually fire clientTimeout via the real timer.
      vi.useRealTimers();

      // Use a different approach: directly verify with real timers + short timeout
      const request2: AtmosphereRequest = {
        url: 'ws://localhost/test',
        transport: 'websocket',
        timeout: 50, // 50ms for test speed
      };
      const clientTimeout2 = vi.fn();
      const transport2 = new WebSocketTransport(request2, { clientTimeout: clientTimeout2 });

      const p2 = transport2.connect();
      mockWs.onopen?.({ type: 'open' });
      await p2;

      // Wait for 80ms — should have fired
      await new Promise((r) => setTimeout(r, 80));
      expect(clientTimeout2).toHaveBeenCalledWith(request2);
    });

    it('should reset timeout on each message', async () => {
      const clientTimeout = vi.fn();
      const request: AtmosphereRequest = {
        url: 'ws://localhost/test',
        transport: 'websocket',
        timeout: 100,
      };

      global.WebSocket = function () { return mockWs; } as any;
      const transport = new WebSocketTransport(request, { clientTimeout });

      const p = transport.connect();
      mockWs.onopen?.({ type: 'open' });
      await p;

      // At 60ms send a message — resets the 100ms timer
      await new Promise((r) => setTimeout(r, 60));
      mockWs.onmessage?.({ data: 'hello' });
      expect(clientTimeout).not.toHaveBeenCalled();

      // At 60ms after message, still within 100ms window
      await new Promise((r) => setTimeout(r, 60));
      expect(clientTimeout).not.toHaveBeenCalled();

      // At 110ms after last message, should fire
      await new Promise((r) => setTimeout(r, 50));
      expect(clientTimeout).toHaveBeenCalledTimes(1);
    });
  });

  describe('suspend / resume', () => {
    it('should suppress messages while suspended', async () => {
      const message = vi.fn();
      const request: AtmosphereRequest = { url: 'ws://localhost/test', transport: 'websocket' };

      global.WebSocket = function () { return mockWs; } as any;

      const atmosphere = new Atmosphere();
      const sub = await new Promise<any>((resolve) => {
        const p = atmosphere.subscribe(request, { message, open: () => {} });
        mockWs.onopen?.({ type: 'open' });
        p.then(resolve);
      });

      // Receive message — should work
      mockWs.onmessage?.({ data: 'msg1' });
      expect(message).toHaveBeenCalledTimes(1);

      // Suspend
      sub.suspend();
      expect(sub.state).toBe('suspended');

      // Message while suspended — should NOT reach handler
      mockWs.onmessage?.({ data: 'msg2' });
      expect(message).toHaveBeenCalledTimes(1); // Still 1

      // Resume
      await sub.resume();
      expect(sub.state).toBe('connected');

      // Message after resume — should work
      mockWs.onmessage?.({ data: 'msg3' });
      expect(message).toHaveBeenCalledTimes(2);
    });
  });

  describe('interceptors', () => {
    it('should transform outgoing messages', async () => {
      const interceptor: AtmosphereInterceptor = {
        name: 'uppercase',
        onOutgoing: (data) => (typeof data === 'string' ? data.toUpperCase() : data),
      };

      global.WebSocket = function () { return mockWs; } as any;

      const atmosphere = new Atmosphere({ interceptors: [interceptor] });
      const sub = await new Promise<any>((resolve) => {
        const p = atmosphere.subscribe(
          { url: 'http://localhost/test', transport: 'websocket' },
        );
        mockWs.onopen?.({ type: 'open' });
        p.then(resolve);
      });

      sub.push('hello');

      expect(mockWs.send).toHaveBeenCalledWith('HELLO');
    });

    it('should transform incoming messages', async () => {
      const interceptor: AtmosphereInterceptor = {
        name: 'prefix',
        onIncoming: (body) => `[PREFIX] ${body}`,
      };

      const message = vi.fn();
      global.WebSocket = function () { return mockWs; } as any;

      const atmosphere = new Atmosphere({ interceptors: [interceptor] });
      await new Promise<void>((resolve) => {
        atmosphere.subscribe(
          { url: 'http://localhost/test', transport: 'websocket' },
          { message },
        ).then(() => resolve());
        mockWs.onopen?.({ type: 'open' });
      });

      mockWs.onmessage?.({ data: 'world' });

      expect(message).toHaveBeenCalledWith(
        expect.objectContaining({ responseBody: '[PREFIX] world' }),
      );
    });

    it('should chain multiple interceptors in correct order', async () => {
      const interceptors: AtmosphereInterceptor[] = [
        { onOutgoing: (d) => (typeof d === 'string' ? `[A:${d}]` : d) },
        { onOutgoing: (d) => (typeof d === 'string' ? `[B:${d}]` : d) },
      ];

      global.WebSocket = function () { return mockWs; } as any;

      const atmosphere = new Atmosphere({ interceptors });
      const sub = await new Promise<any>((resolve) => {
        const p = atmosphere.subscribe(
          { url: 'http://localhost/test', transport: 'websocket' },
        );
        mockWs.onopen?.({ type: 'open' });
        p.then(resolve);
      });

      sub.push('msg');

      // Outgoing: A applied first, then B
      expect(mockWs.send).toHaveBeenCalledWith('[B:[A:msg]]');
    });
  });
});
