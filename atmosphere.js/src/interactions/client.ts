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
 * Typed client for the Atmosphere Interactions API
 * (`org.atmosphere.interactions`, exposed at `/api/interactions`).
 *
 * An interaction is a stateful agent turn that carries a durable `steps[]`
 * log and chains via `previousInteractionId`. This client covers the REST
 * surface — create (sync or background), get, list, continue, cancel — plus
 * {@link InteractionsClient.pollUntilTerminal} / {@link InteractionsClient.watch}
 * helpers for the background "retrieve-after-disconnect" pattern.
 *
 * For live socket streaming of a background run — durable steps pushed as they
 * happen over WebSocket/SSE — use {@link InteractionsClient.subscribe}, which
 * connects to the server's per-interaction stream channel.
 */
import { Atmosphere } from '../core/atmosphere';
import type { Subscription } from '../types';

export type InteractionStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface TokenUsage {
  input: number;
  output: number;
  cachedInput: number;
  total: number;
  model: string | null;
}

export interface InteractionStep {
  seq: number;
  type: string;
  text: string | null;
  toolName: string | null;
  data: Record<string, unknown>;
  usage: TokenUsage | null;
  createdAt: string;
}

export interface Interaction {
  id: string;
  parentId: string | null;
  conversationId: string;
  agentId: string | null;
  userId: string;
  model: string | null;
  status: InteractionStatus;
  background: boolean;
  store: boolean;
  steps: InteractionStep[];
  finalText: string | null;
  usage: TokenUsage | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Request body for {@link InteractionsClient.create} / `continue`. */
export interface CreateInteractionRequest {
  message: string;
  /** Detach the run; it returns immediately as `RUNNING` and is polled later. */
  background?: boolean;
  /** Persist a durable record (default `true` server-side). */
  store?: boolean;
  agentId?: string;
  model?: string;
  systemPrompt?: string;
  /** Chain onto a previous interaction (set automatically by `continue`). */
  previousInteractionId?: string;
  metadata?: Record<string, unknown>;
}

export interface ListQuery {
  conversationId?: string;
}

export interface InteractionsClientOptions {
  /**
   * Base URL the API is mounted under. Defaults to '' (same-origin), so calls
   * hit `/api/interactions`. Set e.g. `https://host:8080` for cross-origin/Node.
   */
  baseUrl?: string;
  /** Path prefix for the API. Defaults to `/api/interactions`. */
  basePath?: string;
  /** `fetch` implementation. Defaults to the global `fetch`. */
  fetch?: typeof fetch;
  /** Extra headers (e.g. `Authorization`) sent on every request. */
  headers?: Record<string, string>;
  /** Credentials mode. Defaults to `same-origin`. */
  credentials?: RequestCredentials;
}

export interface PollOptions {
  /** Poll interval in ms. Default 700. */
  intervalMs?: number;
  /** Give up after this many ms. Default 120000. 0 disables the timeout. */
  timeoutMs?: number;
  /** Abort the poll. */
  signal?: AbortSignal;
  /** Invoked with the interaction after each poll (live progress). */
  onUpdate?: (interaction: Interaction) => void;
}

/** Handlers for {@link InteractionsClient.subscribe} live socket streaming. */
export interface LiveHandlers {
  /** A durable step arrived (deduped by sequence; replay + live merged). */
  onStep?: (step: InteractionStep) => void;
  /** The run reached a terminal state; the subscription auto-closes after this. */
  onTerminal?: (info: { status: InteractionStatus; finalText: string | null; errorMessage: string | null }) => void;
  /** Transport opened. */
  onOpen?: () => void;
  /** Transport closed. */
  onClose?: () => void;
  /** Transport error. */
  onError?: (error: Error) => void;
}

export interface SubscribeOptions {
  /** Primary transport. Default 'websocket'. */
  transport?: 'websocket' | 'sse' | 'long-polling';
  /** Fallback when the primary fails. Default 'sse'. */
  fallbackTransport?: 'websocket' | 'sse' | 'long-polling';
  /** Path the live-stream handler is mounted at. Default '/atmosphere/interactions-stream'. */
  streamPath?: string;
}

/** Error thrown when the Interactions API returns a non-2xx response. */
export class InteractionsError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'InteractionsError';
    this.status = status;
  }
}

