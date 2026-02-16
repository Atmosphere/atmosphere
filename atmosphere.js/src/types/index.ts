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
export type TransportType = 'websocket' | 'sse' | 'long-polling' | 'streaming' | 'polling' | 'jsonp';

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
  reconnectInterval?: number;
  maxReconnectOnClose?: number;
  maxRequest?: number;
  trackMessageLength?: boolean;
  messageDelimiter?: string;
  enableProtocol?: boolean;
  headers?: Record<string, string>;
  withCredentials?: boolean;
  maxWebsocketErrorRetries?: number;
  pollingInterval?: number;
  closed?: boolean;
  heartbeat?: {
    client?: number;
    server?: number;
  };
}

/**
 * Event handlers for subscriptions
 */
export interface SubscriptionHandlers<T = unknown> {
  message?: (response: AtmosphereResponse<T>) => void;
  open?: (response: AtmosphereResponse<T>) => void;
  close?: (response: AtmosphereResponse<T>) => void;
  error?: (error: Error) => void;
  reconnect?: (request: AtmosphereRequest, response: AtmosphereResponse<T>) => void;
  clientTimeout?: (request: AtmosphereRequest) => void;
  /** Called when the primary transport fails and a fallback is attempted. */
  transportFailure?: (reason: string, request: AtmosphereRequest) => void;
  /** Called when all reconnection attempts have been exhausted. */
  failureToReconnect?: (request: AtmosphereRequest, response: AtmosphereResponse<T>) => void;
  /** Called when a connection is re-established after a disconnect (not on first open). */
  reopen?: (response: AtmosphereResponse<T>) => void;
}

/**
 * Subscription object returned from subscribe()
 */
export interface Subscription {
  readonly id: string;
  readonly state: ConnectionState;
  push(message: string | object | ArrayBuffer): void;
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
 * Retry strategy configuration
 */
export interface RetryStrategy {
  strategy: 'linear' | 'exponential' | 'custom';
  maxAttempts: number;
  initialDelay: number;
  maxDelay: number;
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
  readonly type: 'join' | 'leave' | 'broadcast' | 'direct' | 'presence';
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
  /** Leave this room */
  leave(): void;
}
