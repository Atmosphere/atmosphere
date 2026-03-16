import WebSocket from 'ws';

/**
 * Parsed AI streaming event from the wire protocol.
 * Supports both legacy format (type: "streaming-text") and
 * new AiEvent format (event: "text-delta").
 */
export interface StreamingEvent {
  type: 'streaming-text' | 'progress' | 'metadata' | 'complete' | 'error';
  data?: string | Record<string, unknown>;
  sessionId?: string;
  seq?: number;
  key?: string;
  value?: unknown;
  /** New AiEvent wire protocol: event type name (e.g., "text-delta", "tool-start"). */
  event?: string;
}

/**
 * WebSocket client for AI streaming endpoints.
 * Collects tokens, metadata, and events for assertion.
 */
export class AiWsClient {
  private ws: WebSocket | null = null;
  private doneLatch: (() => void) | null = null;

  readonly events: StreamingEvent[] = [];
  readonly tokens: string[] = [];
  readonly metadata: Map<string, unknown> = new Map();
  readonly errors: string[] = [];
  readonly sessionIds: Set<string> = new Set();

  constructor(
    private readonly wsUrl: string,
    private readonly path: string,
    private readonly headers: Record<string, string> = {},
  ) {}

  /** Connect to the AI endpoint. */
  async connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const url = `${this.wsUrl}${this.path}?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0`;
      this.ws = new WebSocket(url, { headers: this.headers });

      this.ws.on('message', (data) => {
        const text = data.toString().trim();
        if (!text) return;

        // Atmosphere may send multiple JSON objects in one frame
        for (const line of text.split('\n')) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith('<!--')) continue;
          try {
            const event = JSON.parse(trimmed) as StreamingEvent;
            this.handleEvent(event);
          } catch {
            // Not JSON — probably Atmosphere padding or handshake UUID
          }
        }
      });

      this.ws.on('open', () => resolve());
      this.ws.on('error', reject);
      setTimeout(() => reject(new Error('WebSocket connect timeout')), 10_000);
    });
  }

  /** Send a prompt to the AI endpoint. */
  send(prompt: string): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket not connected');
    }
    this.ws.send(prompt);
  }

  /** Wait for a complete or error event. */
  async waitForDone(timeoutMs = 30_000): Promise<void> {
    if (this.events.some(e => e.type === 'complete' || e.type === 'error')) {
      return;
    }
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error(
          `waitForDone timed out after ${timeoutMs}ms. ` +
          `Events: ${JSON.stringify(this.events.map(e => e.type))}`
        ));
      }, timeoutMs);

      this.doneLatch = () => {
        clearTimeout(timeout);
        resolve();
      };
    });
  }

  /** Wait for a specific metadata key to appear. */
  async waitForMetadata(key: string, timeoutMs = 10_000): Promise<unknown> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (this.metadata.has(key)) return this.metadata.get(key);
      await new Promise(r => setTimeout(r, 100));
    }
    throw new Error(`Metadata key "${key}" not received within ${timeoutMs}ms`);
  }

  /** Wait until at least N events of a specific type are collected. */
  async waitForEvents(type: string, count: number, timeoutMs = 15_000): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (this.events.filter(e => e.type === type).length >= count) return;
      await new Promise(r => setTimeout(r, 100));
    }
    const actual = this.events.filter(e => e.type === type).length;
    throw new Error(
      `Expected ${count} "${type}" events, got ${actual} within ${timeoutMs}ms`
    );
  }

  /** Get the full concatenated response from all token events. */
  get fullResponse(): string {
    return this.tokens.join('');
  }

  /** Get tokens for a specific session ID (useful for fan-out). */
  tokensForSession(sessionId: string): string[] {
    return this.events
      .filter(e => e.type === 'streaming-text' && e.sessionId === sessionId)
      .map(e => e.data ?? '');
  }

  /** Close the WebSocket connection. */
  close(): void {
    this.ws?.close();
    this.ws = null;
  }

  /** Reset collected state for reuse. */
  reset(): void {
    this.events.length = 0;
    this.tokens.length = 0;
    this.metadata.clear();
    this.errors.length = 0;
    this.sessionIds.clear();
    this.doneLatch = null;
  }

  /** Get all AiEvent-format events of a given type. */
  aiEvents(eventType: string): StreamingEvent[] {
    return this.events.filter(e => e.event === eventType);
  }

  /** Get data payload of the first AiEvent of a given type. */
  aiEventData(eventType: string): Record<string, unknown> | undefined {
    const ev = this.events.find(e => e.event === eventType);
    return ev?.data as Record<string, unknown> | undefined;
  }

  private handleEvent(event: StreamingEvent): void {
    this.events.push(event);
    if (event.sessionId) {
      this.sessionIds.add(event.sessionId);
    }

    // Handle new AiEvent wire format (event: "text-delta", "tool-start", etc.)
    if (event.event) {
      switch (event.event) {
        case 'text-delta': {
          const d = event.data as Record<string, unknown> | undefined;
          if (d?.text) this.tokens.push(d.text as string);
          break;
        }
        case 'error':
          if (event.data) {
            const d = event.data as Record<string, unknown>;
            this.errors.push((d.message as string) ?? 'unknown error');
          }
          this.doneLatch?.();
          this.doneLatch = null;
          break;
        case 'complete':
          this.doneLatch?.();
          this.doneLatch = null;
          break;
      }
      return;
    }

    // Legacy wire format (type: "streaming-text", "progress", etc.)
    switch (event.type) {
      case 'streaming-text':
        if (event.data) this.tokens.push(event.data as string);
        break;
      case 'metadata':
        if (event.key != null) this.metadata.set(event.key, event.value);
        break;
      case 'error':
        if (event.data) this.errors.push(event.data as string);
        this.doneLatch?.();
        this.doneLatch = null;
        break;
      case 'complete':
        this.doneLatch?.();
        this.doneLatch = null;
        break;
    }
  }
}
