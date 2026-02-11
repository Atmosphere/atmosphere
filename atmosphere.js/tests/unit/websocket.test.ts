import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebSocketTransport } from '../../src/transports/websocket';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('WebSocketTransport', () => {
  let transport: WebSocketTransport;
  let mockHandlers: SubscriptionHandlers;
  let mockWebSocket: any;

  beforeEach(() => {
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
    };

    mockWebSocket = {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      send: vi.fn(),
      close: vi.fn(),
      readyState: WebSocket.OPEN,
      onopen: null,
      onmessage: null,
      onerror: null,
      onclose: null,
      binaryType: 'arraybuffer',
    };

    global.WebSocket = vi.fn(() => mockWebSocket) as any;

    const request: AtmosphereRequest = {
      url: 'ws://localhost:8080/chat',
      transport: 'websocket',
    };

    transport = new WebSocketTransport(request, mockHandlers);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('name', () => {
    it('should return "websocket"', () => {
      expect(transport.name).toBe('websocket');
    });
  });

  describe('connect()', () => {
    it('should create WebSocket connection', async () => {
      const connectPromise = transport.connect();

      // Simulate WebSocket open event
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }

      await connectPromise;

      expect(global.WebSocket).toHaveBeenCalledWith(
        expect.stringContaining('ws://localhost:8080/chat'),
      );
    });

    it('should transform HTTP URL to WS URL', async () => {
      const request: AtmosphereRequest = {
        url: 'http://example.com/chat',
        transport: 'websocket',
      };
      const transport = new WebSocketTransport(request, mockHandlers);

      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      expect(global.WebSocket).toHaveBeenCalledWith(
        expect.stringContaining('ws://example.com/chat'),
      );
    });

    it('should call open handler on successful connection', async () => {
      const connectPromise = transport.connect();

      // Simulate WebSocket open event
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }

      await connectPromise;

      expect(mockHandlers.open).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 200,
          transport: 'websocket',
          state: 'open',
        }),
      );
    });

    it('should reject on connection error', async () => {
      const connectPromise = transport.connect();

      // Simulate WebSocket error
      if (mockWebSocket.onerror) {
        mockWebSocket.onerror({ type: 'error' });
      }

      await expect(connectPromise).rejects.toThrow('WebSocket connection error');
      expect(mockHandlers.error).toHaveBeenCalled();
    });
  });

  describe('send()', () => {
    it('should send message through WebSocket', async () => {
      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      transport.send('Hello World');

      expect(mockWebSocket.send).toHaveBeenCalledWith('Hello World');
    });

    it('should throw error if not connected', () => {
      mockWebSocket.readyState = WebSocket.CLOSED;
      expect(() => transport.send('test')).toThrow('WebSocket is not connected');
    });
  });

  describe('disconnect()', () => {
    it('should close WebSocket connection', async () => {
      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      await transport.disconnect();

      expect(mockWebSocket.close).toHaveBeenCalled();
    });
  });

  describe('message handling', () => {
    it('should notify message handler with received data', async () => {
      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      // Simulate receiving message
      if (mockWebSocket.onmessage) {
        mockWebSocket.onmessage({ data: 'test message' });
      }

      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({
          responseBody: 'test message',
          transport: 'websocket',
          state: 'messageReceived',
        }),
      );
    });

    it('should split messages by delimiter when trackMessageLength is true', async () => {
      const request: AtmosphereRequest = {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        trackMessageLength: true,
        messageDelimiter: '|',
      };
      const transport = new WebSocketTransport(request, mockHandlers);

      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      if (mockWebSocket.onmessage) {
        mockWebSocket.onmessage({ data: 'msg1|msg2|msg3' });
      }

      expect(mockHandlers.message).toHaveBeenCalledTimes(3);
    });
  });

  describe('state', () => {
    it('should start with disconnected state', () => {
      expect(transport.state).toBe('disconnected');
    });

    it('should transition to connected after open', async () => {
      const connectPromise = transport.connect();
      if (mockWebSocket.onopen) {
        mockWebSocket.onopen({ type: 'open' });
      }
      await connectPromise;

      expect(transport.state).toBe('connected');
    });
  });
});
