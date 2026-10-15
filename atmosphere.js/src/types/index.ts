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
 * Transport types supported by Atmosphere
 */
export type TransportType = 'websocket' | 'sse' | 'long-polling' | 'streaming' | 'webtransport';

/**
 * Connection lifecycle states
 */
export type ConnectionState =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'suspended'
  | 'closed'
  | 'error';

/**
 * Atmosphere response object
 */
export interface AtmosphereResponse<T = unknown> {
  status: number;
  reasonPhrase: string;
  responseBody: T;
  messages: unknown[];
  headers: Record<string, string>;
  state: string;
  transport: TransportType;
  error: Error | null;
  request?: AtmosphereRequest;
}

/**
 * Atmosphere request configuration
 */
export interface AtmosphereRequest {
  url: string;
  transport: TransportType;
  fallbackTransport?: TransportType;
  contentType?: string;
  timeout?: number;
  connectTimeout?: number;
  reconnect?: boolean;
  /**
   * Base delay (ms) of the reconnect backoff when no {@link retryStrategy} is
   * set: attempt `n` (1-based) waits `reconnectInterval * 2^min(n - 1, 5)`
   * scaled by a random jitter in `[0.5, 1)`. Default 1000. With a
   * `retryStrategy`, it is only the fallback for `retryStrategy.initialDelay`.
   */
  reconnectInterval?: number;
  /**
   * Reconnect attempts made after a drop before `failureToReconnect` fires.
   * Default 5. Overridden by `retryStrategy.maxAttempts` when that is set.
   */
  maxReconnectOnClose?: number;
  /**
   * How long to wait between reconnect attempts, and how many to make.
   * Absent, the exponential backoff described on {@link reconnectInterval}
   * applies. See {@link RetryStrategy}.
   */
  retryStrategy?: RetryStrategy;
  maxRequest?: number;
  trackMessageLength?: boolean;
  messageDelimiter?: string;
  enableProtocol?: boolean;
  /**
   * Custom request headers. A value may be a function, called for every
   * request; plain values (and getters) are also re-read for every request, so
   * a value may change between two polls or two sends.
   *
   * Where they travel:
   * - fetch requests (long-polling and streaming connections, and every
   *   message `push`ed over long-polling, streaming or SSE) send them as real
   *   HTTP headers, left out of the URL, when {@link headerDelivery} allows it;
   * - otherwise, and always for WebSocket, WebTransport and the SSE
   *   `EventSource` (which cannot set headers), they are query parameters,
   *   which the Atmosphere server turns back into request headers.
   *
   * A name that is not a valid header token, a forbidden header name (such as
   * `Cookie` or `Origin`), `Content-Type`, or a value with characters outside
   * Latin-1 or with line breaks cannot be a fetch header and stays a query
   * parameter.
   */
  headers?: Record<string, string | (() => string)>;
  /**
   * How {@link headers} and {@link authToken} travel on fetch requests:
   * - `'auto'` (default): as HTTP headers on a same-origin request (and where
   *   there is no page origin, as in React Native, so no CORS preflight), as
   *   query parameters on a cross-origin one: custom headers would make the
   *   browser send a CORS preflight, and the Atmosphere `CorsInterceptor`
   *   allows only a fixed list of request headers;
   * - `'headers'`: as HTTP headers on every fetch request, cross-origin too.
   *   Use it only when the server's CORS policy allows these headers;
   * - `'query'`: as query parameters on every request (earlier versions' only behaviour).
   *
   * A value sent as a header is not visible to the server through
   * `request.getParameter()`, only through `request.getHeader()`.
   */
  headerDelivery?: 'auto' | 'headers' | 'query';
  /**
   * Durable session token for reconnection across server restarts.
   * When set, the token is sent as {@code X-Atmosphere-Session-Token}
   * on every request. The client stores the server-assigned token
   * automatically after the first handshake.
   */
  sessionToken?: string;
  /**
   * Durable run id for crash-resume. The streaming client sets this when the
   * server surfaces the assigned run id (as an {@code X-Atmosphere-Run-Id}
   * metadata frame); it is then re-sent as the {@code X-Atmosphere-Run-Id}
   * query parameter on every reconnect, so a server with durable runs enabled
   * resumes the in-flight run from its effect journal. Cleared when the run
   * reaches a terminal frame (complete/error) so a later reconnect does not
   * try to resume a finished run.
   */
  runId?: string | null;
  /**
   * Authentication token sent as {@code X-Atmosphere-Auth} on every request.
   * Works with all transports (WebSocket, SSE, long-polling). It travels like
   * {@link headers}: an HTTP header on fetch requests that
   * {@link headerDelivery} allows, a query parameter otherwise.
   */
  authToken?: string;
  withCredentials?: boolean;
  /**
   * Explicit URL for WebTransport connections. When set, the WebTransport
   * transport uses this URL instead of deriving one from {@link url}.
   * Useful in development where the HTTP/3 server runs on a different port
   * than the servlet container (e.g., {@code https://localhost:4443/atmosphere/chat}).
   */
  webTransportUrl?: string;
  /**
   * SHA-256 certificate hashes for WebTransport connections with self-signed
   * certificates. Each entry is a base64-encoded SHA-256 hash of the DER-encoded
   * certificate. Required for local development with self-signed certs (max 14 days).
   *
   * Generate with: {@code openssl x509 -in cert.crt -outform DER | openssl dgst -sha256 -binary | base64}
   */
  serverCertificateHashes?: string[];
  maxWebsocketErrorRetries?: number;
  pollingInterval?: number;
  closed?: boolean;
  heartbeat?: {
    client?: number;
    server?: number;
  };
  /**
   * Optional offline queue for buffering messages while disconnected.
   * When set, messages sent via `push()` while disconnected are queued
   * and automatically drained on reconnect (if `drainOnReconnect` is true).
   */
  offlineQueue?: import('../queue/offline-queue').OfflineQueue;
}

