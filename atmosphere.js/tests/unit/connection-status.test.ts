import { describe, it, expect, vi } from 'vitest';
import { ConnectionStatus } from '../../src/resilience';
import type {
  AtmosphereRequest,
  AtmosphereResponse,
  SubscriptionHandlers,
} from '../../src/types';

function makeRequest(overrides: Partial<AtmosphereRequest> = {}): AtmosphereRequest {
  return {
    url: 'http://localhost/test',
    transport: 'websocket',
    ...overrides,
  };
}

function makeResponse(
  overrides: Partial<AtmosphereResponse> = {},
): AtmosphereResponse {
  return {
    status: 200,
    reasonPhrase: 'OK',
    responseBody: '',
    messages: [],
    headers: {},
    state: 'messageReceived',
    transport: 'websocket',
    error: null,
    ...overrides,
  };
}

describe('ConnectionStatus', () => {
  it('starts in idle phase with sensible defaults', () => {
    const status = new ConnectionStatus();
    expect(status.snapshot.phase).toBe('idle');
    expect(status.snapshot.lastEvent).toBeNull();
    expect(status.snapshot.transport).toBe('websocket');
    expect(status.snapshot.attempt).toBe(0);
    expect(status.snapshot.viaFallback).toBe(false);
  });

  it('honors initialTransport option', () => {
    const status = new ConnectionStatus({ initialTransport: 'sse' });
    expect(status.snapshot.transport).toBe('sse');
  });

  it('transitions idle → open on first open event', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse({ transport: 'websocket' }));

    expect(status.snapshot.phase).toBe('open');
    expect(status.snapshot.lastEvent).toBe('open');
    expect(status.snapshot.transport).toBe('websocket');
  });

  it('transitions open → reconnecting → open with reopen event', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();

    handlers.open?.(makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());
    expect(status.snapshot.phase).toBe('reconnecting');
    expect(status.snapshot.lastEvent).toBe('reconnect');
    expect(status.snapshot.attempt).toBe(1);

    handlers.open?.(makeResponse());
    expect(status.snapshot.phase).toBe('open');
    expect(status.snapshot.lastEvent).toBe('reopen');
    expect(status.snapshot.attempt).toBe(0);
  });

  it('increments attempt counter across multiple reconnects', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse());

    handlers.reconnect?.(makeRequest(), makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());

    expect(status.snapshot.phase).toBe('reconnecting');
    expect(status.snapshot.attempt).toBe(3);
  });

  it('marks viaFallback after transportFailure and updates transport', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();

    handlers.transportFailure?.(
      'websocket handshake failed',
      makeRequest({ transport: 'websocket', fallbackTransport: 'sse' }),
    );

    expect(status.snapshot.viaFallback).toBe(true);
    expect(status.snapshot.transport).toBe('sse');
    expect(status.snapshot.lastEvent).toBe('transportFailure');
    expect(status.snapshot.lastError?.message).toBe('websocket handshake failed');
  });

  it('transitions reconnecting → lost on failureToReconnect', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());

    handlers.failureToReconnect?.(makeRequest(), makeResponse());

    expect(status.snapshot.phase).toBe('lost');
    expect(status.snapshot.lastEvent).toBe('failureToReconnect');
  });

  it('transitions to closed on a clean close', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse());

    handlers.close?.(makeResponse({ state: 'closed' }));

    expect(status.snapshot.phase).toBe('closed');
    expect(status.snapshot.lastEvent).toBe('close');
  });

  it('records last error on error event without changing phase', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse());

    const err = new Error('socket reset');
    handlers.error?.(err);

    expect(status.snapshot.lastError).toBe(err);
    expect(status.snapshot.lastEvent).toBe('error');
    expect(status.snapshot.phase).toBe('open'); // error alone is not terminal
  });

  it('records clientTimeout event without forcing a phase change', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();
    handlers.open?.(makeResponse());

    handlers.clientTimeout?.(makeRequest());

    expect(status.snapshot.lastEvent).toBe('clientTimeout');
    expect(status.snapshot.phase).toBe('open');
  });

  it('preserves wrapped consumer handlers (delegates to original)', () => {
    const status = new ConnectionStatus();
    const consumerOpen = vi.fn();
    const consumerMessage = vi.fn();
    const consumerError = vi.fn();
    const consumerTransportFailure = vi.fn();

    const handlers: SubscriptionHandlers = status.wrap({
      open: consumerOpen,
      message: consumerMessage,
      error: consumerError,
      transportFailure: consumerTransportFailure,
    });

    const response = makeResponse();
    handlers.open?.(response);
    handlers.message?.(response);
    const err = new Error('x');
    handlers.error?.(err);
    handlers.transportFailure?.('reason', makeRequest());

    expect(consumerOpen).toHaveBeenCalledWith(response);
    expect(consumerMessage).toHaveBeenCalledWith(response);
    expect(consumerError).toHaveBeenCalledWith(err);
    expect(consumerTransportFailure).toHaveBeenCalledWith('reason', expect.any(Object));
  });

  it('notifies subscribers on every state change', () => {
    const status = new ConnectionStatus();
    const listener = vi.fn();
    status.onChange(listener);

    const handlers = status.wrap();
    handlers.open?.(makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());
    handlers.open?.(makeResponse());

    expect(listener).toHaveBeenCalledTimes(3);
    expect(listener.mock.calls[0][0].phase).toBe('open');
    expect(listener.mock.calls[1][0].phase).toBe('reconnecting');
    expect(listener.mock.calls[2][0].phase).toBe('open');
    expect(listener.mock.calls[2][0].lastEvent).toBe('reopen');
  });

  it('unsubscribe stops further notifications', () => {
    const status = new ConnectionStatus();
    const listener = vi.fn();
    const unsubscribe = status.onChange(listener);

    const handlers = status.wrap();
    handlers.open?.(makeResponse());
    expect(listener).toHaveBeenCalledTimes(1);

    unsubscribe();
    handlers.reconnect?.(makeRequest(), makeResponse());
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('updates `since` only when phase actually changes', () => {
    let t = 1000;
    const status = new ConnectionStatus({ now: () => t });
    const handlers = status.wrap();

    handlers.open?.(makeResponse());
    const openSince = status.snapshot.since;
    expect(openSince).toBe(1000);

    t = 2000;
    handlers.error?.(new Error('blip')); // does not change phase
    expect(status.snapshot.since).toBe(openSince);

    t = 3000;
    handlers.reconnect?.(makeRequest(), makeResponse()); // changes phase
    expect(status.snapshot.since).toBe(3000);
  });

  it('reset returns to idle while preserving transport setting', () => {
    const status = new ConnectionStatus({ initialTransport: 'sse' });
    const handlers = status.wrap();
    handlers.open?.(makeResponse({ transport: 'sse' }));
    handlers.close?.(makeResponse({ state: 'closed' }));

    status.reset();

    expect(status.snapshot.phase).toBe('idle');
    expect(status.snapshot.lastEvent).toBeNull();
    expect(status.snapshot.attempt).toBe(0);
    expect(status.snapshot.viaFallback).toBe(false);
  });

  it('reopen-only reconnect (no preceding open) still transitions phase back to open', () => {
    // Regression: some transports emit only `reopen` on reconnect, not
    // a separate `open` event. The Badge must still flip back to "open"
    // rather than stay stuck on "reconnecting" forever.
    const status = new ConnectionStatus();
    const handlers = status.wrap();

    handlers.open?.(makeResponse());
    handlers.reconnect?.(makeRequest(), makeResponse());
    expect(status.snapshot.phase).toBe('reconnecting');

    handlers.reopen?.(makeResponse());
    expect(status.snapshot.phase).toBe('open');
    expect(status.snapshot.lastEvent).toBe('reopen');
    expect(status.snapshot.attempt).toBe(0);
  });

  it('full transport-failure → fallback-open → server-disconnect → reopen flow', () => {
    const status = new ConnectionStatus();
    const handlers = status.wrap();

    // WebSocket primary fails, falls back to SSE
    handlers.transportFailure?.(
      'WS handshake 502',
      makeRequest({ transport: 'websocket', fallbackTransport: 'sse' }),
    );
    expect(status.snapshot.viaFallback).toBe(true);
    expect(status.snapshot.transport).toBe('sse');

    // Fallback opens
    handlers.open?.(makeResponse({ transport: 'sse' }));
    expect(status.snapshot.phase).toBe('open');
    expect(status.snapshot.viaFallback).toBe(true); // sticky until next subscribe

    // Server drops the connection
    handlers.reconnect?.(makeRequest({ transport: 'sse' }), makeResponse());
    expect(status.snapshot.phase).toBe('reconnecting');
    expect(status.snapshot.attempt).toBe(1);

    // Reconnect succeeds
    handlers.open?.(makeResponse({ transport: 'sse' }));
    expect(status.snapshot.phase).toBe('open');
    expect(status.snapshot.lastEvent).toBe('reopen');
    expect(status.snapshot.attempt).toBe(0);
  });
});
