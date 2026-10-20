/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AtmosphereProtocol } from '../../src/utils/protocol';
import { WebSocketTransport } from '../../src/transports/websocket';
import { LongPollingTransport } from '../../src/transports/long-polling';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * A connection lost before the server noticed loses the frames the server
 * wrote into it meanwhile: neither the room's cache nor the run's replay
 * knows they never arrived. The client says what it last received of each AI
 * session (`X-Atmosphere-Resume`, `sessionId:seq` pairs) on every request, so
 * its reconnect is written what it missed from there, and nothing it holds.
 */

const S1 = '6f1c2a9e-5b7d-4e0a-9f3c-1d2e3f4a5b6c';
const S2 = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e';

function text(sessionId: string, seq: number, data = 'x'): string {
  return JSON.stringify({ type: 'streaming-text', data, sessionId, seq });
}

function resumeOf(url: string): string | null {
  return new URL(url, 'http://localhost').searchParams.get('X-Atmosphere-Resume');
}

describe('the resume point the protocol records', () => {
  it('keeps the last seq of each session, the most recently received last', () => {
    const protocol = new AtmosphereProtocol();
    expect(protocol.resumePoint).toBeNull();

    protocol.recordFrames(text(S1, 1));
    protocol.recordFrames(text(S1, 2));
    protocol.recordFrames(text(S2, 7));
    protocol.recordFrames(JSON.stringify({ type: 'complete', sessionId: S1, seq: 3 }));

    expect(protocol.resumePoint).toBe(`${S2}:7,${S1}:3`);
  });

  it('reads every frame of a multi-frame long-polling reply, and a frame whose data nests objects', () => {
    const protocol = new AtmosphereProtocol();
    const nested = JSON.stringify({ event: 'tool-start', data: { sessionId: 'inner', seq: 99 }, sessionId: S2, seq: 4 });
    protocol.recordFrames(`${text(S1, 5)}\n${nested}\n`);

    expect(protocol.resumePoint).toBe(`${S1}:5,${S2}:4`);
  });

  it('never moves a session back, ignores what is not an AI frame, and names at most 16 sessions', () => {
    const protocol = new AtmosphereProtocol();
    protocol.recordFrames(text(S1, 9));
    protocol.recordFrames(text(S1, 4));
    protocol.recordFrames('{"type":"presence","count":2}');
    protocol.recordFrames(JSON.stringify({ type: 'streaming-text', sessionId: 'not a session!', seq: 1 }));
    expect(protocol.resumePoint).toBe(`${S1}:9`);

    for (let i = 0; i < 20; i++) protocol.recordFrames(text(`s${i}`, i + 1));
    const pairs = protocol.resumePoint!.split(',');
    expect(pairs).toHaveLength(16);
    expect(pairs[15]).toBe('s19:20');
    expect(pairs).not.toContain(`${S1}:9`);
  });

  it('is sent on the URL, and seeded only from a well-formed value', () => {
    const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'websocket' };
    const protocol = new AtmosphereProtocol();
    expect(resumeOf(protocol.buildUrl(request))).toBeNull();

    protocol.seedResume('../../etc:1');
    expect(protocol.resumePoint).toBeNull();
    protocol.seedResume(`${S1}:3,${S2}:8`);
    expect(resumeOf(protocol.buildUrl(request))).toBe(`${S1}:3,${S2}:8`);
  });
});

describe('a reconnect sends the resume point', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function handlers(): SubscriptionHandlers {
    return { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn(), reconnect: vi.fn(), reopen: vi.fn() };
  }

  it('over WebSocket, once the socket dropped', async () => {
    vi.useFakeTimers();
    const sockets: any[] = [];
    const urls: string[] = [];
    const WSCtor: any = vi.fn(function (url: string) {
      const ws = { send: vi.fn(), close: vi.fn(), readyState: 1, onopen: null as any, onmessage: null as any,
        onerror: null as any, onclose: null as any, binaryType: 'arraybuffer' };
      urls.push(url);
      sockets.push(ws);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CONNECTING = 0;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;

    const transport = new WebSocketTransport({
      url: 'ws://localhost:8080/ai', transport: 'websocket', reconnect: true, reconnectInterval: 100,
      maxReconnectOnClose: 5,
    }, handlers());
    const connected = transport.connect();
    sockets[0].onopen?.();
    await connected;
    expect(resumeOf(urls[0])).toBeNull();

    sockets[0].onmessage?.({ data: text(S1, 2) });
    sockets[0].onmessage?.({ data: text(S1, 3) });
    expect(transport.resumePoint).toBe(`${S1}:3`);

    sockets[0].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(1000);
    expect(sockets).toHaveLength(2);
    expect(resumeOf(urls[1])).toBe(`${S1}:3`);
  });

  describe('over long-polling', () => {
    let originalFetch: typeof global.fetch;

    beforeEach(() => {
      originalFetch = global.fetch;
    });

    afterEach(() => {
      global.fetch = originalFetch;
    });

    it('on the poll after a reply', async () => {
      const urls: string[] = [];
      const replies = [text(S1, 4), ''];
      global.fetch = vi.fn((url: string) => {
        urls.push(String(url));
        const body = replies.shift();
        if (body === undefined) return new Promise(() => { /* the last poll waits */ });
        return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(body), headers: new Headers() });
      }) as unknown as typeof fetch;

      const transport = new LongPollingTransport({ url: 'http://localhost/ai', transport: 'long-polling' },
        handlers());
      await transport.connect();
      await vi.waitFor(() => expect(urls.length).toBeGreaterThanOrEqual(2));

      expect(resumeOf(urls[0])).toBeNull();
      expect(resumeOf(urls[1])).toBe(`${S1}:4`);
      await transport.disconnect();
    });
  });

  it('starts from the resume point a request carries over from an earlier subscription', () => {
    const transport = new WebSocketTransport({ url: 'ws://localhost:8080/ai', transport: 'websocket',
      resume: `${S2}:6` }, handlers());
    expect(transport.resumePoint).toBe(`${S2}:6`);
  });
});
