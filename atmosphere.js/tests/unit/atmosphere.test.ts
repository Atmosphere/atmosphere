import { describe, it, expect, beforeEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';

describe('Atmosphere', () => {
  let atmosphere: Atmosphere;

  beforeEach(() => {
    atmosphere = new Atmosphere();
  });

  it('should have correct version', () => {
    expect(atmosphere.version).toBe('5.0.0');
  });

  it('should create subscription with generated ID', async () => {
    // Mock WebSocket
    const mockWs = {
      send: () => {},
      close: () => {},
      readyState: WebSocket.OPEN,
      onopen: null,
      onmessage: null,
      onerror: null,
      onclose: null,
      binaryType: 'arraybuffer',
    };
    global.WebSocket = function () {
      setTimeout(() => {
        if (mockWs.onopen) mockWs.onopen({ type: 'open' });
      }, 0);
      return mockWs as any;
    } as any;

    const subscription = await atmosphere.subscribe({
      url: 'ws://localhost:8080/test',
      transport: 'websocket',
    });

    expect(subscription.id).toMatch(/^sub-\d+$/);
    expect(subscription.state).toBe('connected');
  });

  it('should close all subscriptions', async () => {
    const mockWs = {
      send: () => {},
      close: () => {},
      readyState: WebSocket.OPEN,
      onopen: null,
      onmessage: null,
      onerror: null,
      onclose: null,
      binaryType: 'arraybuffer',
    };
    global.WebSocket = function () {
      setTimeout(() => {
        if (mockWs.onopen) mockWs.onopen({ type: 'open' });
      }, 0);
      return mockWs as any;
    } as any;

    await atmosphere.subscribe({
      url: 'ws://localhost:8080/test1',
      transport: 'websocket',
    });

    await atmosphere.subscribe({
      url: 'ws://localhost:8080/test2',
      transport: 'websocket',
    });

    expect(atmosphere.getSubscriptions()).toHaveLength(2);

    await atmosphere.closeAll();

    expect(atmosphere.getSubscriptions()).toHaveLength(0);
  });
});
