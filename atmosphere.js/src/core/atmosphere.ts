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
  AtmosphereConfig,
  AtmosphereRequest,
  Subscription,
  SubscriptionHandlers,
  ConnectionState,
} from '../types';
import { WebSocketTransport } from '../transports/websocket';
import { BaseTransport } from '../transports/base';
import { logger } from '../utils/logger';

/**
 * Main Atmosphere client class
 */
export class Atmosphere {
  readonly version = '5.0.0-alpha.1';
  private subscriptions = new Map<string, Subscription>();
  private subscriptionId = 0;
  private config: AtmosphereConfig;

  constructor(config: AtmosphereConfig = {}) {
    this.config = config;
    if (config.logLevel) {
      logger.setLevel(config.logLevel);
    }
  }

  /**
   * Subscribe to an Atmosphere endpoint
   */
  async subscribe<T = unknown>(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T> = {},
  ): Promise<Subscription> {
    const id = `sub-${++this.subscriptionId}`;

    logger.info(`Creating subscription ${id} to ${request.url}`);

    // Select appropriate transport
    const transport = this.createTransport(request, handlers);

    // Connect
    await transport.connect();

    // Create subscription object
    const eventHandlers = new Map<string, Set<(...args: unknown[]) => void>>();

    const subscription: Subscription = {
      id,
      get state(): ConnectionState {
        return transport.state;
      },
      push: (message: string | object | ArrayBuffer) => {
        const data =
          typeof message === 'string'
            ? message
            : message instanceof ArrayBuffer
              ? message
              : JSON.stringify(message);
        transport.send(data);
      },
      close: async () => {
        await transport.disconnect();
        this.subscriptions.delete(id);
        logger.info(`Subscription ${id} closed`);
      },
      on: (event: string, handler: (...args: unknown[]) => void) => {
        if (!eventHandlers.has(event)) {
          eventHandlers.set(event, new Set());
        }
        eventHandlers.get(event)!.add(handler);
      },
      off: (event: string, handler: (...args: unknown[]) => void) => {
        const handlers = eventHandlers.get(event);
        if (handlers) {
          handlers.delete(handler);
        }
      },
    };

    this.subscriptions.set(id, subscription);
    return subscription;
  }

  /**
   * Close all active subscriptions
   */
  async closeAll(): Promise<void> {
    logger.info('Closing all subscriptions');
    const promises = Array.from(this.subscriptions.values()).map((sub) =>
      sub.close(),
    );
    await Promise.all(promises);
  }

  /**
   * Get all active subscriptions
   */
  getSubscriptions(): Subscription[] {
    return Array.from(this.subscriptions.values());
  }

  private createTransport<T>(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T>,
  ): BaseTransport<T> {
    const transport = request.transport || this.config.defaultTransport || 'websocket';

    switch (transport) {
      case 'websocket':
        return new WebSocketTransport<T>(request, handlers);
      // TODO: Add other transports (SSE, long-polling, etc.)
      default:
        throw new Error(`Unsupported transport: ${transport}`);
    }
  }
}
