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
  SubscriptionHandlers,
  ConnectionState,
} from '../types';
import { logger } from '../utils/logger';

/**
 * Base class for all transport implementations
 */
export abstract class BaseTransport<T = unknown> {
  protected request: AtmosphereRequest;
  protected handlers: SubscriptionHandlers<T>;
  protected _state: ConnectionState = 'disconnected';

  constructor(request: AtmosphereRequest, handlers: SubscriptionHandlers<T>) {
    this.request = request;
    this.handlers = handlers;
  }

  abstract connect(): Promise<void>;
  abstract disconnect(): Promise<void>;
  abstract send(message: string | ArrayBuffer): void;
  abstract get name(): string;

  get state(): ConnectionState {
    return this._state;
  }

  protected notifyOpen(response: AtmosphereResponse<T>): void {
    this._state = 'connected';
    logger.debug(`${this.name} transport opened`);
    this.handlers.open?.(response);
  }

  protected notifyMessage(response: AtmosphereResponse<T>): void {
    logger.debug(`${this.name} message received`);
    this.handlers.message?.(response);
  }

  protected notifyClose(response: AtmosphereResponse<T>): void {
    this._state = 'closed';
    logger.debug(`${this.name} transport closed`);
    this.handlers.close?.(response);
  }

  protected notifyError(error: Error): void {
    this._state = 'error';
    logger.error(`${this.name} transport error:`, error);
    this.handlers.error?.(error);
  }

  protected notifyReconnect(request: AtmosphereRequest, response: AtmosphereResponse<T>): void {
    this._state = 'reconnecting';
    logger.info(`${this.name} reconnecting...`);
    this.handlers.reconnect?.(request, response);
  }

  protected buildUrl(baseUrl: string): string {
    try {
      const url = new URL(baseUrl, window.location.href);

      // Add headers as query parameters if configured
      if (this.request.headers) {
        Object.entries(this.request.headers).forEach(([key, value]) => {
          url.searchParams.set(key, value);
        });
      }

      return url.toString();
    } catch (error) {
      logger.error('Failed to build URL:', error);
      return baseUrl;
    }
  }
}
