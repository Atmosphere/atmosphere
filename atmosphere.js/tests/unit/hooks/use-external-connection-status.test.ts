import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useExternalConnectionStatus } from '../../../src/hooks/react/useExternalConnectionStatus';

/**
 * Unit coverage for the reusable adapter that lets non-atmosphere transports
 * (gRPC, A2A, AG-UI, raw fetch) emit a ConnectionStatusSnapshot the unified
 * Badge can render.
 */
describe('useExternalConnectionStatus', () => {
  it('starts in idle with the supplied transport name', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'grpc' }),
    );

    expect(result.current.status.phase).toBe('idle');
    expect(result.current.status.transport).toBe('grpc');
    expect(result.current.status.lastEvent).toBeNull();
    expect(result.current.status.attempt).toBe(0);
    expect(result.current.status.viaFallback).toBe(false);
    expect(result.current.status.lastError).toBeNull();
  });

  it('honors initialPhase option', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'a2a', initialPhase: 'open' }),
    );

    expect(result.current.status.phase).toBe('open');
    expect(result.current.status.lastEvent).toBe('open');
  });

  it('full request-response lifecycle: connecting → open → closed', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'a2a' }),
    );

    act(() => result.current.markConnecting());
    expect(result.current.status.phase).toBe('connecting');

    act(() => result.current.markOpen());
    expect(result.current.status.phase).toBe('open');
    expect(result.current.status.lastEvent).toBe('open');

    act(() => result.current.markClosed());
    expect(result.current.status.phase).toBe('closed');
    expect(result.current.status.lastEvent).toBe('close');
  });

  it('markLost captures the error and transitions to lost', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'ag-ui' }),
    );
    const err = new Error('stream aborted');

    act(() => result.current.markLost(err));

    expect(result.current.status.phase).toBe('lost');
    expect(result.current.status.lastEvent).toBe('failureToReconnect');
    expect(result.current.status.lastError).toBe(err);
  });

  it('markLost without error leaves lastError null but still transitions', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'grpc' }),
    );

    act(() => result.current.markLost());

    expect(result.current.status.phase).toBe('lost');
    expect(result.current.status.lastError).toBeNull();
  });

  it('reset returns to idle and clears the error', () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'grpc' }),
    );

    act(() => result.current.markLost(new Error('x')));
    act(() => result.current.reset());

    expect(result.current.status.phase).toBe('idle');
    expect(result.current.status.lastEvent).toBeNull();
    expect(result.current.status.lastError).toBeNull();
  });

  it('the `since` timestamp advances on every transition', async () => {
    const { result } = renderHook(() =>
      useExternalConnectionStatus({ transport: 'a2a' }),
    );

    const t0 = result.current.status.since;
    await new Promise((r) => setTimeout(r, 5));
    act(() => result.current.markConnecting());
    const t1 = result.current.status.since;
    expect(t1).toBeGreaterThan(t0);

    await new Promise((r) => setTimeout(r, 5));
    act(() => result.current.markOpen());
    const t2 = result.current.status.since;
    expect(t2).toBeGreaterThan(t1);
  });
});