/**
 * The `Error` passed to the {@link SubscriptionHandlers.error} handler, carrying
 * whether the client recovers from it on its own.
 *
 * atmosphere.js sets both fields on every error it reports. They are optional
 * in the type only so that code calling an `error` handler itself with a plain
 * `Error` (a test, a wrapper) keeps compiling; such errors read `undefined`.
 */
export interface AtmosphereError extends Error {
  /**
   * `true` when the transport follows this failure with an automatic
   * reconnect attempt (`reconnect` is on and the attempt quota is not spent);
   * `false` when nothing more is attempted for it: the failure of the initial
   * connection made by `subscribe()` over SSE, streaming, long-polling or
   * WebTransport (which `subscribe()` rejects with or falls back from), the
   * failure of the last allowed reconnect attempt, any failure with
   * `reconnect` off, and an undelivered message (`isSendError`).
   */
  willRetry?: boolean;
  /**
   * Reconnect attempt this failure belongs to: 0 for the initial connection
   * or for an open connection that fails, `n` for the n-th reconnect attempt
   * since the connection was last open.
   */
  attempt?: number;
}

/**
 * Event handlers for subscriptions
 */
export interface SubscriptionHandlers<T = unknown> {
  message?: (response: AtmosphereResponse<T>) => void;
  /**
   * Called once the connection is open. Through `Atmosphere.subscribe()` it
   * fires only after `subscribe()` has resolved, so the handler can use the
   * returned `Subscription`; with `enableProtocol` it also waits for the
   * protocol handshake, so the tracking id is known.
   */
  open?: (response: AtmosphereResponse<T>) => void;
  close?: (response: AtmosphereResponse<T>) => void;
  /**
   * Called for every failed connection attempt, including those the transport
   * retries on its own, and for an undelivered message. Use
   * {@link AtmosphereError.willRetry} to tell a transient failure (a reconnect
   * attempt follows, announced by `reconnect`) from a terminal one; after the
   * last failed reconnect attempt `failureToReconnect` also fires.
   */
  error?: (error: AtmosphereError) => void;
  reconnect?: (request: AtmosphereRequest, response: AtmosphereResponse<T>) => void;
  clientTimeout?: (request: AtmosphereRequest) => void;
  /** Called when the primary transport fails and a fallback is attempted. */
  transportFailure?: (reason: string, request: AtmosphereRequest) => void;
  /** Called when all reconnection attempts have been exhausted. */
  failureToReconnect?: (request: AtmosphereRequest, response: AtmosphereResponse<T>) => void;
  /** Called when a connection is re-established after a disconnect (not on first open). */
  reopen?: (response: AtmosphereResponse<T>) => void;
  /**
   * Called when the server sends a refreshed auth token via X-Atmosphere-Auth-Refresh header.
   *
   * **Important:** This callback is header-based and only fires on HTTP-based transports
   * (long-polling, SSE, streaming) or during WebSocket handshake/reconnection.
   * Active WebSocket sessions do not receive HTTP headers on data frames, so this
   * callback will NOT fire mid-session on a live WebSocket connection.
   * To refresh tokens on WebSocket, rely on reconnection (which re-sends headers)
   * or implement application-level token refresh via regular messages.
   */
  authTokenRefresh?: (newToken: string) => void;
  /**
   * Called when the server signals auth expired via X-Atmosphere-Auth-Expired header.
   *
   * **Important:** Same limitation as {@link authTokenRefresh} — this is header-based
   * and will not fire on active WebSocket data frames. It fires on HTTP-based transports
   * or during WebSocket handshake/reconnection only.
   */
  authExpired?: (reason: string) => void;
}

