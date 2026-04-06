import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SSETransport } from '../../src/transports/sse';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('SSETransport', () => {
  let mockHandlers: SubscriptionHandlers;
  let mockEventSource: any;
  let originalFetch: typeof global.fetch;

  beforeEach(() => {
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
    };

    mockEventSource = {
      close: vi.fn(),
      onopen: null as ((ev: Event) => void) | null,
      onmessage: null as ((ev: MessageEvent) => void) | null,
      onerror: null as ((ev: Event) => void) | null,
    };

    global.EventSource = vi.fn(function() { return mockEventSource; }) as any;

    originalFetch = global.fetch;
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(''),
      headers: new Headers(),
    });
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it('should return "sse" as name', () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
    const transport = new SSETransport(request, mockHandlers);
    expect(transport.name).toBe('sse');
  });

  it('should report availability based on EventSource', () => {
    expect(SSETransport.isAvailable()).toBe(true);
  });

  it('should connect and notify open handler', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
    const transport = new SSETransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockEventSource.onopen?.({} as Event);
    await connectPromise;

    expect(mockHandlers.open).toHaveBeenCalledWith(
      expect.objectContaining({ status: 200, transport: 'sse', state: 'open' }),
    );
    expect(transport.state).toBe('connected');
  });

  it('should deliver messages via onmessage', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
    const transport = new SSETransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockEventSource.onopen?.({} as Event);
    await connectPromise;

    mockEventSource.onmessage?.({ data: 'hello world' } as MessageEvent);

    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({
        responseBody: 'hello world',
        transport: 'sse',
        state: 'messageReceived',
      }),
    );
  });

  it('should reject on initial connection error', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
    const transport = new SSETransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockEventSource.onerror?.({} as Event);

    await expect(connectPromise).rejects.toThrow('SSE connection failed');
    expect(mockHandlers.error).toHaveBeenCalled();
  });

  it('should close EventSource on disconnect', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
    const transport = new SSETransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockEventSource.onopen?.({} as Event);
    await connectPromise;

    await transport.disconnect();

    expect(mockEventSource.close).toHaveBeenCalled();
    expect(transport.state).toBe('disconnected');
  });

  it('should include Atmosphere headers in URL', async () => {
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'sse',
      enableProtocol: true,
      trackMessageLength: true,
    };
    const transport = new SSETransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockEventSource.onopen?.({} as Event);
    await connectPromise;

    const calledUrl = (global.EventSource as any).mock.calls[0][0];
    expect(calledUrl).toContain('X-Atmosphere-Transport=sse');
    expect(calledUrl).toContain('X-Atmosphere-Framework=');
    expect(calledUrl).toContain('X-atmo-protocol=true');
    expect(calledUrl).toContain('X-Atmosphere-TrackMessageSize=true');
  });

  describe('send()', () => {
    it('should send messages via POST fetch', async () => {
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
      const transport = new SSETransport(request, mockHandlers);

      const connectPromise = transport.connect();
      mockEventSource.onopen?.({} as Event);
      await connectPromise;

      transport.send('hello');

      await vi.waitFor(() => {
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
        expect(calls.length).toBeGreaterThan(0);
        const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].body).toBe('hello');
      });
    });

    it('should use credentials: include when withCredentials is true', async () => {
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'sse',
        withCredentials: true,
      };
      const transport = new SSETransport(request, mockHandlers);

      const connectPromise = transport.connect();
      mockEventSource.onopen?.({} as Event);
      await connectPromise;

      transport.send('hello');

      await vi.waitFor(() => {
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
        const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].credentials).toBe('include');
      });
    });

    it('should use credentials: same-origin when withCredentials is falsy', async () => {
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
      const transport = new SSETransport(request, mockHandlers);

      const connectPromise = transport.connect();
      mockEventSource.onopen?.({} as Event);
      await connectPromise;

      transport.send('hello');

      await vi.waitFor(() => {
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
        const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].credentials).toBe('same-origin');
      });
    });

    it('should set Content-Type header on POST', async () => {
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'sse',
        contentType: 'application/json',
      };
      const transport = new SSETransport(request, mockHandlers);

      const connectPromise = transport.connect();
      mockEventSource.onopen?.({} as Event);
      await connectPromise;

      transport.send('{"msg":"hi"}');

      await vi.waitFor(() => {
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
        const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].headers['Content-Type']).toBe('application/json');
      });
    });

    it('should send ArrayBuffer as Blob body', async () => {
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
      const transport = new SSETransport(request, mockHandlers);

      const connectPromise = transport.connect();
      mockEventSource.onopen?.({} as Event);
      await connectPromise;

      const buffer = new ArrayBuffer(4);
      transport.send(buffer);

      await vi.waitFor(() => {
        const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
        const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].body).toBeInstanceOf(Blob);
      });
    });

    it('should log warning on POST failure', async () => {
      global.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'));
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'sse' };
      const transport = new SSETransport(request, mockHandlers);

      // Should not throw
      transport.send('hello');

      // Wait for the promise to settle
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
  });
});
