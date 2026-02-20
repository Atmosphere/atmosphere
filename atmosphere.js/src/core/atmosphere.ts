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
  AtmosphereResponse,
  AtmosphereInterceptor,
  Subscription,
  SubscriptionHandlers,
  ConnectionState,
  TransportType,
} from '../types';
import { WebSocketTransport } from '../transports/websocket';
import { SSETransport } from '../transports/sse';
import { LongPollingTransport } from '../transports/long-polling';
import { StreamingTransport } from '../transports/streaming';
import { BaseTransport } from '../transports/base';
import { logger } from '../utils/logger';

/**
 * Main Atmosphere client class.
 *
 * Manages subscriptions with automatic transport selection and fallback.
 * Default transport: WebSocket. Configure `fallbackTransport` for automatic
 * retry with a different transport on failure.
 */
export class Atmosphere {
  readonly version = '5.0.0';
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
   * Subscribe to an Atmosphere endpoint.
   *
   * If the primary transport fails and `fallbackTransport` is configured,
   * the client will automatically retry with the fallback transport and
   * notify via the `transportFailure` handler.
   */
  async subscribe<T = unknown>(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T> = {},
  ): Promise<Subscription> {
    const id = `sub-${++this.subscriptionId}`;
    const transport = request.transport || this.config.defaultTransport || 'websocket';
    const fallback = request.fallbackTransport ?? this.config.fallbackTransport;

    logger.info(`Creating subscription ${id} to ${request.url} via ${transport}`);

    const mergedRequest = { ...request, transport };
    let activeTransport: BaseTransport<T>;

    try {
      activeTransport = this.createTransport(mergedRequest, handlers);
      await activeTransport.connectWithTimeout();
    } catch (primaryError) {
      if (fallback && fallback !== transport) {
        const reason = primaryError instanceof Error ? primaryError.message : String(primaryError);
        logger.info(`${transport} failed, falling back to ${fallback}`);
        handlers.transportFailure?.(reason, mergedRequest);
        const fallbackRequest = { ...mergedRequest, transport: fallback };
        activeTransport = this.createTransport(fallbackRequest, handlers);
        await activeTransport.connectWithTimeout();
      } else {
        throw primaryError;
      }
    }

    const currentTransport = activeTransport;
    const eventHandlers = new Map<string, Set<(...args: unknown[]) => void>>();

    // Helper to dispatch an event to all on() listeners for a given event name.
    const dispatchEvent = (event: string, ...args: unknown[]) => {
      const listeners = eventHandlers.get(event);
      if (listeners) {
        for (const listener of listeners) {
          listener(...args);
        }
      }
    };

    // Wrap original handlers to also dispatch to on() listeners.
    const originalMessage = handlers.message;
    handlers.message = ((response: AtmosphereResponse<T>) => {
      originalMessage?.(response);
      dispatchEvent('message', response);
    }) as SubscriptionHandlers<T>['message'];

    const originalOpen = handlers.open;
    handlers.open = ((response: AtmosphereResponse<T>) => {
      originalOpen?.(response);
      dispatchEvent('open', response);
    }) as SubscriptionHandlers<T>['open'];

    const originalClose = handlers.close;
    handlers.close = ((response: AtmosphereResponse<T>) => {
      originalClose?.(response);
      dispatchEvent('close', response);
    }) as SubscriptionHandlers<T>['close'];

    const originalError = handlers.error;
    handlers.error = ((error: Error) => {
      originalError?.(error);
      dispatchEvent('error', error);
    }) as SubscriptionHandlers<T>['error'];

    const originalReconnect = handlers.reconnect;
    handlers.reconnect = ((request: AtmosphereRequest, response: AtmosphereResponse<T>) => {
      originalReconnect?.(request, response);
      dispatchEvent('reconnect', request, response);
    }) as SubscriptionHandlers<T>['reconnect'];

    const originalReopen = handlers.reopen;
    handlers.reopen = ((response: AtmosphereResponse<T>) => {
      originalReopen?.(response);
      dispatchEvent('reopen', response);
    }) as SubscriptionHandlers<T>['reopen'];

    const subscription: Subscription = {
      id,
      get state(): ConnectionState {
        return currentTransport.state;
      },
      push: (message: string | object | ArrayBuffer) => {
        const data =
          typeof message === 'string'
            ? message
            : message instanceof ArrayBuffer
              ? message
              : JSON.stringify(message);
        currentTransport.send(data);
      },
      close: async () => {
        await currentTransport.disconnect();
        this.subscriptions.delete(id);
        logger.info(`Subscription ${id} closed`);
      },
      suspend: () => {
        currentTransport.suspend();
      },
      resume: async () => {
        await currentTransport.resume();
      },
      on: (event: string, handler: (...args: unknown[]) => void) => {
        if (!eventHandlers.has(event)) {
          eventHandlers.set(event, new Set());
        }
        eventHandlers.get(event)!.add(handler);
      },
      off: (event: string, handler: (...args: unknown[]) => void) => {
        const listeners = eventHandlers.get(event);
        if (listeners) {
          listeners.delete(handler);
        }
      },
    };

    this.subscriptions.set(id, subscription);
    return subscription;
  }

  /**
   * Close all active subscriptions.
   */
  async closeAll(): Promise<void> {
    logger.info('Closing all subscriptions');
    const promises = Array.from(this.subscriptions.values()).map((sub) =>
      sub.close(),
    );
    await Promise.all(promises);
  }

  /**
   * Get all active subscriptions.
   */
  getSubscriptions(): Subscription[] {
    return Array.from(this.subscriptions.values());
  }

  private createTransport<T>(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T>,
  ): BaseTransport<T> {
    const transport: TransportType = request.transport || this.config.defaultTransport || 'websocket';
    const interceptors: AtmosphereInterceptor[] = this.config.interceptors ?? [];

    switch (transport) {
      case 'websocket':
        return new WebSocketTransport<T>(request, handlers, interceptors);
      case 'sse':
        return new SSETransport<T>(request, handlers, interceptors);
      case 'long-polling':
        return new LongPollingTransport<T>(request, handlers, interceptors);
      case 'streaming':
        return new StreamingTransport<T>(request, handlers, interceptors);
      default:
        throw new Error(`Unsupported transport: ${transport}`);
    }
  }
}