/**
 * Subscription object returned from subscribe()
 */
export interface Subscription {
  /** Client-side subscription id (`sub-N`); the server never sees it. */
  readonly id: string;
  /**
   * The server's tracking id for this connection (`X-Atmosphere-tracking-id`,
   * the `AtmosphereResource` uuid), read from the transport in use on every
   * access. It is `'0'` until the Atmosphere protocol handshake has been
   * received, which happens only with `enableProtocol: true`; when the
   * fallback transport took over, it is the fallback transport's id.
   */
  readonly uuid: string;
  readonly state: ConnectionState;
  push(message: string | object | ArrayBuffer): void;
  /**
   * Close the connection. Over long-polling, streaming and SSE it also sends
   * Atmosphere's close message (`X-Atmosphere-Transport=close` with this
   * connection's {@link uuid} as `X-Atmosphere-tracking-id`, as a body-less
   * `fetch` POST with `keepalive`), which the server's
   * `OnDisconnectInterceptor` (a default interceptor) answers by releasing the
   * `AtmosphereResource`; nothing is sent while {@link uuid} is `'0'` (no
   * `enableProtocol` handshake). While the subscription is open, a `pagehide`
   * listener sends the same message with `navigator.sendBeacon` when the page
   * is hidden or unloaded, marked `X-Atmosphere-Close-Reason=going-away` (a
   * reload or navigation that may come back, as a WebSocket's 1001 close;
   * `close()` sends no reason: the client left); `close()` removes the
   * listener. WebSocket and WebTransport
   * send no extra request: closing the socket tells the server. Calling
   * `close()` again sends nothing more, and a failed close message is logged
   * at debug level, never thrown.
   */
  close(): Promise<void>;
  suspend(): void;
  resume(): Promise<void>;
  on(event: string, handler: (...args: unknown[]) => void): void;
  off(event: string, handler: (...args: unknown[]) => void): void;
}

/**
 * Configuration for the Atmosphere client
 */
export interface AtmosphereConfig {
  logLevel?: 'debug' | 'info' | 'warn' | 'error' | 'silent';
  defaultTransport?: TransportType;
  fallbackTransport?: TransportType;
  interceptors?: AtmosphereInterceptor[];
}

/**
 * Request/response interceptor for transforming data in the pipeline.
 *
 * Interceptors are applied in order for outgoing messages, and in
 * reverse order for incoming messages — like a middleware stack.
 */
export interface AtmosphereInterceptor {
  readonly name?: string;
  /** Transform outgoing message before it is sent. Return the transformed data. */
  onOutgoing?: (data: string | ArrayBuffer) => string | ArrayBuffer;
  /** Transform incoming message before it reaches the handler. Return the transformed body. */
  onIncoming?: (body: string) => string;
}

/**
 * Reconnect delay policy, set on {@link AtmosphereRequest.retryStrategy}.
 *
 * `attempt` below is the number of the reconnect attempt being scheduled:
 * 1 for the first reconnect after a drop, 2 for the next, and so on. It goes
 * back to 1 once a connection opens again. Delays computed from a strategy
 * are deterministic (no jitter); add your own in `customCalculate` if many
 * clients may reconnect at once.
 *
 * - `linear`: `initialDelay * attempt`.
 * - `exponential`: `initialDelay * 2^(attempt - 1)`, capped at `maxDelay`, or
 *   at `initialDelay * 32` when `maxDelay` is not set.
 * - `custom`: `customCalculate(attempt)`.
 *
 * Every result is then capped at `maxDelay` when set and clamped to
 * `[0, 2^31 - 1]` ms (`NaN` becomes 0).
 *
 * A constant delay, e.g. every 5 s: `{ strategy: 'custom', customCalculate: () => 5000 }`,
 * or equivalently `{ strategy: 'linear', initialDelay: 5000, maxDelay: 5000 }`.
 */
