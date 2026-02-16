import { describe, it, expect, vi, beforeEach } from 'vitest';
import { LongPollingTransport } from '../../src/transports/long-polling';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('LongPollingTransport', () => {
  let mockHandlers: SubscriptionHandlers;
  let mockXhr: any;

  beforeEach(() => {
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
    };

    mockXhr = {
      open: vi.fn(),
      send: vi.fn(),
      abort: vi.fn(),
      setRequestHeader: vi.fn(),
      readyState: 0,
      status: 200,
      responseText: '',
      withCredentials: false,
      onreadystatechange: null as ((ev: Event) => void) | null,
      onerror: null as ((ev: Event) => void) | null,
    };

    global.XMLHttpRequest = vi.fn(() => mockXhr) as any;
  });

  it('should return "long-polling" as name', () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);
    expect(transport.name).toBe('long-polling');
  });

  it('should connect and notify open on first successful poll', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    const connectPromise = transport.connect();

    // Simulate successful response
    mockXhr.readyState = 4;
    mockXhr.status = 200;
    mockXhr.responseText = 'hello';
    mockXhr.onreadystatechange?.({} as Event);

    await connectPromise;

    expect(mockHandlers.open).toHaveBeenCalledWith(
      expect.objectContaining({ status: 200, transport: 'long-polling', state: 'open' }),
    );
    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'hello', transport: 'long-polling' }),
    );
  });

  it('should reject on initial connection error', async () => {
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      reconnect: false,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    const connectPromise = transport.connect();

    // Simulate error response
    mockXhr.readyState = 4;
    mockXhr.status = 500;
    mockXhr.onreadystatechange?.({} as Event);

    await expect(connectPromise).rejects.toThrow();
  });

  it('should disconnect and abort active request', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/test', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockXhr.readyState = 4;
    mockXhr.status = 200;
    mockXhr.responseText = '';
    mockXhr.onreadystatechange?.({} as Event);
    await connectPromise;

    await transport.disconnect();

    expect(transport.state).toBe('disconnected');
  });

  it('should include Atmosphere headers in URL', async () => {
    const request: AtmosphereRequest = {
      url: 'http://localhost/test',
      transport: 'long-polling',
      enableProtocol: true,
    };
    const transport = new LongPollingTransport(request, mockHandlers);

    const connectPromise = transport.connect();
    mockXhr.readyState = 4;
    mockXhr.status = 200;
    mockXhr.responseText = '';
    mockXhr.onreadystatechange?.({} as Event);
    await connectPromise;

    const calledUrl = mockXhr.open.mock.calls[0][1];
    expect(calledUrl).toContain('X-Atmosphere-Transport=long-polling');
    expect(calledUrl).toContain('X-atmo-protocol=true');
  });
});
