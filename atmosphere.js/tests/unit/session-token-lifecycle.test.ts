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

/**
 * The durable session token is a bearer credential: whoever holds it rejoins
 * the session's rooms and broadcaster subscriptions. The client kept it in the
 * tab's sessionStorage with no way to drop it: close() cleared the run id but
 * not the token, and there was no clear API. close() now drops it (abandon()
 * and a reload keep it), clearSessionToken(url) drops it at any time, and a
 * token the server hands back in place of the one sent replaces it, an
 * explicit request.sessionToken included.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { Atmosphere } from '../../src/core/atmosphere';
import { clearSessionToken } from '../../src';
import { WebSocketTransport } from '../../src/transports/websocket';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';
import { sessionTokenStorageKey } from '../../src/utils/session-token-store';

const URL_ = 'ws://localhost:8080/chat';
const TOKEN = '3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f';
const NEW_TOKEN = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';

describe('durable session token lifecycle', () => {
  let sockets: any[];

  beforeEach(() => {
    sessionStorage.clear();
    sockets = [];
    const WSCtor: any = vi.fn(function (url: string) {
      const ws = {
        url, send: vi.fn(), close: vi.fn(), readyState: 1,
        addEventListener: vi.fn(), removeEventListener: vi.fn(),
        onopen: null as any, onmessage: null as any, onerror: null as any, onclose: null as any,
        binaryType: 'arraybuffer',
      };
      sockets.push(ws);
      setTimeout(() => ws.onopen?.({ type: 'open' }), 0);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CONNECTING = 0;
    global.WebSocket = WSCtor;
  });

  afterEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
  });

  function tokenParam(url: string): string | null {
    return new URL(url).searchParams.get('X-Atmosphere-Session-Token');
  }

  async function subscribeAndReceiveToken() {
    const sub = await new Atmosphere().subscribe({ url: URL_, transport: 'websocket', reconnect: false }, {});
    sockets[sockets.length - 1].onmessage?.({ data: `X-Atmosphere-Session-Token=${TOKEN}` });
    expect(sessionStorage.getItem(sessionTokenStorageKey(URL_))).toBe(TOKEN);
    return sub;
  }

  it('forgets the stored token when the subscription is closed for good', async () => {
    const sub = await subscribeAndReceiveToken();

    await sub.close();

    expect(sessionStorage.getItem(sessionTokenStorageKey(URL_))).toBeNull();
    // A later subscription (or a reload of the tab) starts a new session.
    const next = await new Atmosphere().subscribe({ url: URL_, transport: 'websocket', reconnect: false }, {});
    expect(tokenParam(sockets[sockets.length - 1].url)).toBeNull();
    await next.close();
  });

  it('keeps the token when the subscription is abandoned as a lost connection', async () => {
    const sub = await subscribeAndReceiveToken();

    await sub.abandon();

    expect(sessionStorage.getItem(sessionTokenStorageKey(URL_))).toBe(TOKEN);
  });

  it('drops the stored token with clearSessionToken(url)', async () => {
    await subscribeAndReceiveToken();

    clearSessionToken(URL_);

    expect(sessionStorage.getItem(sessionTokenStorageKey(URL_))).toBeNull();
  });

  it('replaces the stored token with a different one the server hands back', async () => {
    sessionStorage.setItem(sessionTokenStorageKey(URL_), TOKEN);
    const handlers: SubscriptionHandlers = {};
    const transport = new WebSocketTransport({ url: URL_, transport: 'websocket' }, handlers);
    const connecting = transport.connect();
    await new Promise((r) => setTimeout(r, 1));
    await connecting;
    expect(tokenParam(sockets[0].url)).toBe(TOKEN);

    sockets[0].onmessage?.({ data: `X-Atmosphere-Session-Token=${NEW_TOKEN}` });

    expect(sessionStorage.getItem(sessionTokenStorageKey(URL_))).toBe(NEW_TOKEN);
  });

  it('sends the token the server handed back on reconnect, not the stale explicit one', async () => {
    const request: AtmosphereRequest = { url: URL_, transport: 'websocket', sessionToken: TOKEN };
    const transport = new WebSocketTransport(request, {});
    let connecting = transport.connect();
    await new Promise((r) => setTimeout(r, 1));
    await connecting;
    expect(tokenParam(sockets[0].url)).toBe(TOKEN);

    // The server no longer knows TOKEN and answers with a new session.
    sockets[0].onmessage?.({ data: `X-Atmosphere-Session-Token=${NEW_TOKEN}` });

    connecting = transport.connect();
    await new Promise((r) => setTimeout(r, 1));
    await connecting;
    expect(tokenParam(sockets[1].url)).toBe(NEW_TOKEN);
  });
});
