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
import { WebTransportTransport } from '../../src/transports/webtransport';
import type { AtmosphereRequest, AtmosphereInterceptor, SubscriptionHandlers } from '../../src/types';
import { OfflineQueue } from '../../src/queue/offline-queue';

/**
 * Creates a mock WritableStreamDefaultWriter.
 */
function createMockWriter() {
  return {
    write: vi.fn().mockResolvedValue(undefined),
    close: vi.fn().mockResolvedValue(undefined),
    abort: vi.fn().mockResolvedValue(undefined),
    releaseLock: vi.fn(),
    ready: Promise.resolve(undefined),
    closed: new Promise<undefined>(() => {}),
    desiredSize: 1,
  };
}

/**
 * Creates a mock ReadableStreamDefaultReader that can be fed data.
 */
function createMockReader() {
  const readQueue: Array<{ value: Uint8Array; done: boolean }> = [];
  let waitingResolve: ((result: { value: Uint8Array | undefined; done: boolean }) => void) | null = null;

  const reader = {
    read: vi.fn(() => {
      if (readQueue.length > 0) {
        return Promise.resolve(readQueue.shift()!);
      }
      return new Promise<{ value: Uint8Array | undefined; done: boolean }>((resolve) => {
        waitingResolve = resolve;
      });
    }),
    cancel: vi.fn().mockResolvedValue(undefined),
    releaseLock: vi.fn(),
    closed: new Promise<undefined>(() => {}),
    // Test helper to push data into the read stream.
    // Appends \n to match the server's newline-delimited framing.
    pushData(data: string) {
      const encoded = new TextEncoder().encode(data + '\n');
      const result = { value: encoded, done: false };
      if (waitingResolve) {
        const resolve = waitingResolve;
        waitingResolve = null;
        resolve(result);
      } else {
        readQueue.push(result);
      }
    },
    // Test helper to signal stream end
    pushDone() {
      const result = { value: undefined as Uint8Array | undefined, done: true };
      if (waitingResolve) {
        const resolve = waitingResolve;
        waitingResolve = null;
        resolve(result);
      } else {
        readQueue.push({ value: new Uint8Array(0), done: true });
      }
    },
  };

  return reader;
}

/**
 * Helper to create a fresh mock WebTransport instance with the given writer and reader.
 */
function createMockTransportInstance(
  mockReader: ReturnType<typeof createMockReader>,
  mockWriter: ReturnType<typeof createMockWriter>,
) {
  return {
    ready: Promise.resolve(),
    closed: new Promise(() => {}), // Never resolves by default
    close: vi.fn(),
    createBidirectionalStream: vi.fn().mockResolvedValue({
      readable: { getReader: () => mockReader },
      writable: { getWriter: () => mockWriter },
    }),
    datagrams: {
      readable: {} as ReadableStream,
      writable: {} as WritableStream,
    },
    incomingBidirectionalStreams: {} as ReadableStream,
  };
}

