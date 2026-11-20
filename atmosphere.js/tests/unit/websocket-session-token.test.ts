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
 * A durable session survives a server restart over WebSocket. The server sent
 * the session token only as an upgrade response header, which a WebSocket
 * client cannot read, so the reconnect after a restart carried no token and
 * the server started a new session. The client now asks for the token in band
 * (`X-Atmosphere-Session-Token-Inband=true`), keeps the
 * `X-Atmosphere-Session-Token=<token>` frame away from the application, sends
 * the token on every reconnect, and keeps it across a page reload.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebSocketTransport } from '../../src/transports/websocket';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';
import { sessionTokenStorageKey } from '../../src/utils/session-token-store';

const TOKEN = '3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f';

describe('WebSocket durable session token', () => {
  let handlers: SubscriptionHandlers;
  let sockets: any[];

  beforeEach(() => {
    sessionStorage.clear();
    handlers = { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn() };
    sockets = [];
    global.WebSocket = vi.fn(function (this: any, url: string) {
      const socket = {
        url,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        send: vi.fn(),
        close: vi.fn(),
        readyState: WebSocket.OPEN,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      sockets.push(socket);
      return socket;
    }) as any;
    (global.WebSocket as any).OPEN = 1;
  });

  afterEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
  });

  async function connect(request: AtmosphereRequest): Promise<{ transport: WebSocketTransport; socket: any }> {
    const transport = new WebSocketTransport(request, handlers);
    const promise = transport.connect();
    const socket = sockets[sockets.length - 1];
    socket.onopen?.({ type: 'open' });
    await promise;
    return { transport, socket };
  }

  function param(url: string, name: string): string | null {
    return new URL(url).searchParams.get(name);
  }

  it('asks the server for the token in band', async () => {
    const { socket } = await connect({ url: 'ws://localhost:8080/chat', transport: 'websocket' });

    expect(param(socket.url, 'X-Atmosphere-Session-Token-Inband')).toBe('true');
  });

  it('keeps the token frame from the application and sends the token on the reconnect', async () => {
    const request: AtmosphereRequest = { url: 'ws://localhost:8080/chat', transport: 'websocket' };
    const { socket } = await connect(request);

    socket.onmessage?.({ data: `X-Atmosphere-Session-Token=${TOKEN}` });
    socket.onmessage?.({ data: 'hello' });

    expect(handlers.message).toHaveBeenCalledTimes(1);
    expect(handlers.message).toHaveBeenCalledWith(expect.objectContaining({ responseBody: 'hello' }));

    // The server restarts: the next connection (a reconnect, or the transport
    // a reloaded page creates) carries the token, so the server restores the
    // session instead of minting a new one.
    const { socket: reconnect } = await connect(request);
    expect(param(reconnect.url, 'X-Atmosphere-Session-Token')).toBe(TOKEN);
    expect(sessionStorage.getItem(sessionTokenStorageKey(request.url))).toBe(TOKEN);
  });

  it('takes a length-prefixed token frame that arrives before the protocol handshake', async () => {
    const request: AtmosphereRequest = {
      url: 'ws://localhost:8080/chat',
      transport: 'websocket',
      enableProtocol: true,
      trackMessageLength: true,
      messageDelimiter: '|',
    };
    const { transport, socket } = await connect(request);
    const frame = `X-Atmosphere-Session-Token=${TOKEN}`;

    socket.onmessage?.({ data: `${frame.length}|${frame}` });
    // The handshake still follows and is still parsed as one.
    socket.onmessage?.({ data: '36|0f9e8d7c-6b5a-4c3d-2e1f-0a9b8c7d6e5f|0|X|' });

    expect((transport as any).protocol.sessionToken).toBe(TOKEN);
    expect((transport as any).protocol.uuid).toBe('0f9e8d7c-6b5a-4c3d-2e1f-0a9b8c7d6e5f');
    expect(handlers.message).not.toHaveBeenCalled();
  });

  it('delivers an application message that only looks like the frame', async () => {
    const { socket } = await connect({ url: 'ws://localhost:8080/chat', transport: 'websocket' });

    socket.onmessage?.({ data: 'X-Atmosphere-Session-Token=not a token!' });

    expect(handlers.message).toHaveBeenCalledTimes(1);
  });

  it('lets an explicit sessionToken win over the stored one', async () => {
    sessionStorage.setItem(sessionTokenStorageKey('ws://localhost:8080/chat'), TOKEN);
    const { socket } = await connect({
      url: 'ws://localhost:8080/chat',
      transport: 'websocket',
      sessionToken: 'chosen-by-app',
    });

    expect(param(socket.url, 'X-Atmosphere-Session-Token')).toBe('chosen-by-app');
  });
});
