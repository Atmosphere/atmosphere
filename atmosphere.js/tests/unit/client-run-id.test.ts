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
import { Atmosphere } from '../../src/core/atmosphere';
import { BaseTransport } from '../../src/transports/base';
import type { AtmosphereRequest, Subscription, TransportType } from '../../src/types';
import { loadRunId } from '../../src/utils/run-id-store';
import { loadPendingPrompt, mintClientRunId } from '../../src/utils/client-run-id';

/**
 * A prompt sent under a run id the client minted (`clientRunId`): kept with
 * its id in the tab before it is sent, wrapped with the id on the wire, and
 * sent again under the same id by a page reloaded before the server received
 * it — when the server says it has no run by that id, or after a bounded wait.
 * Over every transport (mode parity).
 */

const URL_ = 'http://localhost/atmosphere/agent/chat';
const UUID_FORM = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function ok(body = ''): unknown {
  return { ok: true, status: 200, text: () => Promise.resolve(body), headers: new Headers() };
}

function runIdParam(url: string): string | null {
  return new URL(url, 'http://localhost').searchParams.get('X-Atmosphere-Run-Id');
}

interface FakeServer {
  connectUrls: string[];
  /** What the client sent: WebSocket messages, or the bodies it POSTed. */
  sent: string[];
  deliver(body: string): Promise<void>;
}

function install(transport: TransportType, uuid: string): FakeServer {
  const connectUrls: string[] = [];
  const sent: string[] = [];
  const handshake = `${uuid}|0|X|`;
  const postOrPoll = (onPoll: (url: string) => Promise<unknown>) => vi.fn((url: string, init?: RequestInit) => {
    if (String(url).includes('X-Atmosphere-Transport=close')) return Promise.resolve(ok());
    if (init?.method === 'POST') {
      sent.push(String(init.body));
      return Promise.resolve(ok());
    }
    return onPoll(String(url));
  }) as unknown as typeof fetch;
  switch (transport) {
    case 'websocket': {
      let ws: any = null;
      const WSCtor: any = vi.fn(function (url: string) {
        connectUrls.push(url);
        ws = {
          send: vi.fn((m: string) => sent.push(m)), close: vi.fn(), readyState: 1,
          onopen: null, onmessage: null, onerror: null, onclose: null,
        };
        setTimeout(() => {
          ws.onopen?.({});
          ws.onmessage?.({ data: handshake });
        }, 0);
        return ws;
      });
      WSCtor.OPEN = 1;
      WSCtor.CONNECTING = 0;
      global.WebSocket = WSCtor;
      global.fetch = vi.fn(() => Promise.resolve(ok())) as unknown as typeof fetch;
      return { connectUrls, sent, deliver: async (body) => { ws.onmessage({ data: body }); } };
    }
    case 'sse': {
      let es: any = null;
      global.EventSource = vi.fn(function (url: string) {
        connectUrls.push(url);
        es = { close: vi.fn(), onopen: null, onmessage: null, onerror: null };
        setTimeout(() => {
          es.onopen?.({});
          es.onmessage?.({ data: handshake });
        }, 0);
        return es;
      }) as unknown as typeof EventSource;
      global.fetch = postOrPoll(() => Promise.resolve(ok()));
      return { connectUrls, sent, deliver: async (body) => { es.onmessage({ data: body }); } };
    }
    case 'long-polling': {
      const waiting: Array<(body: string) => void> = [];
      let polls = 0;
      global.fetch = postOrPoll((url) => {
        if (polls++ === 0) {
          connectUrls.push(url);
          return Promise.resolve(ok(handshake));
        }
        return new Promise((resolve) => waiting.push((body) => resolve(ok(body))));
      });
      return {
        connectUrls, sent,
        deliver: async (body) => {
          await vi.waitFor(() => expect(waiting.length).toBeGreaterThan(0));
          waiting.shift()!(body);
          await new Promise((r) => setTimeout(r, 0));
        },
      };
    }
    case 'streaming': {
      const encoder = new TextEncoder();
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      global.fetch = postOrPoll((url) => {
        connectUrls.push(url);
        const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
        setTimeout(() => controller.enqueue(encoder.encode(handshake)), 0);
        return Promise.resolve({ ok: true, status: 200, body: stream, headers: new Headers() });
      });
      return {
        connectUrls, sent,
        deliver: async (body) => {
          controller.enqueue(encoder.encode(body));
          await new Promise((r) => setTimeout(r, 0));
        },
      };
    }
    default:
      throw new Error(`no fake for ${transport}`);
  }
}

const runIdFrame = (id: string) =>
  JSON.stringify({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: id, sessionId: 's1', seq: 1 });
const unknownFrame = (id: string) =>
  JSON.stringify({ type: 'metadata', key: 'X-Atmosphere-Run-Unknown', value: id, sessionId: id, seq: 1 });

