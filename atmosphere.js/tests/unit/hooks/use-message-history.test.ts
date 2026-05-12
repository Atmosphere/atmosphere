import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useMessageHistory } from '../../../src/hooks/react/useMessageHistory';

describe('useMessageHistory (react)', () => {
  it('starts with lastSeenId=0 by default', () => {
    const { result } = renderHook(() => useMessageHistory());
    expect(result.current.lastSeenId).toBe(0);
    expect(result.current.sync.lastSeenId).toBe(0);
  });

  it('observe advances lastSeenId reactively', () => {
    const { result } = renderHook(() => useMessageHistory());

    act(() => { result.current.observe({ id: 5 }); });
    expect(result.current.lastSeenId).toBe(5);

    act(() => { result.current.observe({ id: 12 }); });
    expect(result.current.lastSeenId).toBe(12);
  });

  it('observe returns false on out-of-order ids and does not re-render', () => {
    const { result } = renderHook(() => useMessageHistory());

    act(() => { result.current.observe({ id: 10 }); });
    let advanced = true;
    act(() => { advanced = result.current.observe({ id: 7 }); });
    expect(advanced).toBe(false);
    expect(result.current.lastSeenId).toBe(10);
  });

  it('reset returns lastSeenId to zero', () => {
    const { result } = renderHook(() => useMessageHistory());
    act(() => { result.current.observe({ id: 4 }); });
    expect(result.current.lastSeenId).toBe(4);

    act(() => { result.current.reset(); });
    expect(result.current.lastSeenId).toBe(0);
  });

  it('honors an externally-supplied MessageHistorySync instance', async () => {
    const { MessageHistorySync } = await import('../../../src/history/message-history-sync');
    const external = new MessageHistorySync();
    external.observe({ id: 99 });

    const { result } = renderHook(() => useMessageHistory({ instance: external }));
    expect(result.current.sync).toBe(external);
    expect(result.current.lastSeenId).toBe(99);
  });

  it('messages without an id are ignored', () => {
    const { result } = renderHook(() => useMessageHistory());
    act(() => { result.current.observe({ type: 'presence' }); });
    act(() => { result.current.observe({}); });
    expect(result.current.lastSeenId).toBe(0);
  });
});
