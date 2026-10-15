import WebSocket from 'ws';
import { randomUUID } from 'crypto';

/**
 * Raw WebSocket client of the spring-boot-durable-sessions chat endpoint.
 *
 * The client's `open` event fires once the 101 upgrade is read, but the server
 * only adds the connection to the chat broadcaster afterwards, while it runs
 * the upgraded request through its interceptors (the DurableSessionInterceptor
 * writes the new session to SQLite) and suspends it. A raw client gets no
 * handshake frame that would say when that is done, so a message another client
 * sends right after `open` can be broadcast before this connection is a member,
 * and is never delivered to it. {@link connectDurableChat} therefore resolves
 * only once the connection has received the echo of a message of its own: the
 * endpoint broadcasts every message to all members, so the echo proves this
 * connection is one of them.
 */

/** Marks the messages the readiness check sends; they never reach `messages`. */
const READY_PREFIX = '__e2e-ready__';

export interface DurableChatConnection {
  ws: WebSocket;
  /** Messages received, the readiness checks of any connection left out. */
  messages: string[];
  /** Headers of the 101 upgrade response, lower-cased. */
  headers: Record<string, string>;
  close: () => void;
}

/**
 * Open a WebSocket to `/atmosphere/chat` and resolve once the server delivers
 * broadcasts to it. The readiness message is sent again every 500 ms until its
 * echo arrives, so a frame the server reads before the connection joins the
 * broadcaster cannot leave the check waiting.
 */
export function connectDurableChat(
  baseUrl: string,
  opts?: { sessionToken?: string; timeoutMs?: number },
): Promise<DurableChatConnection> {
  const timeoutMs = opts?.timeoutMs ?? 15_000;
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + '/atmosphere/chat';
    const requestHeaders: Record<string, string> = {};
    if (opts?.sessionToken) {
      requestHeaders['X-Atmosphere-Session-Token'] = opts.sessionToken;
    }
    const ws = new WebSocket(wsUrl, { headers: requestHeaders });
    const messages: string[] = [];
    const headers: Record<string, string> = {};
    const readyTag = `${READY_PREFIX}${randomUUID()}`;
    let ready = false;
    let resend: ReturnType<typeof setInterval> | undefined;

    const fail = (error: Error) => {
      clearInterval(resend);
      clearTimeout(deadline);
      if (!ready) {
        ws.terminate();
      }
      reject(error);
    };
    const deadline = setTimeout(
      () => fail(new Error(`WebSocket to ${wsUrl} was not delivering broadcasts after ${timeoutMs}ms`)),
      timeoutMs);

    ws.on('upgrade', (response) => {
      for (const [key, value] of Object.entries(response.headers)) {
        if (typeof value === 'string') {
          headers[key.toLowerCase()] = value;
        }
      }
    });

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (!text) return;
      if (text.includes(READY_PREFIX)) {
        if (!ready && text.includes(readyTag)) {
          ready = true;
          clearInterval(resend);
          clearTimeout(deadline);
          resolve({ ws, messages, headers, close: () => ws.close() });
        }
        return;
      }
      messages.push(text);
    });

    ws.on('open', () => {
      const sendReady = () => ws.send(JSON.stringify({ author: 'e2e-ready', message: readyTag }));
      sendReady();
      resend = setInterval(sendReady, 500);
    });
    ws.on('error', (error) => fail(error));
    ws.on('close', (code) => {
      if (!ready) {
        fail(new Error(`WebSocket to ${wsUrl} closed (${code}) before it was delivering broadcasts`));
      }
    });
  });
}

/** Poll `fn` every 200 ms until it holds, or throw after `timeoutMs`. */
export async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}
