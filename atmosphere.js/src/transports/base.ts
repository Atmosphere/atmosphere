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
import { AtmosphereProtocol } from '../utils/protocol';

/**
 * Base class for all transport implementations.
 *
 * Provides protocol handling (handshake, heartbeat, message tracking),
 * lifecycle notifications, and URL building shared by all transports.
 */
export abstract class BaseTransport<T = unknown> {
  protected request: AtmosphereRequest;
  protected handlers: SubscriptionHandlers<T>;
  protected _state: ConnectionState = 'disconnected';
  protected protocol: AtmosphereProtocol;

  constructor(request: AtmosphereRequest, handlers: SubscriptionHandlers<T>) {
    this.request = request;
    this.handlers = handlers;
    this.protocol = new AtmosphereProtocol();
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
}
