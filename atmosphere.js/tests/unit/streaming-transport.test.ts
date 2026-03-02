import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { StreamingTransport } from '../../src/transports/streaming';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * Helper to create a mock ReadableStream that yields the given chunks.
 */
function createMockReadableStream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;

  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index]));
        index++;
      } else {
        controller.close();
      }
    },
  });
}

/**
 * Helper to create a mock ReadableStream that stays open until explicitly closed.
 */
function createHangingStream(): {
  stream: ReadableStream<Uint8Array>;
  push: (chunk: string) => void;
  close: () => void;
  error: (err: Error) => void;
} {
  const encoder = new TextEncoder();
  let controller: ReadableStreamDefaultController<Uint8Array>;

  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });

  return {
    stream,
    push(chunk: string) {
      controller.enqueue(encoder.encode(chunk));
    },
    close() {
      controller.close();
    },
    error(err: Error) {
      controller.error(err);
    },
  };
}

describe('StreamingTransport', () => {
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

  function mockStreamingFetch(
    chunks: string[],
    status = 200,
    headers?: Record<string, string>,
  ): void {
    global.fetch = vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      body: createMockReadableStream(chunks),
      headers: new Headers(headers),
    });
  }

  describe('basics', () => {
    it('should return "streaming" as name', () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);
      expect(transport.name).toBe('streaming');
    });
  });

  describe('connect()', () => {
    it('should connect and notify open on successful response', async () => {
      mockStreamingFetch(['hello']);
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      expect(mockHandlers.open).toHaveBeenCalledWith(
        expect.objectContaining({ status: 200, transport: 'streaming', state: 'open' }),
      );
      expect(transport.state).toBe('connected');
    });

    it('should reject on non-ok response', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        body: null,
        headers: new Headers(),
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await expect(transport.connect()).rejects.toThrow('Streaming connection failed: 503');
      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should reject on null response body', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: null,
        headers: new Headers(),
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await expect(transport.connect()).rejects.toThrow('Streaming connection failed: 200');
    });

    it('should reject on network error', async () => {
      global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await expect(transport.connect()).rejects.toThrow('Streaming connection error');
      expect(mockHandlers.error).toHaveBeenCalled();
    });

    it('should include Atmosphere headers in URL', async () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        enableProtocol: true,
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      const calledUrl = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
      expect(calledUrl).toContain('X-Atmosphere-Transport=streaming');
      expect(calledUrl).toContain('X-atmo-protocol=true');
    });

    it('should use credentials: include when withCredentials is true', async () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        withCredentials: true,
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
      expect(fetchOpts.credentials).toBe('include');
    });

    it('should use credentials: same-origin when withCredentials is falsy', async () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
      expect(fetchOpts.credentials).toBe('same-origin');
    });

    it('should pass Content-Type header from request', async () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        contentType: 'application/json',
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
      expect(fetchOpts.headers['Content-Type']).toBe('application/json');
    });

    it('should default Content-Type to text/plain', async () => {
      mockStreamingFetch([]);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      const fetchOpts = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1];
      expect(fetchOpts.headers['Content-Type']).toBe('text/plain');
    });
  });

  describe('streaming data', () => {
    it('should deliver messages from streamed chunks', async () => {
      const { stream, push, close } = createHangingStream();

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: stream,
        headers: new Headers(),
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      push('hello');
      push(' world');

      // Wait for chunks to be processed
      await vi.waitFor(() => {
        expect(mockHandlers.message).toHaveBeenCalledTimes(2);
      });

      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({ responseBody: 'hello', transport: 'streaming' }),
      );
      expect(mockHandlers.message).toHaveBeenCalledWith(
        expect.objectContaining({ responseBody: ' world', transport: 'streaming' }),
      );

      close();
    });

    it('should deliver all chunks from a finite stream', async () => {
      mockStreamingFetch(['chunk1', 'chunk2', 'chunk3']);
      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      // Wait for all chunks to be processed
      await vi.waitFor(() => {
        expect(mockHandlers.message).toHaveBeenCalledTimes(3);
      });

      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        1,
        expect.objectContaining({ responseBody: 'chunk1' }),
      );
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        2,
        expect.objectContaining({ responseBody: 'chunk2' }),
      );
      expect(mockHandlers.message).toHaveBeenNthCalledWith(
        3,
        expect.objectContaining({ responseBody: 'chunk3' }),
      );
    });

    it('should notify close when stream ends normally', async () => {
      mockStreamingFetch(['data']);
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        reconnect: false,
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();

      await vi.waitFor(() => {
        expect(mockHandlers.close).toHaveBeenCalledWith(
          expect.objectContaining({ state: 'closed', transport: 'streaming' }),
        );
      });
    });

    it('should notify close when stream errors after open', async () => {
      const { stream, push, error } = createHangingStream();

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: stream,
        headers: new Headers(),
      });

      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        reconnect: false,
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      push('hello');

      await vi.waitFor(() => {
        expect(mockHandlers.message).toHaveBeenCalledTimes(1);
      });

      error(new Error('Stream failed'));

      await vi.waitFor(() => {
        expect(mockHandlers.close).toHaveBeenCalled();
      });
    });
  });

  describe('disconnect()', () => {
    it('should abort the stream on disconnect', async () => {
      const { stream, close } = createHangingStream();

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body: stream,
        headers: new Headers(),
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      await transport.disconnect();

      expect(transport.state).toBe('disconnected');
      close(); // cleanup
    });

    it('should handle AbortError gracefully on disconnect during connect', async () => {
      global.fetch = vi.fn().mockImplementation((_url: string, opts: RequestInit) => {
        return new Promise((_resolve, reject) => {
          opts.signal?.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted.', 'AbortError'));
          });
        });
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      const connectPromise = transport.connect();
      await new Promise((resolve) => setTimeout(resolve, 10));

      await transport.disconnect();
      // connect() should resolve without throwing (AbortError is swallowed)
      await connectPromise;

      expect(transport.state).toBe('disconnected');
      expect(mockHandlers.error).not.toHaveBeenCalled();
    });
  });

  describe('send()', () => {
    it('should send messages via POST fetch', async () => {
      const fetchCalls: Array<[string, RequestInit]> = [];
      const { stream, close } = createHangingStream();

      global.fetch = vi.fn().mockImplementation((url: string, opts: RequestInit) => {
        fetchCalls.push([url, opts]);
        if (opts?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 200,
            text: () => Promise.resolve(''),
            headers: new Headers(),
          });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          body: stream,
          headers: new Headers(),
        });
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      transport.send('hello world');

      await vi.waitFor(() => {
        const postCall = fetchCalls.find(([, opts]) => opts.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].body).toBe('hello world');
      });

      close();
    });

    it('should send ArrayBuffer as Blob body', async () => {
      const fetchCalls: Array<[string, RequestInit]> = [];
      const { stream, close } = createHangingStream();

      global.fetch = vi.fn().mockImplementation((url: string, opts: RequestInit) => {
        fetchCalls.push([url, opts]);
        if (opts?.method === 'POST') {
          return Promise.resolve({
            ok: true,
            status: 200,
            text: () => Promise.resolve(''),
            headers: new Headers(),
          });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          body: stream,
          headers: new Headers(),
        });
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      const buffer = new ArrayBuffer(8);
      transport.send(buffer);

      await vi.waitFor(() => {
        const postCall = fetchCalls.find(([, opts]) => opts.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].body).toBeInstanceOf(Blob);
      });

      close();
    });

    it('should use credentials: include on POST when withCredentials is true', async () => {
      const fetchCalls: Array<[string, RequestInit]> = [];
      const { stream, close } = createHangingStream();

      global.fetch = vi.fn().mockImplementation((url: string, opts: RequestInit) => {
        fetchCalls.push([url, opts]);
        if (opts?.method === 'POST') {
          return Promise.resolve({
            ok: true, status: 200,
            text: () => Promise.resolve(''),
            headers: new Headers(),
          });
        }
        return Promise.resolve({
          ok: true, status: 200,
          body: stream,
          headers: new Headers(),
        });
      });

      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'streaming',
        withCredentials: true,
      };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      transport.send('test');

      await vi.waitFor(() => {
        const postCall = fetchCalls.find(([, opts]) => opts.method === 'POST');
        expect(postCall).toBeDefined();
        expect(postCall![1].credentials).toBe('include');
      });

      close();
    });

    it('should log warning on POST failure (not throw)', async () => {
      const { stream, close } = createHangingStream();

      global.fetch = vi.fn().mockImplementation((_url: string, opts: RequestInit) => {
        if (opts?.method === 'POST') {
          return Promise.reject(new TypeError('Network error'));
        }
        return Promise.resolve({
          ok: true, status: 200,
          body: stream,
          headers: new Headers(),
        });
      });

      const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'streaming' };
      const transport = new StreamingTransport(request, mockHandlers);

      await transport.connect();
      // Should not throw
      transport.send('hello');

      // Wait for the promise to settle
      await new Promise((resolve) => setTimeout(resolve, 10));
      close();
    });
  });
});
