import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LongPollingTransport } from '../../src/transports/long-polling';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('LongPollingTransport', () => {
  let mockHandlers: SubscriptionHandlers;
  let originalFetch: typeof global.fetch;

  beforeEach(() => {
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
    };

    originalFetch = global.fetch;
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  function mockFetchResponse(body: string, status = 200, headers?: Record<string, string>): void {
    global.fetch = vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      text: () => Promise.resolve(body),
      headers: new Headers(headers),
    });
  }

  it('should return "long-polling" as name', () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);
    expect(transport.name).toBe('long-polling');
  });

  it('should connect and notify open on first successful poll', async () => {
    mockFetchResponse('hello');
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    expect(mockHandlers.open).toHaveBeenCalledWith(
      expect.objectContaining({ status: 200, transport: 'long-polling', state: 'open' }),
    );
    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'hello', transport: 'long-polling' }),
    );
  });

  it('should reject on initial connection error (non-ok status)', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: () => Promise.resolve(''),
      headers: new Headers(),
    });

    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      reconnect: false,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await expect(transport.connect()).rejects.toThrow();
    expect(mockHandlers.error).toHaveBeenCalled();
  });

  it('should reject on initial connection network error', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      reconnect: false,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await expect(transport.connect()).rejects.toThrow();
    expect(mockHandlers.error).toHaveBeenCalled();
  });

  it('should disconnect and abort active request', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();
    await transport.disconnect();

    expect(transport.state).toBe('disconnected');
  });

  it('should include Atmosphere headers in URL', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      enableProtocol: true,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    const calledUrl = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(calledUrl).toContain('X-Atmosphere-Transport=long-polling');
    expect(calledUrl).toContain('X-atmo-protocol=true');
  });

  it('should send messages via POST fetch', async () => {
    // First call is the GET poll, subsequent calls are POST sends
    let callCount = 0;
    global.fetch = vi.fn().mockImplementation(() => {
      callCount++;
      return Promise.resolve({
        ok: true,
        status: 200,
        text: () => Promise.resolve(''),
        headers: new Headers(),
      });
    });

    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();
    transport.send('hello world');

    // Wait for the async send
    await vi.waitFor(() => {
      const calls = (global.fetch as ReturnType<typeof vi.fn>).mock.calls;
      const postCall = calls.find((c: unknown[]) => c[1]?.method === 'POST');
      expect(postCall).toBeDefined();
      expect(postCall![1].body).toBe('hello world');
    });
  });

  it('should use credentials: include when withCredentials is true', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      withCredentials: true,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(fetchOpts.credentials).toBe('include');
  });

  it('should use credentials: same-origin when withCredentials is falsy', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(fetchOpts.credentials).toBe('same-origin');
  });

  it('should not re-poll after disconnect', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();
    await transport.disconnect();

    const callCountAfterDisconnect = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.length;

    // Wait a tick to ensure no further polls happen
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls.length).toBe(callCountAfterDisconnect);
  });

  it('should handle AbortError gracefully on disconnect', async () => {
    // Make fetch hang indefinitely until aborted
    global.fetch = vi.fn().mockImplementation((_url: string, opts: RequestInit) => {
      return new Promise((_resolve, reject) => {
        opts.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'));
        });
      });
    });

    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    const connectPromise = transport.connect();
    // Let the fetch call register
    await new Promise((resolve) => setTimeout(resolve, 10));

    await transport.disconnect();
    // connect() should resolve without throwing
    await connectPromise;

    expect(transport.state).toBe('disconnected');
    expect(mockHandlers.error).not.toHaveBeenCalled();
  });

  it('should pass Content-Type header from request', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      contentType: 'application/json',
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(fetchOpts.headers['Content-Type']).toBe('application/json');
  });

  it('should default Content-Type to text/plain', async () => {
    mockFetchResponse('');
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    await transport.connect();

    const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(fetchOpts.headers['Content-Type']).toBe('text/plain');
  });
});
