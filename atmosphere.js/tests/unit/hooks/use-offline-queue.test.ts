import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useOfflineQueue } from '../../../src/hooks/react/useOfflineQueue';
import { OfflineQueue } from '../../../src/queue/offline-queue';

/**
 * Unit coverage for the React adapter that exposes the {@code OfflineQueue}
 * primitive as a reactive hook. The underlying queue is the same one the
 * transport drains on reconnect (via {@code request.offlineQueue}); the hook
 * only adds the re-render plumbing.
 */
describe('useOfflineQueue (react)', () => {
  it('creates a fresh queue with default config when no instance is passed', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());
    expect(result.current.queue).toBeInstanceOf(OfflineQueue);
    expect(result.current.size).toBe(0);
    expect(result.current.pendingCount).toBe(0);
    expect(result.current.messages).toEqual([]);
    expect(result.current.pending).toEqual([]);
  });

  it('honors a caller-provided queue instance instead of creating a new one', () => {
    const external = new OfflineQueue<string>({ maxSize: 7 });
    const { result } = renderHook(() => useOfflineQueue<string>({ instance: external }));
    expect(result.current.queue).toBe(external);
  });

  it('enqueue surfaces the message reactively', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());

    act(() => {
      result.current.enqueue('hello');
    });

    expect(result.current.size).toBe(1);
    expect(result.current.messages).toHaveLength(1);
    expect(result.current.messages[0].data).toBe('hello');
    expect(result.current.messages[0].state).toBe('pending');
  });

  it('clear empties both queued and pending buckets', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());

    act(() => {
      result.current.enqueue('a');
      result.current.enqueue('b');
      result.current.track('c'); // directly-sent, awaiting ACK
    });

    expect(result.current.size).toBe(2);
    expect(result.current.pendingCount).toBe(1);

    act(() => result.current.clear());

    expect(result.current.size).toBe(0);
    expect(result.current.pendingCount).toBe(0);
  });

  it('drain on the underlying queue transitions messages to sent and triggers a re-render', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());

    act(() => {
      result.current.enqueue('first');
      result.current.enqueue('second');
    });
    expect(result.current.size).toBe(2);

    // Simulate the transport drain: BaseTransport.drainOfflineQueue calls
    // queue.drain(sendFn) on reconnect. The handler set by the hook bumps
    // the re-render tick so the snapshot reflects pending → sent.
    const sent: string[] = [];
    act(() => {
      result.current.queue.drain((data) => {
        sent.push(data);
      });
    });

    expect(sent).toEqual(['first', 'second']);
    expect(result.current.size).toBe(0);
    expect(result.current.pendingCount).toBe(2);
    expect(result.current.pending.every((m) => m.state === 'sent')).toBe(true);
  });

  it('acknowledge moves a sent message to confirmed and removes it from pending', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());

    let tracked: ReturnType<typeof result.current.track>;
    act(() => {
      tracked = result.current.track('outbound');
    });
    expect(result.current.pendingCount).toBe(1);

    act(() => {
      result.current.acknowledge(tracked!.id);
    });

    expect(result.current.pendingCount).toBe(0);
  });

  it('fail marks the message failed and removes it from pending', () => {
    const { result } = renderHook(() => useOfflineQueue<string>());

    let tracked: ReturnType<typeof result.current.track>;
    act(() => {
      tracked = result.current.track('outbound');
    });

    act(() => {
      result.current.fail(tracked!.id, 'rate-limited');
    });

    expect(result.current.pendingCount).toBe(0);
  });

  it('respects the maxSize config (oldest evicted, re-render fires for the drop)', () => {
    const { result } = renderHook(() => useOfflineQueue<string>({ maxSize: 2 }));

    act(() => {
      result.current.enqueue('a');
      result.current.enqueue('b');
      result.current.enqueue('c'); // 'a' must be evicted
    });

    expect(result.current.size).toBe(2);
    expect(result.current.messages.map((m) => m.data)).toEqual(['b', 'c']);
  });

  it('queue identity is stable across re-renders', () => {
    const { result, rerender } = renderHook(() => useOfflineQueue<string>());

    const first = result.current.queue;
    rerender();
    rerender();
    expect(result.current.queue).toBe(first);
  });
});
