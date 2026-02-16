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

import type {
  AtmosphereRequest,
  AtmosphereResponse,
  AtmosphereInterceptor,
  SubscriptionHandlers,
  ConnectionState,
} from '../types';
import { logger } from '../utils/logger';
import { AtmosphereProtocol } from '../utils/protocol';

/**
 * Base class for all transport implementations.
 *
 * Provides protocol handling (handshake, heartbeat, message tracking),
 * lifecycle notifications, connect/inactivity timeouts, suspend/resume,
 * interceptor pipeline, and URL building shared by all transports.
 */
export abstract class BaseTransport<T = unknown> {
  protected request: AtmosphereRequest;
  protected handlers: SubscriptionHandlers<T>;
  protected _state: ConnectionState = 'disconnected';
  protected protocol: AtmosphereProtocol;
  protected interceptors: AtmosphereInterceptor[];
  private _hasOpened = false;
  private _inactivityTimer: ReturnType<typeof setTimeout> | null = null;
  protected _requestCount = 0;

  constructor(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T>,
    interceptors: AtmosphereInterceptor[] = [],
  ) {
    this.request = request;
    this.handlers = handlers;
    this.protocol = new AtmosphereProtocol();
    this.interceptors = interceptors;
  }

  abstract connect(): Promise<void>;
  abstract disconnect(): Promise<void>;
  abstract send(message: string | ArrayBuffer): void;
  abstract get name(): string;

  get state(): ConnectionState {
    return this._state;
  }

  /** The server-assigned UUID for this connection. */
  get uuid(): string {
    return this.protocol.uuid;
  }

  /** Whether this connection has opened at least once. */
  get hasOpened(): boolean {
    return this._hasOpened;
  }

  /**
   * Wrap `connect()` with an optional `connectTimeout`.
   * Subclasses call the raw `connect()`. The Atmosphere core uses this.
   */
  async connectWithTimeout(): Promise<void> {
    const timeout = this.request.connectTimeout;
    if (!timeout || timeout <= 0) {
      return this.connect();
    }

    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Connect timeout after ${timeout}ms`));
      }, timeout);

      this.connect()
        .then(() => {
          clearTimeout(timer);
          resolve();
        })
        .catch((err) => {
          clearTimeout(timer);
          reject(err);
        });
    });
  }

  /** Suspend the connection — stop receiving without disconnect. */
  suspend(): void {
    if (this._state === 'connected') {
      this._state = 'suspended';
      this.stopInactivityTimer();
      logger.info(`${this.name} suspended`);
    }
  }

  /** Resume a suspended connection. */
  async resume(): Promise<void> {
    if (this._state === 'suspended') {
      this._state = 'connected';
      this.resetInactivityTimer();
      logger.info(`${this.name} resumed`);
    }
  }

  /** Apply outgoing interceptors (in order). */
  protected applyOutgoing(data: string | ArrayBuffer): string | ArrayBuffer {
    let result = data;
    for (const interceptor of this.interceptors) {
      if (interceptor.onOutgoing) {
        result = interceptor.onOutgoing(result);
      }
    }
    return result;
  }

  /** Apply incoming interceptors (in reverse order). */
  protected applyIncoming(body: string): string {
    let result = body;
    for (let i = this.interceptors.length - 1; i >= 0; i--) {
      if (this.interceptors[i].onIncoming) {
        result = this.interceptors[i].onIncoming!(result);
      }
    }
    return result;
  }

  protected notifyOpen(response: AtmosphereResponse<T>): void {
    if (this._hasOpened) {
      // This is a reopen after reconnect
      this._state = 'connected';
      logger.debug(`${this.name} transport reopened`);
      this.handlers.reopen?.(response);
    } else {
      this._hasOpened = true;
      this._state = 'connected';
      logger.debug(`${this.name} transport opened`);
      this.handlers.open?.(response);
    }
    this.resetInactivityTimer();
  }

  protected notifyMessage(response: AtmosphereResponse<T>): void {
    if (this._state === 'suspended') return;
    logger.debug(`${this.name} message received`);
    this.resetInactivityTimer();
    this.handlers.message?.(response);
  }

  protected notifyClose(response: AtmosphereResponse<T>): void {
    this._state = 'closed';
    this.stopInactivityTimer();
    logger.debug(`${this.name} transport closed`);
    this.handlers.close?.(response);
  }

  protected notifyError(error: Error): void {
    this._state = 'error';
    this.stopInactivityTimer();
    logger.error(`${this.name} transport error:`, error);
    this.handlers.error?.(error);
  }

  protected notifyReconnect(request: AtmosphereRequest, response: AtmosphereResponse<T>): void {
    this._state = 'reconnecting';
    logger.info(`${this.name} reconnecting...`);
    this.handlers.reconnect?.(request, response);
  }

  protected notifyFailureToReconnect(response: AtmosphereResponse<T>): void {
    logger.warn(`${this.name} all reconnect attempts exhausted`);
    this.handlers.failureToReconnect?.(this.request, response);
  }

  /** Returns true if maxRequest has been reached. */
  protected isMaxRequestReached(): boolean {
    const max = this.request.maxRequest;
    return max !== undefined && max > 0 && this._requestCount >= max;
  }

  private resetInactivityTimer(): void {
    this.stopInactivityTimer();
    const timeout = this.request.timeout;
    if (timeout && timeout > 0) {
      this._inactivityTimer = setTimeout(() => {
        logger.warn(`${this.name} inactivity timeout after ${timeout}ms`);
        this.handlers.clientTimeout?.(this.request);
      }, timeout);
    }
  }

  private stopInactivityTimer(): void {
    if (this._inactivityTimer) {
      clearTimeout(this._inactivityTimer);
      this._inactivityTimer = null;
    }
  }
}
