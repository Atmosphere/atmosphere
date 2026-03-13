import { describe, it, expect, beforeEach, vi } from 'vitest';
import { OfflineQueue } from '../../src/queue/offline-queue';

describe('OfflineQueue', () => {
  let queue: OfflineQueue<string>;

  beforeEach(() => {
    queue = new OfflineQueue({ maxSize: 5 });
  });

  it('enqueues messages and reports size', () => {
    queue.enqueue('msg-1');
    queue.enqueue('msg-2');
    expect(queue.size).toBe(2);
  });

  it('returns tracked message with client-generated ID', () => {
    const tracked = queue.enqueue('hello');
    expect(tracked.id).toBeDefined();
    expect(tracked.data).toBe('hello');
    expect(tracked.state).toBe('pending');
    expect(tracked.createdAt).toBeGreaterThan(0);
  });

  it('drains queue via send function', () => {
    queue.enqueue('a');
    queue.enqueue('b');
    queue.enqueue('c');

    const sent: string[] = [];
    queue.drain((data) => sent.push(data));

    expect(sent).toEqual(['a', 'b', 'c']);
    expect(queue.size).toBe(0);
  });

  it('transitions messages to sent state on drain', () => {
    queue.enqueue('msg');
    const drained: string[] = [];

    queue.setHandlers({
      onDrain: (msg) => drained.push(msg.state),
    });

    queue.drain(() => {});
    expect(drained).toEqual(['sent']);
  });

  it('transitions to failed if send throws', () => {
    queue.enqueue('msg');
    const failures: string[] = [];

    queue.setHandlers({
      onFailed: (msg, err) => failures.push(err),
    });

    queue.drain(() => { throw new Error('connection lost'); });
    expect(failures).toEqual(['connection lost']);
  });

  it('drops oldest when queue exceeds maxSize', () => {
    const drops: string[] = [];
    queue.setHandlers({
      onDrop: (msg) => drops.push(msg.data),
    });

    for (let i = 0; i < 7; i++) {
      queue.enqueue(`msg-${i}`);
    }

    expect(queue.size).toBe(5);
    expect(drops).toEqual(['msg-0', 'msg-1']);
  });

  it('acknowledge transitions from sent to confirmed', () => {
    const tracked = queue.enqueue('hello');
    let acked = false;

    queue.setHandlers({ onAck: () => { acked = true; } });
    queue.drain(() => {});
    queue.acknowledge(tracked.id);

    expect(acked).toBe(true);
    expect(queue.pendingCount).toBe(0);
  });

  it('fail transitions from sent to failed', () => {
    const tracked = queue.enqueue('hello');
    const errors: string[] = [];

    queue.setHandlers({ onFailed: (_msg, err) => errors.push(err) });
    queue.drain(() => {});
    queue.fail(tracked.id, 'timeout');

    expect(errors).toEqual(['timeout']);
    expect(queue.pendingCount).toBe(0);
  });

  it('track creates a directly-sent message for ACK tracking', () => {
    const tracked = queue.track('direct-msg');
    expect(tracked.state).toBe('sent');
    expect(queue.pendingCount).toBe(1);
    expect(queue.size).toBe(0); // Not in the queue

    queue.acknowledge(tracked.id);
    expect(queue.pendingCount).toBe(0);
  });

  it('messages returns read-only snapshot of queue', () => {
    queue.enqueue('a');
    queue.enqueue('b');

    const msgs = queue.messages;
    expect(msgs.length).toBe(2);
    expect(msgs[0].data).toBe('a');
    expect(msgs[1].data).toBe('b');
  });

  it('pending returns read-only snapshot of pending acks', () => {
    queue.enqueue('a');
    queue.drain(() => {});

    const pending = queue.pending;
    expect(pending.length).toBe(1);
    expect(pending[0].data).toBe('a');
    expect(pending[0].state).toBe('sent');
  });

  it('clear removes all queued and pending messages', () => {
    queue.enqueue('a');
    queue.enqueue('b');
    queue.drain(() => {});
    queue.enqueue('c');

    queue.clear();
    expect(queue.size).toBe(0);
    expect(queue.pendingCount).toBe(0);
  });

  it('drainOnReconnect defaults to true', () => {
    const q = new OfflineQueue();
    expect(q.drainOnReconnect).toBe(true);
  });

  it('respects drainOnReconnect false config', () => {
    const q = new OfflineQueue({ drainOnReconnect: false });
    expect(q.drainOnReconnect).toBe(false);
  });
});