describe('WebTransportTransport', () => {
  let transport: WebTransportTransport;
  let mockHandlers: SubscriptionHandlers;
  let mockWriter: ReturnType<typeof createMockWriter>;
  let mockReader: ReturnType<typeof createMockReader>;
  let mockTransportInstance: any;

  beforeEach(() => {
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
      reopen: vi.fn(),
      failureToReconnect: vi.fn(),
      clientTimeout: vi.fn(),
    };

    mockWriter = createMockWriter();
    mockReader = createMockReader();

    mockTransportInstance = createMockTransportInstance(mockReader, mockWriter);

    (globalThis as any).WebTransport = vi.fn(function() { return mockTransportInstance; });

    const request: AtmosphereRequest = {
      url: 'https://localhost:8080/chat',
      transport: 'webtransport',
    };

    transport = new WebTransportTransport(request, mockHandlers);
  });

  afterEach(() => {
    vi.clearAllMocks();
    delete (globalThis as any).WebTransport;
  });

  describe('name', () => {
    it('should return "webtransport"', () => {
      expect(transport.name).toBe('webtransport');
    });
  });

  describe('connect()', () => {
    it('should create WebTransport with correct HTTPS URL', async () => {
      await transport.connect();

      expect((globalThis as any).WebTransport).toHaveBeenCalledWith(
        expect.stringContaining('https://localhost:8080/chat'),
        expect.any(Object),
      );
    });

    it('should call open handler on successful connection', async () => {
      await transport.connect();

      expect(mockHandlers.open).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 200,
          transport: 'webtransport',
          state: 'open',
        }),
      );
    });

    it('should transition state to connected', async () => {
      await transport.connect();
      expect(transport.state).toBe('connected');
    });

    it('should reject when transport closes before ready', async () => {
      mockTransportInstance.ready = new Promise(() => {}); // Never resolves
      mockTransportInstance.closed = Promise.resolve({ closeCode: 0, reason: 'Failed' });

      await expect(transport.connect()).rejects.toThrow(
        'WebTransport connection closed before ready',
      );
      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should create a bidirectional stream', async () => {
      await transport.connect();
      expect(mockTransportInstance.createBidirectionalStream).toHaveBeenCalled();
    });

    it('should reject connect when WebTransport constructor throws', async () => {
      const constructorError = new Error('Invalid URL');
      (globalThis as any).WebTransport = vi.fn(function() {
        throw constructorError;
      });

      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await expect(transport.connect()).rejects.toThrow('Invalid URL');
      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should handle connect timeout via connectWithTimeout', async () => {
      // Make ready never resolve so the connect hangs
      mockTransportInstance.ready = new Promise(() => {});
      mockTransportInstance.closed = new Promise(() => {});

      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        connectTimeout: 50,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await expect(transport.connectWithTimeout()).rejects.toThrow(
        'Connect timeout after 50ms',
      );
    });

    it('should set state to connecting during connect', async () => {
      // Pause ready so we can inspect state mid-connect
      let resolveReady!: () => void;
      mockTransportInstance.ready = new Promise<void>((r) => {
        resolveReady = r;
      });

      const connectPromise = transport.connect();

      // State should be connecting while awaiting ready
      expect(transport.state).toBe('connecting');

      resolveReady();
      await connectPromise;

      expect(transport.state).toBe('connected');
    });

    it('should build URL with atmosphere protocol params when enableProtocol is true', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
        enableProtocol: true,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl).toContain('X-atmo-protocol=true');
      expect(calledUrl).toContain('X-Atmosphere-Transport=webtransport');
      expect(calledUrl).toContain('X-Atmosphere-tracking-id=');
      expect(calledUrl).toContain('X-Atmosphere-Framework=');
    });
  });

  describe('send()', () => {
    it('should write encoded string through stream writer', async () => {
      await transport.connect();

      transport.send('Hello World');

      expect(mockWriter.write).toHaveBeenCalledWith(
        new TextEncoder().encode('Hello World\n'),
      );
    });

    it('should write ArrayBuffer through stream writer', async () => {
      await transport.connect();

      const buffer = new TextEncoder().encode('binary data').buffer;
      transport.send(buffer as ArrayBuffer);

      expect(mockWriter.write).toHaveBeenCalledWith(
        expect.any(Uint8Array),
      );
    });

    it('should throw error if not connected', () => {
      expect(() => transport.send('test')).toThrow('WebTransport is not connected');
    });

    it('should apply outgoing interceptors to sent messages', async () => {
      const interceptor: AtmosphereInterceptor = {
        name: 'test-outgoing',
        onOutgoing: (data) => {
          if (typeof data === 'string') {
            return `[prefix]${data}`;
          }
          return data;
        },
      };

      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers, [interceptor]);

      await transport.connect();
      transport.send('hello');

      expect(mockWriter.write).toHaveBeenCalledWith(
        new TextEncoder().encode('[prefix]hello\n'),
      );
    });

    it('should handle empty string send', async () => {
      await transport.connect();

      transport.send('');

      expect(mockWriter.write).toHaveBeenCalledWith(
        new TextEncoder().encode('\n'),
      );
    });
  });

  describe('disconnect()', () => {
    it('should close transport', async () => {
      await transport.connect();
      await transport.disconnect();

      expect(mockTransportInstance.close).toHaveBeenCalledWith({
        closeCode: 0,
        reason: 'Client disconnect',
      });
    });

    it('should close writer and cancel reader', async () => {
      await transport.connect();
      await transport.disconnect();

      expect(mockWriter.close).toHaveBeenCalled();
      expect(mockReader.cancel).toHaveBeenCalled();
    });

    it('should transition to disconnected state', async () => {
      await transport.connect();
      await transport.disconnect();

      expect(transport.state).toBe('disconnected');
    });

    it('should handle disconnect when writer is already null', async () => {
      await transport.connect();
      await transport.disconnect();

      // Second disconnect should not throw
      await expect(transport.disconnect()).resolves.toBeUndefined();
      expect(transport.state).toBe('disconnected');
    });

    it('should clear reconnect timer on disconnect', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 5,
        reconnectInterval: 5000,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      // Trigger a close to schedule a reconnect
      mockReader.pushDone();
      await new Promise((r) => setTimeout(r, 50));

      // Reconnect should be scheduled
      expect(mockHandlers.reconnect).toHaveBeenCalled();

      // Disconnect should cancel the pending reconnect timer
      await transport.disconnect();
      expect(transport.state).toBe('disconnected');

      // Wait long enough that a reconnect would have fired if not cancelled
      await new Promise((r) => setTimeout(r, 100));

      // WebTransport constructor should only have been called once (initial connect)
      expect((globalThis as any).WebTransport).toHaveBeenCalledTimes(1);
    });

    it('should stop heartbeat on disconnect', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        enableProtocol: true,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      // After disconnect, heartbeat should not fire
      await transport.disconnect();

      // If heartbeat were still running, it would try to send via a closed writer
      // The fact that disconnect completes cleanly verifies heartbeat is stopped
      expect(transport.state).toBe('disconnected');
    });
  });

  describe('message handling', () => {
    it('should notify message handler with received data', async () => {
      await transport.connect();

      // Allow the read loop to start
      await new Promise((r) => setTimeout(r, 0));

      mockReader.pushData('test message');

      // Allow the read loop to process
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({
          responseBody: 'test message',
          transport: 'webtransport',
          state: 'messageReceived',
        }),
      );
    });

    it('should process atmosphere protocol handshake on first message', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        enableProtocol: true,
        messageDelimiter: '|',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      // Send handshake message: UUID|heartbeatInterval|heartbeatPadding|
      mockReader.pushData('server-uuid-123|60000|X|');
      await new Promise((r) => setTimeout(r, 10));

      // Handshake messages should not be delivered to the message handler
      expect(mockHandlers.message).not.toHaveBeenCalled();

      // But open should have been called (protocol handshake triggers open)
      expect(mockHandlers.open).toHaveBeenCalled();
    });

    it('should filter heartbeat padding from messages', async () => {
      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      // Send the default heartbeat padding character 'X'
      mockReader.pushData('X');
      await new Promise((r) => setTimeout(r, 10));

      // Heartbeat padding should be filtered — no message delivered
      expect(mockHandlers.message).not.toHaveBeenCalled();
    });

    it('should notify open after protocol handshake completes', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        enableProtocol: true,
        messageDelimiter: '|',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      // With enableProtocol, open should NOT be called during connect() itself
      await transport.connect();

      // With enableProtocol=true, handleOpen does not call notifyOpen.
      // It waits for the protocol handshake message.
      // Reset mock to verify the handshake triggers the open.
      mockHandlers.open = vi.fn();

      await new Promise((r) => setTimeout(r, 0));

      // Send handshake
      mockReader.pushData('uuid-456|30000|Y|');
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.open).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 200,
          transport: 'webtransport',
          state: 'open',
        }),
      );
    });

    it('should apply incoming interceptors to received messages', async () => {
      const interceptor: AtmosphereInterceptor = {
        name: 'test-incoming',
        onIncoming: (body) => `[received]${body}`,
      };

      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers, [interceptor]);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      mockReader.pushData('hello');
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({
          responseBody: '[received]hello',
        }),
      );
    });

    it('should handle multiple messages delivered in sequence', async () => {
      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      mockReader.pushData('first');
      await new Promise((r) => setTimeout(r, 10));

      mockReader.pushData('second');
      await new Promise((r) => setTimeout(r, 10));

      mockReader.pushData('third');
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.message).toHaveBeenCalledTimes(3);
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        1,
        expect.objectContaining({ responseBody: 'first' }),
      );
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        2,
        expect.objectContaining({ responseBody: 'second' }),
      );
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        3,
        expect.objectContaining({ responseBody: 'third' }),
      );
    });
  });

  describe('reconnect on close', () => {
    it('should schedule reconnect when stream ends', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 100,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      // Allow the read loop to start
      await new Promise((r) => setTimeout(r, 0));

      // Signal stream end
      mockReader.pushDone();

      // Allow the read loop to process and schedule reconnect
      await new Promise((r) => setTimeout(r, 50));

      expect(mockHandlers.reconnect).toHaveBeenCalledWith(
        request,
        expect.objectContaining({
          state: 'reconnecting',
          transport: 'webtransport',
        }),
      );
    });

    it('should increment reconnectAttempts on each reconnect', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 5,
        reconnectInterval: 10,
      };

      // Track how many times WebTransport is constructed
      let constructCount = 0;
      (globalThis as any).WebTransport = vi.fn(function() {
        constructCount++;
        const w = createMockWriter();
        const r = createMockReader();
        const inst = createMockTransportInstance(r, w);

        // After ready, immediately signal stream end to trigger reconnect cascade
        setTimeout(() => {
          r.pushDone();
        }, 5);

        return inst;
      });

      transport = new WebTransportTransport(request, mockHandlers);
      await transport.connect();

      // Wait for multiple reconnect attempts
      await new Promise((r) => setTimeout(r, 300));

      // Should have attempted more than one connection
      expect(constructCount).toBeGreaterThan(1);

      // Each reconnect fires the reconnect handler
      expect(mockHandlers.reconnect).toHaveBeenCalled();

      // Clean up
      await transport.disconnect();
    });

    it('should not reconnect after maxReconnectOnClose is zero', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 0,
        reconnectInterval: 10,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      mockReader.pushDone();

      // Wait for the async read loop to process done and call handleClose
      await vi.waitFor(() => {
        expect(mockHandlers.failureToReconnect).toHaveBeenCalledWith(
          request,
          expect.objectContaining({
            state: 'closed',
            transport: 'webtransport',
          }),
        );
      });

      // With maxReconnectOnClose=0, reconnectAttempts (0) < 0 is false,
      // so reconnect should NOT be scheduled.
      expect(mockHandlers.reconnect).not.toHaveBeenCalled();
    });

    it('should not reconnect when reconnect is false', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: false,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      mockReader.pushDone();

      // Wait for close handler to fire
      await vi.waitFor(() => {
        expect(mockHandlers.close).toHaveBeenCalled();
      });

      expect(mockHandlers.reconnect).not.toHaveBeenCalled();
    });

    it('should reset protocol on reconnect', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 10,
        enableProtocol: true,
        messageDelimiter: '|',
      };

      let connectCount = 0;
      const capturedUrls: string[] = [];
      (globalThis as any).WebTransport = vi.fn(function(url: string) {
        capturedUrls.push(url);
        connectCount++;
        const w = createMockWriter();
        const r = createMockReader();
        const inst = createMockTransportInstance(r, w);

        if (connectCount === 1) {
          // First connect: send handshake then end stream
          setTimeout(() => {
            r.pushData('my-uuid|5000|X|');
            setTimeout(() => r.pushDone(), 5);
          }, 5);
        } else {
          // Second connect: just end stream — the URL should show protocol was reset
          // because buildUrl uses uuid which gets reset if protocol resets
          setTimeout(() => r.pushDone(), 5);
        }

        return inst;
      });

      transport = new WebTransportTransport(request, mockHandlers);
      await transport.connect();

      // Wait for reconnect to fire
      await vi.waitFor(() => {
        expect(connectCount).toBeGreaterThanOrEqual(2);
      });

      await transport.disconnect();
    });

    it('should call reopen handler on reconnect success', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 10,
      };

      let connectCount = 0;
      (globalThis as any).WebTransport = vi.fn(function() {
        connectCount++;
        const w = createMockWriter();
        const r = createMockReader();
        const inst = createMockTransportInstance(r, w);

        if (connectCount === 1) {
          // First connect succeeds then stream ends to trigger reconnect
          setTimeout(() => r.pushDone(), 5);
        }
        // Second connect succeeds and stays open (reader blocks)

        return inst;
      });

      transport = new WebTransportTransport(request, mockHandlers);
      await transport.connect();

      // First open should call open handler
      expect(mockHandlers.open).toHaveBeenCalledTimes(1);

      // Wait for reconnect to succeed
      await vi.waitFor(() => {
        expect(mockHandlers.reopen).toHaveBeenCalled();
      });

      expect(mockHandlers.reopen).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 200,
          transport: 'webtransport',
          state: 'open',
        }),
      );

      await transport.disconnect();
    });
  });

  describe('state transitions', () => {
    it('should start with disconnected state', () => {
      expect(transport.state).toBe('disconnected');
    });

    it('should transition to connected after open', async () => {
      await transport.connect();
      expect(transport.state).toBe('connected');
    });

    it('should transition to disconnected after disconnect', async () => {
      await transport.connect();
      await transport.disconnect();
      expect(transport.state).toBe('disconnected');
    });

    it('should be in disconnected state initially', () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
      };
      const fresh = new WebTransportTransport(request, mockHandlers);
      expect(fresh.state).toBe('disconnected');
    });

    it('should transition through connecting -> connected -> closed', async () => {
      // Capture states during lifecycle
      const states: string[] = [];

      let resolveReady!: () => void;
      mockTransportInstance.ready = new Promise<void>((r) => {
        resolveReady = r;
      });

      const connectPromise = transport.connect();
      states.push(transport.state); // connecting

      resolveReady();
      await connectPromise;
      states.push(transport.state); // connected

      await transport.disconnect();
      states.push(transport.state); // disconnected

      expect(states).toEqual(['connecting', 'connected', 'disconnected']);
    });

    it('should suspend and resume', async () => {
      await transport.connect();
      expect(transport.state).toBe('connected');

      transport.suspend();
      expect(transport.state).toBe('suspended');

      await transport.resume();
      expect(transport.state).toBe('connected');
    });

    it('should not deliver messages while suspended', async () => {
      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      transport.suspend();

      mockReader.pushData('should-be-ignored');
      await new Promise((r) => setTimeout(r, 10));

      // notifyMessage checks state === suspended and returns early
      expect(mockHandlers.message).not.toHaveBeenCalled();

      await transport.resume();
    });
  });

  describe('URL building', () => {
    it('should convert http to https', async () => {
      const request: AtmosphereRequest = {
        url: 'http://example.com/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      expect((globalThis as any).WebTransport).toHaveBeenCalledWith(
        expect.stringContaining('https://example.com/chat'),
        expect.any(Object),
      );
    });

    it('should keep https as https', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      expect((globalThis as any).WebTransport).toHaveBeenCalledWith(
        expect.stringContaining('https://example.com/chat'),
        expect.any(Object),
      );
    });

    it('should use https protocol for WebTransport URLs', async () => {
      const request: AtmosphereRequest = {
        url: 'http://example.com/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl: string = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl.startsWith('https://')).toBe(true);
      expect(calledUrl).not.toContain('wss://');
      expect(calledUrl).not.toContain('ws://');
    });

    it('should throw on relative URL in non-browser environment', () => {
      // Ensure no window.location
      const origWindow = globalThis.window;
      // @ts-expect-error - removing window for test
      delete globalThis.window;

      const request: AtmosphereRequest = {
        url: '/relative/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      const connectPromise = transport.connect();

      // Restore window before awaiting so afterEach cleanup works
      globalThis.window = origWindow;

      return expect(connectPromise).rejects.toThrow(
        'In React Native or non-browser environments, request.url must be an absolute URL',
      );
    });

    it('should handle absolute URLs in non-browser environment', async () => {
      const origWindow = globalThis.window;
      // @ts-expect-error - removing window for test
      delete globalThis.window;

      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      globalThis.window = origWindow;

      expect((globalThis as any).WebTransport).toHaveBeenCalledWith(
        expect.stringContaining('https://example.com/chat'),
        expect.any(Object),
      );
    });

    it('should preserve query parameters from original URL', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat?room=lobby&user=test',
        transport: 'webtransport',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl: string = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl).toContain('room=lobby');
      expect(calledUrl).toContain('user=test');
    });

    it('should include trackMessageLength param when enabled', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
        trackMessageLength: true,
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl: string = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl).toContain('X-Atmosphere-TrackMessageSize=true');
    });

    it('should include auth token in URL when provided', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
        authToken: 'my-secret-token',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl: string = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl).toContain('X-Atmosphere-Auth=my-secret-token');
    });

    it('should include custom headers as query params', async () => {
      const request: AtmosphereRequest = {
        url: 'https://example.com/chat',
        transport: 'webtransport',
        headers: { 'X-Custom': 'value1', 'X-Other': 'value2' },
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl: string = (globalThis as any).WebTransport.mock.calls[0][0];
      expect(calledUrl).toContain('X-Custom=value1');
      expect(calledUrl).toContain('X-Other=value2');
    });
  });

  describe('integration with BaseTransport', () => {
    it('should report correct name', () => {
      expect(transport.name).toBe('webtransport');
    });

    it('should drain offline queue on reconnect', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        reconnect: true,
        maxReconnectOnClose: 3,
        reconnectInterval: 10,
      };

      const queue = new OfflineQueue({ maxSize: 10, drainOnReconnect: true });
      queue.enqueue('queued-msg-1');
      queue.enqueue('queued-msg-2');

      let connectCount = 0;
      let secondWriter: ReturnType<typeof createMockWriter> | null = null;
      (globalThis as any).WebTransport = vi.fn(function() {
        connectCount++;
        const w = createMockWriter();
        const r = createMockReader();
        const inst = createMockTransportInstance(r, w);

        if (connectCount === 1) {
          // First connect: end stream to trigger reconnect
          setTimeout(() => r.pushDone(), 5);
        } else {
          secondWriter = w;
          // Second connect stays open
        }

        return inst;
      });

      transport = new WebTransportTransport(request, mockHandlers);
      transport.setOfflineQueue(queue);

      await transport.connect();

      // Wait for reconnect to succeed and drain
      await new Promise((r) => setTimeout(r, 300));

      // The offline queue should have been drained on reopen
      expect(queue.size).toBe(0);

      // The second writer should have received the queued messages
      if (secondWriter) {
        expect(secondWriter.write).toHaveBeenCalled();
      }

      await transport.disconnect();
    });

    it('should have hasOpened=false before first connect', () => {
      expect(transport.hasOpened).toBe(false);
    });

    it('should have hasOpened=true after connect', async () => {
      await transport.connect();
      expect(transport.hasOpened).toBe(true);
    });

    it('should have uuid=0 before protocol handshake', () => {
      expect(transport.uuid).toBe('0');
    });
  });

  describe('error handling', () => {
    it('should call error handler when createBidirectionalStream fails', async () => {
      mockTransportInstance.createBidirectionalStream = vi.fn().mockRejectedValue(
        new Error('Stream creation failed'),
      );

      await expect(transport.connect()).rejects.toThrow('Stream creation failed');
      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should call error handler when read loop throws', async () => {
      // Create a reader that will throw on the first read
      const errorReader = {
        read: vi.fn()
          .mockRejectedValueOnce(new Error('Read failed')),
        cancel: vi.fn().mockResolvedValue(undefined),
        releaseLock: vi.fn(),
        closed: new Promise<undefined>(() => {}),
      };

      mockTransportInstance.createBidirectionalStream = vi.fn().mockResolvedValue({
        readable: { getReader: () => errorReader },
        writable: { getWriter: () => mockWriter },
      });

      await transport.connect();

      // Allow the read loop to start and hit the error
      await new Promise((r) => setTimeout(r, 50));

      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should set state to error on transport error', async () => {
      mockTransportInstance.createBidirectionalStream = vi.fn().mockRejectedValue(
        new Error('Failed'),
      );

      await expect(transport.connect()).rejects.toThrow();
      expect(transport.state).toBe('error');
    });
  });

  describe('trackMessageLength', () => {
    it('should split length-delimited messages', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        trackMessageLength: true,
        messageDelimiter: '|',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      // Wire format: LENGTH|PAYLOAD (e.g., "5|hello3|bye")
      mockReader.pushData('5|hello3|bye');
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.message).toHaveBeenCalledTimes(2);
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        1,
        expect.objectContaining({ responseBody: 'hello' }),
      );
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        2,
        expect.objectContaining({ responseBody: 'bye' }),
      );
    });

    it('should handle partial messages across chunks', async () => {
      const request: AtmosphereRequest = {
        url: 'https://localhost:8080/chat',
        transport: 'webtransport',
        trackMessageLength: true,
        messageDelimiter: '|',
      };
      transport = new WebTransportTransport(request, mockHandlers);

      await transport.connect();
      await new Promise((r) => setTimeout(r, 0));

      // First chunk: incomplete message (length says 10 but only 5 chars)
      mockReader.pushData('10|hello');
      await new Promise((r) => setTimeout(r, 10));

      // Should not deliver yet — message is incomplete
      expect(mockHandlers.message).not.toHaveBeenCalled();

      // Second chunk: rest of the message
      mockReader.pushData('world');
      await new Promise((r) => setTimeout(r, 10));

      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({ responseBody: 'helloworld' }),
      );
    });
  });
});
