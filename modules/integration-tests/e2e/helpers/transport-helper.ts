import WebSocket from 'ws';
import { EventSource } from 'eventsource';

/**
 * Raw WebSocket client for testing Atmosphere endpoints without a browser.
 * Similar to the connectAtmosphere() helper in durable-sessions.spec.ts
 * but with explicit transport header support.
 */
export function connectWebSocket(
  baseUrl: string,
  path: string,
  opts?: { headers?: Record<string, string> },
): Promise<{ ws: WebSocket; messages: string[]; close: () => void }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + path +
      '?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl, { headers: opts?.headers });
    const messages: string[] = [];

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (text && !text.startsWith('<!--')) messages.push(text);
    });

    ws.on('open', () => resolve({ ws, messages, close: () => ws.close() }));
    ws.on('error', reject);
    setTimeout(() => reject(new Error('WebSocket connect timeout')), 10_000);
  });
}

/**
 * Raw SSE client for testing Atmosphere endpoints with Server-Sent Events.
 * Uses the EventSource API which handles reconnection automatically.
 */
export function connectSSE(
  baseUrl: string,
  path: string,
): Promise<{ es: EventSource; messages: string[]; close: () => void }> {
  return new Promise((resolve, reject) => {
    const url = baseUrl + path +
      '?X-Atmosphere-Transport=sse&X-Atmosphere-Framework=5.0.0&Content-Type=application/json';
    const es = new EventSource(url);
    const messages: string[] = [];

    es.onmessage = (event: MessageEvent) => {
      const text = String(event.data).trim();
      if (text && !text.startsWith('<!--')) messages.push(text);
    };

    es.onopen = () => resolve({ es, messages, close: () => es.close() });
    es.onerror = (_err: Event) => {
      if (es.readyState === EventSource.CONNECTING) return; // reconnecting
      reject(new Error('SSE connection error'));
    };
    setTimeout(() => reject(new Error('SSE connect timeout')), 10_000);
  });
}

/**
 * Raw long-polling client for testing Atmosphere endpoints.
 * Implements the Atmosphere long-polling protocol:
 * 1. Initial GET to subscribe (blocking)
 * 2. Subsequent GETs to poll for new messages
 * 3. POST to send messages
 */
export class LongPollingClient {
  private abortController = new AbortController();
  private _messages: string[] = [];
  private polling = false;
  private uuid = '';

  constructor(
    private readonly baseUrl: string,
    private readonly path: string,
  ) {}

  get messages(): string[] {
    return this._messages;
  }

  /** Subscribe to the Atmosphere endpoint via long-polling. */
  async connect(): Promise<void> {
    const url = `${this.baseUrl}${this.path}` +
      '?X-Atmosphere-Transport=long-polling&X-Atmosphere-Framework=5.0.0' +
      '&Content-Type=application/json&X-atmo-protocol=true';

    const res = await fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'X-Atmosphere-Transport': 'long-polling',
        'X-Atmosphere-Framework': '5.0.0',
        'X-atmo-protocol': 'true',
      },
      signal: this.abortController.signal,
    });

    if (!res.ok) {
      throw new Error(`Long-polling subscribe failed: ${res.status}`);
    }

    const body = await res.text();
    // First response contains the UUID
    const trimmed = body.trim();
    if (trimmed) {
      // UUID is typically the first non-empty line
      const lines = trimmed.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('<!--'));
      if (lines.length > 0) {
        this.uuid = lines[0];
      }
    }

    this.polling = true;
    this.poll();
  }

  /** Send a message via POST. */
  async send(message: string): Promise<void> {
    const url = `${this.baseUrl}${this.path}`;
    await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Atmosphere-Transport': 'long-polling',
        'X-Atmosphere-Framework': '5.0.0',
        'X-Atmosphere-tracking-id': this.uuid,
      },
      body: message,
      signal: this.abortController.signal,
    });
  }

  /** Close the long-polling connection. */
  close(): void {
    this.polling = false;
    this.abortController.abort();
  }

  private async poll(): Promise<void> {
    while (this.polling) {
      try {
        const url = `${this.baseUrl}${this.path}` +
          `?X-Atmosphere-Transport=long-polling&X-Atmosphere-Framework=5.0.0` +
          `&X-Atmosphere-tracking-id=${this.uuid}`;

        const res = await fetch(url, {
          method: 'GET',
          headers: {
            'X-Atmosphere-Transport': 'long-polling',
            'X-Atmosphere-Framework': '5.0.0',
            'X-Atmosphere-tracking-id': this.uuid,
          },
          signal: this.abortController.signal,
        });

        if (!res.ok) continue;

        const body = await res.text();
        const trimmed = body.trim();
        if (trimmed) {
          const lines = trimmed.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('<!--'));
          for (const line of lines) {
            if (line !== this.uuid) {
              this._messages.push(line);
            }
          }
        }
      } catch {
        if (!this.polling) return; // expected abort
        await new Promise(r => setTimeout(r, 500));
      }
    }
  }
}

/** Wait for a condition with polling. */
export async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}