const TERMINAL: ReadonlySet<InteractionStatus> = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]);

/** Whether a status is terminal (no further steps will be appended). */
export function isTerminal(status: InteractionStatus): boolean {
  return TERMINAL.has(status);
}

export class InteractionsClient {
  private readonly baseUrl: string;
  private readonly basePath: string;
  private readonly doFetch: typeof fetch;
  private readonly headers: Record<string, string>;
  private readonly credentials: RequestCredentials;
  private liveClient?: Atmosphere;

  constructor(options: InteractionsClientOptions = {}) {
    const f = options.fetch ?? globalThis.fetch;
    if (typeof f !== 'function') {
      throw new Error(
        'No fetch implementation available; pass one via InteractionsClientOptions.fetch',
      );
    }
    // Bind so a global `fetch` is not invoked with the wrong receiver.
    this.doFetch = f.bind(globalThis);
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.basePath = options.basePath ?? '/api/interactions';
    this.headers = options.headers ?? {};
    this.credentials = options.credentials ?? 'same-origin';
  }

  /** Launch an interaction. With `background: true` it returns immediately as RUNNING. */
  create(request: CreateInteractionRequest): Promise<Interaction> {
    return this.send<Interaction>('POST', '', request);
  }

  /** Continue an existing interaction, chaining via `previousInteractionId`. */
  continue(
    id: string,
    request: CreateInteractionRequest,
  ): Promise<Interaction> {
    return this.send<Interaction>('POST', `/${encodeURIComponent(id)}/continue`, request);
  }

  /** Retrieve one interaction with its full durable `steps[]`. */
  get(id: string): Promise<Interaction> {
    return this.send<Interaction>('GET', `/${encodeURIComponent(id)}`);
  }

  /** List the caller's interactions (ownership-scoped server-side). */
  list(query: ListQuery = {}): Promise<Interaction[]> {
    const q = query.conversationId
      ? `?conversationId=${encodeURIComponent(query.conversationId)}`
      : '';
    return this.send<Interaction[]>('GET', q);
  }

  /** Request cancellation of an in-flight background interaction. */
  async cancel(id: string): Promise<boolean> {
    const res = await this.raw('POST', `/${encodeURIComponent(id)}/cancel`);
    return res.ok;
  }

  /**
   * Subscribe to an interaction's LIVE step stream over the Atmosphere socket
   * (WebSocket, falling back to SSE). The server replays the steps captured so
   * far on connect, then pushes each new durable step as it happens — no
   * polling. Steps are deduped by sequence (replay + live overlap), and the
   * subscription auto-closes once a terminal frame arrives.
   *
   * ```ts
   * const sub = await client.subscribe(id, {
   *   onStep: (s) => append(s),
   *   onTerminal: ({ status }) => console.log('done', status),
   * });
   * // sub.close() to stop early
   * ```
   */
  async subscribe(
    id: string,
    handlers: LiveHandlers = {},
    options: SubscribeOptions = {},
  ): Promise<Subscription> {
    const streamPath = options.streamPath ?? '/atmosphere/interactions-stream';
    const url = `${this.baseUrl}${streamPath}?id=${encodeURIComponent(id)}`;
    const seen = new Set<number>();
    const atm = (this.liveClient ??= new Atmosphere());

    const onFrame = (raw: unknown) => {
      for (const frame of parseFrames(raw)) {
        if (frame.type === 'interaction-step' && frame.step) {
          const step = frame.step as InteractionStep;
          if (!seen.has(step.seq)) {
            seen.add(step.seq);
            handlers.onStep?.(step);
          }
        } else if (frame.type === 'interaction-terminal') {
          handlers.onTerminal?.({
            status: frame.status as InteractionStatus,
            finalText: (frame.finalText ?? null) as string | null,
            errorMessage: (frame.errorMessage ?? null) as string | null,
          });
          // Terminal reached — tear down the socket.
          void subscription.close();
        }
      }
    };

    const subscription = await atm.subscribe(
      {
        url,
        transport: options.transport ?? 'websocket',
        fallbackTransport: options.fallbackTransport ?? 'sse',
        headers: this.headers,
        withCredentials: this.credentials === 'include',
      },
      {
        message: (response) => onFrame(response.responseBody),
        open: () => handlers.onOpen?.(),
        close: () => handlers.onClose?.(),
        error: (e) => handlers.onError?.(e instanceof Error ? e : new Error(String(e))),
      },
    );
    return subscription;
  }

