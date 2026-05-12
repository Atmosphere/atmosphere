import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useOptimistic } from '../../../src/hooks/react/useOptimistic';

describe('useOptimistic (react)', () => {
  it('starts empty', () => {
    const { result } = renderHook(() => useOptimistic<string>());
    expect(result.current.messages).toEqual([]);
    expect(result.current.inFlightCount).toBe(0);
  });

  it('send appends a message in state=sent and counts it as in-flight', () => {
    const { result } = renderHook(() => useOptimistic<string>());

    let handle: ReturnType<typeof result.current.send>;
    act(() => { handle = result.current.send('hello'); });

    expect(result.current.messages).toHaveLength(1);
    expect(result.current.messages[0].state).toBe('sent');
    expect(result.current.messages[0].data).toBe('hello');
    expect(result.current.messages[0].id).toBe(handle!.id);
    expect(result.current.inFlightCount).toBe(1);
  });

  it('commit flips a message to confirmed and lowers inFlightCount', () => {
    const { result } = renderHook(() => useOptimistic<string>());

    let handle: ReturnType<typeof result.current.send>;
    act(() => { handle = result.current.send('hi'); });
    act(() => { result.current.commit(handle!.id); });

    expect(result.current.messages[0].state).toBe('confirmed');
    expect(result.current.inFlightCount).toBe(0);
  });

  it('rollback flips a message to failed and invokes the onRollback callback', () => {
    const onRollback = vi.fn();
    const { result } = renderHook(() => useOptimistic<string>({ onRollback }));

    let handle: ReturnType<typeof result.current.send>;
    act(() => { handle = result.current.send('oops'); });
    act(() => { result.current.rollback(handle!.id, 'rate-limited'); });

    expect(result.current.messages[0].state).toBe('failed');
    expect(result.current.messages[0].error).toBe('rate-limited');
    expect(result.current.inFlightCount).toBe(0);
    expect(onRollback).toHaveBeenCalledTimes(1);
    expect(onRollback.mock.calls[0][1]).toBe('rate-limited');
  });

  it('confirmAfterMs auto-confirms a message that stays in flight past the deadline', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useOptimistic<string>({ confirmAfterMs: 1_000 }));

      act(() => { result.current.send('auto'); });
      expect(result.current.messages[0].state).toBe('sent');

      await act(async () => { vi.advanceTimersByTime(1_000); });

      expect(result.current.messages[0].state).toBe('confirmed');
      expect(result.current.inFlightCount).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('commit cancels the confirmAfterMs timer (no double-confirm)', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useOptimistic<string>({ confirmAfterMs: 1_000 }));

      let handle: ReturnType<typeof result.current.send>;
      act(() => { handle = result.current.send('explicit'); });
      act(() => { result.current.commit(handle!.id); });
      expect(result.current.messages[0].state).toBe('confirmed');

      // The auto-timer should have been cleared. Advancing time does
      // nothing measurable — state stays confirmed.
      await act(async () => { vi.advanceTimersByTime(5_000); });
      expect(result.current.messages[0].state).toBe('confirmed');
    } finally {
      vi.useRealTimers();
    }
  });

  it('rollback cancels the confirmAfterMs timer (no late confirm-after-fail)', async () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useOptimistic<string>({ confirmAfterMs: 1_000 }));

      let handle: ReturnType<typeof result.current.send>;
      act(() => { handle = result.current.send('reject'); });
      act(() => { result.current.rollback(handle!.id, 'server says no'); });
      expect(result.current.messages[0].state).toBe('failed');

      await act(async () => { vi.advanceTimersByTime(5_000); });
      expect(result.current.messages[0].state).toBe('failed');
    } finally {
      vi.useRealTimers();
    }
  });

  it('clear empties the message list and inFlightCount', () => {
    const { result } = renderHook(() => useOptimistic<string>());
    act(() => {
      result.current.send('a');
      result.current.send('b');
    });
    expect(result.current.messages).toHaveLength(2);

    act(() => { result.current.clear(); });
    expect(result.current.messages).toHaveLength(0);
    expect(result.current.inFlightCount).toBe(0);
  });

  it('multiple sends and a single commit only flip the committed one', () => {
    const { result } = renderHook(() => useOptimistic<string>());

    let first: ReturnType<typeof result.current.send>;
    let second: ReturnType<typeof result.current.send>;
    act(() => {
      first = result.current.send('a');
      second = result.current.send('b');
    });

    act(() => { result.current.commit(first!.id); });

    const byId = new Map(result.current.messages.map((m) => [m.id, m.state] as const));
    expect(byId.get(first!.id)).toBe('confirmed');
    expect(byId.get(second!.id)).toBe('sent');
    expect(result.current.inFlightCount).toBe(1);
  });
});