export interface RetryStrategy {
  strategy: 'linear' | 'exponential' | 'custom';
  /**
   * Reconnect attempts before `failureToReconnect` fires. When set it
   * replaces {@link AtmosphereRequest.maxReconnectOnClose} (it wins whether
   * larger or smaller); when absent `maxReconnectOnClose` (default 5) applies.
   */
  maxAttempts?: number;
  /**
   * Delay (ms) of the first attempt for `linear` and `exponential`.
   * Default: {@link AtmosphereRequest.reconnectInterval}, then 1000.
   * Not used by `custom` unless `customCalculate` is missing, in which case
   * every attempt waits `initialDelay`.
   */
  initialDelay?: number;
  /** Upper bound (ms) on any computed delay, whatever the strategy. */
  maxDelay?: number;
  /** Delay (ms) before reconnect attempt `attempt` (1-based); used by `custom`. */
  customCalculate?: (attempt: number) => number;
}

// --- Room & Presence types ---

/**
 * Presence event types
 */
export type PresenceEventType = 'join' | 'leave';

/**
 * A member in a room
 */
export interface RoomMember {
  /** Unique connection ID (atmosphere resource uuid) */
  readonly id: string;
  /** Optional display name or user metadata */
  readonly info?: Record<string, unknown>;
}

/**
 * Presence event fired when a member joins or leaves a room
 */
export interface PresenceEvent {
  readonly type: PresenceEventType;
  readonly room: string;
  readonly member: RoomMember;
  readonly timestamp: number;
}

/**
 * Room protocol message envelope sent over the wire.
 * The server-side RoomInterceptor parses these.
 */
export interface RoomMessage {
  readonly type: 'join' | 'leave' | 'broadcast' | 'direct' | 'presence' | 'typing';
  readonly room: string;
  readonly data?: unknown;
  readonly target?: string;
  readonly member?: RoomMember;
}

/**
 * Event handlers for room-level events
 */
export interface RoomHandlers<T = unknown> {
  /** Called when a message is received in the room */
  message?: (data: T, member: RoomMember) => void;
  /** Called when a member joins the room */
  join?: (event: PresenceEvent) => void;
  /** Called when a member leaves the room */
  leave?: (event: PresenceEvent) => void;
  /** Called when the local client has successfully joined the room */
  joined?: (room: string, members: RoomMember[]) => void;
  /** Called when a member starts or stops typing */
  typing?: (event: TypingEvent) => void;
  /** Called on error */
  error?: (error: Error) => void;
}

/**
 * A client-side room handle
 */
export interface RoomHandle {
  /** The room name */
  readonly name: string;
  /** Current members in the room */
  readonly members: ReadonlyMap<string, RoomMember>;
  /** Broadcast a message to all room members */
  broadcast(data: unknown): void;
  /** Send a direct message to a specific member by ID */
  sendTo(memberId: string, data: unknown): void;
  /** Signal typing state to other room members */
  setTyping(typing: boolean): void;
  /** Leave this room */
  leave(): void;
}

/**
 * Typing indicator event fired when a member starts or stops typing
 */
export interface TypingEvent {
  readonly room: string;
  readonly memberId: string | null;
  readonly typing: boolean;
  readonly timestamp: number;
}

// --- Offline Queue types ---

/**
 * State of a queued message
 */
export type MessageState = 'pending' | 'sent' | 'confirmed' | 'failed';

/**
 * A message tracked by the offline queue with optimistic state
 */
export interface TrackedMessage<T = unknown> {
  /** Client-generated unique ID */
  readonly id: string;
  /** The original message data */
  readonly data: T;
  /** Current delivery state */
  readonly state: MessageState;
  /** Timestamp when the message was created */
  readonly createdAt: number;
  /** Error reason if state is 'failed' */
  readonly error?: string;
}

/**
 * Configuration for the offline queue
 */
export interface OfflineQueueConfig {
  /** Maximum number of messages to queue while offline (default: 100) */
  maxSize?: number;
  /** Whether to automatically drain the queue on reconnect (default: true) */
  drainOnReconnect?: boolean;
}

/**
 * Handlers for offline queue and optimistic message tracking
 */
export interface MessageTrackingHandlers<T = unknown> {
  /**
   * Called when a message is enqueued — including core-driven enqueues
   * (subscription.push while disconnected), which bypass the framework
   * hooks' wrapper methods, so reactive size badges stay live.
   */
  onEnqueue?: (message: TrackedMessage<T>) => void;
  /** Called when a queued message is sent after reconnection */
  onDrain?: (message: TrackedMessage<T>) => void;
  /** Called when a message is acknowledged by the server */
  onAck?: (messageId: string) => void;
  /** Called when a message delivery fails */
  onFailed?: (message: TrackedMessage<T>, error: string) => void;
  /** Called when the queue is full and a message is dropped */
  onDrop?: (message: TrackedMessage<T>) => void;
}