  /**
   * Poll an interaction until it reaches a terminal state, then resolve with
   * the final record. Rejects on timeout, abort, or a fetch error.
   */
  async pollUntilTerminal(id: string, options: PollOptions = {}): Promise<Interaction> {
    const interval = options.intervalMs ?? 700;
    const timeout = options.timeoutMs ?? 120000;
    const deadline = timeout > 0 ? Date.now() + timeout : Infinity;

    for (;;) {
      if (options.signal?.aborted) {
        throw new InteractionsError('Poll aborted', 0);
      }
      const interaction = await this.get(id);
      options.onUpdate?.(interaction);
      if (isTerminal(interaction.status)) {
        return interaction;
      }
      if (Date.now() + interval > deadline) {
        throw new InteractionsError(
          `Interaction ${id} did not terminate within ${timeout}ms`,
          0,
        );
      }
      await delay(interval, options.signal);
    }
  }

  /**
   * Async iterator that yields the interaction after each poll until it is
   * terminal (the terminal record is the last value yielded). Lets a caller
   * render progressive `steps[]` with `for await`:
   *
   * ```ts
   * for await (const it of client.watch(id)) {
   *   render(it.steps);
   * }
   * ```
   */
  async *watch(id: string, options: PollOptions = {}): AsyncGenerator<Interaction> {
    const interval = options.intervalMs ?? 700;
    const timeout = options.timeoutMs ?? 120000;
    const deadline = timeout > 0 ? Date.now() + timeout : Infinity;

    for (;;) {
      if (options.signal?.aborted) {
        throw new InteractionsError('Watch aborted', 0);
      }
      const interaction = await this.get(id);
      options.onUpdate?.(interaction);
      yield interaction;
      if (isTerminal(interaction.status)) {
        return;
      }
      if (Date.now() + interval > deadline) {
        throw new InteractionsError(
          `Interaction ${id} did not terminate within ${timeout}ms`,
          0,
        );
      }
      await delay(interval, options.signal);
    }
  }

  // -- internals --

  private async send<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.raw(method, path, body);
    if (!res.ok) {
      throw new InteractionsError(await errorMessage(res), res.status);
    }
    return (await res.json()) as T;
  }

  private raw(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json', ...this.headers };
    const init: RequestInit = { method, headers, credentials: this.credentials };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return this.doFetch(`${this.baseUrl}${this.basePath}${path}`, init);
  }
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json();
    if (body && typeof body.error === 'string') {
      return body.error;
    }
  } catch {
    // non-JSON error body — fall through to the status line
  }
  return `${res.status} ${res.statusText}`;
}

/**
 * Parse one transport message into zero or more interaction frames. Normally a
 * message carries exactly one JSON frame, but consecutive server writes can
 * arrive concatenated (`{...}{...}`) — split on top-level object boundaries so
 * none are dropped.
 */
export function parseFrames(raw: unknown): Array<Record<string, unknown>> {
  if (raw && typeof raw === 'object') {
    return [raw as Record<string, unknown>];
  }
  if (typeof raw !== 'string' || raw.length === 0) {
    return [];
  }
  const frames: Array<Record<string, unknown>> = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (escaped) { escaped = false; continue; }
    if (c === '\\') { escaped = true; continue; }
    if (c === '"') { inString = !inString; continue; }
    if (inString) { continue; }
    if (c === '{') { if (depth === 0) start = i; depth++; }
    else if (c === '}') {
      depth--;
      if (depth === 0 && start >= 0) {
        try { frames.push(JSON.parse(raw.slice(start, i + 1))); } catch { /* skip non-JSON chunk */ }
        start = -1;
      }
    }
  }
  return frames;
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new InteractionsError('Aborted', 0));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}
