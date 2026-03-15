import { describe, it, expect, beforeEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';
import { OfflineQueue } from '../../src/queue/offline-queue';

describe('OfflineQueue integration with Atmosphere.subscribe', () => {
  let atmosphere: Atmosphere;
  let mockWs: any;
  let sentMessages: string[];

  beforeEach(() => {
    atmosphere = new Atmosphere();
    sentMessages = [];

    mockWs = {
      send: (data: string) => sentMessages.push(data),
      close: () => {},
      readyState: WebSocket.OPEN,
      onopen: null as any,
      onmessage: null as any,
      onerror: null as any,
      onclose: null as any,
      binaryType: 'arraybuffer',
    };

    global.WebSocket = function () {
      setTimeout(() => {
        if (mockWs.onopen) mockWs.onopen({ type: 'open' });
      }, 0);
      return mockWs as any;
    } as any;
  });

  it('enqueues messages when disconnected and queue is configured', async () => {
    const queue = new OfflineQueue<string | object | ArrayBuffer>();

    const sub = await atmosphere.subscribe({
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
      offlineQueue: queue,
    });

    // Simulate disconnect by closing the WS
    mockWs.readyState = WebSocket.CLOSED;
    if (mockWs.onclose) {
      mockWs.onclose({ code: 1006, reason: 'abnormal' });
    }

    // Push while disconnected — should enqueue, not throw
    sub.push('hello-offline');
    sub.push('hello-offline-2');

    expect(queue.size).toBe(2);
    expect(queue.messages[0].data).toBe('hello-offline');
    expect(queue.messages[1].data).toBe('hello-offline-2');
  });

  it('sends directly when connected (queue not used)', async () => {
    const queue = new OfflineQueue<string | object | ArrayBuffer>();

    const sub = await atmosphere.subscribe({
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
      offlineQueue: queue,
    });

    sub.push('hello-online');

    expect(queue.size).toBe(0);
    // Message was sent directly via WebSocket
    expect(sentMessages).toContain('hello-online');
  });

  it('drains queue on reconnect when drainOnReconnect is true', async () => {
    const queue = new OfflineQueue<string | object | ArrayBuffer>({ drainOnReconnect: true });

    const sub = await atmosphere.subscribe({
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
      offlineQueue: queue,
    });

    // Enqueue some messages directly (simulating offline period)
    queue.enqueue('queued-1');
    queue.enqueue('queued-2');
    expect(queue.size).toBe(2);

    // Simulate reconnect: close then reopen
    mockWs.readyState = WebSocket.CLOSED;
    if (mockWs.onclose) {
      mockWs.onclose({ code: 1006, reason: 'abnormal' });
    }

    // Simulate successful reconnection — trigger onopen again
    mockWs.readyState = WebSocket.OPEN;
    if (mockWs.onopen) {
      mockWs.onopen({ type: 'open' });
    }

    // Queue should be drained
    expect(queue.size).toBe(0);
    expect(sentMessages).toContain('queued-1');
    expect(sentMessages).toContain('queued-2');
  });

  it('does not drain queue when drainOnReconnect is false', async () => {
    const queue = new OfflineQueue<string | object | ArrayBuffer>({ drainOnReconnect: false });

    await atmosphere.subscribe({
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
      offlineQueue: queue,
    });

    queue.enqueue('queued-1');

    // Simulate reconnect
    mockWs.readyState = WebSocket.CLOSED;
    if (mockWs.onclose) {
      mockWs.onclose({ code: 1006, reason: 'abnormal' });
    }
    mockWs.readyState = WebSocket.OPEN;
    if (mockWs.onopen) {
      mockWs.onopen({ type: 'open' });
    }

    // Queue should NOT be drained
    expect(queue.size).toBe(1);
  });
});