describe('a prompt sent under a run id the client minted', () => {
  let originalFetch: typeof global.fetch;
  let originalWebSocket: typeof global.WebSocket;
  let originalEventSource: typeof global.EventSource;
  let originalWait: number;
  let subs: Subscription[];

  beforeEach(() => {
    originalFetch = global.fetch;
    originalWebSocket = global.WebSocket;
    originalEventSource = global.EventSource;
    originalWait = BaseTransport.pendingPromptWaitMs;
    subs = [];
    sessionStorage.clear();
    Object.defineProperty(navigator, 'sendBeacon', { value: vi.fn(() => true), configurable: true, writable: true });
  });

  afterEach(async () => {
    for (const sub of subs) {
      await sub.close();
    }
    BaseTransport.pendingPromptWaitMs = originalWait;
    global.fetch = originalFetch;
    global.WebSocket = originalWebSocket;
    global.EventSource = originalEventSource;
    delete (navigator as unknown as Record<string, unknown>).sendBeacon;
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  async function load(transport: TransportType, extra: Partial<AtmosphereRequest> = {}): Promise<Subscription> {
    const sub = await new Atmosphere().subscribe({
      url: URL_, transport, enableProtocol: true, reconnect: false, ...extra,
    } as AtmosphereRequest);
    subs.push(sub);
    await vi.waitFor(() => expect(sub.uuid).not.toBe('0'));
    return sub;
  }

  it('mints a UUIDv4', () => {
    expect(mintClientRunId()).toMatch(UUID_FORM);
    expect(mintClientRunId()).not.toBe(mintClientRunId());
  });

  for (const transport of ['websocket', 'sse', 'long-polling', 'streaming'] as TransportType[]) {
    describe(transport, () => {
      it('sends a prompt alone when the request does not ask for a run id', async () => {
        const server = install(transport, 'uuid-1');
        const sub = await load(transport);
        expect(sub.pushPrompt('what is 2+2?')).toBeNull();
        await vi.waitFor(() => expect(server.sent).toContain('what is 2+2?'));
        expect(loadPendingPrompt(URL_)).toBeNull();
      });

      it('keeps the prompt and its id before sending it wrapped with the id', async () => {
        const server = install(transport, 'uuid-1');
        const sub = await load(transport, { clientRunId: true });
        const runId = sub.pushPrompt('what is 2+2?');
        expect(runId).toMatch(UUID_FORM);
        expect(loadPendingPrompt(URL_)).toEqual({ runId, payload: 'what is 2+2?' });
        expect(loadRunId(URL_)).toBe(runId);
        await vi.waitFor(() => expect(server.sent).toContain(
          JSON.stringify({ clientRunId: runId, prompt: 'what is 2+2?' })));

        // The server's id frame acknowledges it: nothing is kept to send again.
        await server.deliver(runIdFrame(runId!));
        expect(loadPendingPrompt(URL_)).toBeNull();
      });

      it('a reloaded page sends the prompt again under the same id when the server never received it', async () => {
        install(transport, 'uuid-1');
        const page = await load(transport, { clientRunId: true });
        const runId = page.pushPrompt({ prompt: 'hi', hints: { maxCost: 1 } })!;

        // The page reloads before the server answered: a new client.
        const reloaded = install(transport, 'uuid-2');
        await load(transport, { clientRunId: true });
        expect(runIdParam(reloaded.connectUrls[0])).toBe(runId);
        await reloaded.deliver(unknownFrame(runId));
        await vi.waitFor(() => expect(reloaded.sent).toContain(
          JSON.stringify({ clientRunId: runId, prompt: { prompt: 'hi', hints: { maxCost: 1 } } })));
        expect(reloaded.sent.filter((m) => m.includes(runId))).toHaveLength(1);
      });

      it('a reloaded page sends it again after a bounded wait when the server says nothing', async () => {
        BaseTransport.pendingPromptWaitMs = 30;
        install(transport, 'uuid-1');
        const page = await load(transport, { clientRunId: true });
        const runId = page.pushPrompt('hello')!;

        const reloaded = install(transport, 'uuid-2');
        await load(transport, { clientRunId: true });
        await vi.waitFor(() => expect(reloaded.sent).toContain(JSON.stringify({ clientRunId: runId, prompt: 'hello' })));
      });

      it('a reloaded page whose run the server acknowledges does not send it again', async () => {
        // Long enough for the fake server's acknowledgement to land first.
        BaseTransport.pendingPromptWaitMs = 1_000;
        install(transport, 'uuid-1');
        const page = await load(transport, { clientRunId: true });
        const runId = page.pushPrompt('hello')!;

        const reloaded = install(transport, 'uuid-2');
        await load(transport, { clientRunId: true });
        await reloaded.deliver(runIdFrame(runId));
        await new Promise((r) => setTimeout(r, 1_200));
        expect(reloaded.sent.filter((m) => m.includes(runId))).toEqual([]);
        expect(loadPendingPrompt(URL_)).toBeNull();
      });
    });
  }
});
