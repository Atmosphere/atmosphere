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
  reconnect?: boolean;
  reconnectInterval?: number;
  maxReconnectOnClose?: number;
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
}

/**
 * Subscription object returned from subscribe()
 */
export interface Subscription {
  readonly id: string;
  readonly state: ConnectionState;
  push(message: string | object | ArrayBuffer): void;
  close(): Promise<void>;
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
