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
  AtmosphereError,
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
import { WebTransportTransport } from '../transports/webtransport';
import { BaseTransport } from '../transports/base';
import { HandlerGate } from './handler-gate';
import { logger } from '../utils/logger';
import { sendCloseMessage } from '../utils/close-message';
import { VERSION } from '../version';

/**
 * Main Atmosphere client class.
 *
 * Manages subscriptions with automatic transport selection and fallback.
 * Default transport: WebSocket. Configure `fallbackTransport` for automatic
 * retry with a different transport on failure.
 */
export class Atmosphere {
  readonly version = VERSION;
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
    let activeTransport: BaseTransport<T> | null = null;

    // When a fallback transport is configured, a failure of the PRIMARY
    // transport during its initial connect is recoverable: the client falls
    // back below and the application never needed to know. The transports
    // (websocket/sse/webtransport) call their error handler on a failed
    // initial connect *and* throw, so without this guard a recovered
    // fallback would still surface a stale "Opening handshake failed" /
    // transport-error banner. Suppress the primary's error callback until it
    // actually opens; afterwards (read-loop errors, drops) errors flow
    // through unchanged. A genuine no-fallback failure still propagates via
    // the thrown primaryError below, so nothing is swallowed silently.
    const canFallBack = Boolean(fallback && fallback !== transport);
    let primaryOpened = false;
    // Events the transport reports before subscribe() resolves (every
    // transport can fire `open` from inside connect()) are held until the
    // caller has the Subscription; see HandlerGate.
    let gate = new HandlerGate<T>(handlers,
      (name) => name === 'error' && canFallBack && !primaryOpened);

    try {
      activeTransport = this.createTransport(mergedRequest, gate.handlers);
      if (mergedRequest.offlineQueue) {
        activeTransport.setOfflineQueue(mergedRequest.offlineQueue);
      }
      await activeTransport.connectWithTimeout();
      primaryOpened = true;
    } catch (primaryError) {
      if (fallback && fallback !== transport) {
        // Nothing the abandoned primary reported, or reports late, reaches the application.
        gate.discard();
        // Ensure the failed primary transport is fully torn down so it
        // cannot linger as an orphan connection alongside the fallback.
        if (activeTransport) {
          await activeTransport.disconnect().catch(() => { /* best-effort */ });
        }

        const reason = primaryError instanceof Error ? primaryError.message : String(primaryError);
        logger.info(`${transport} failed, falling back to ${fallback}`);
        handlers.transportFailure?.(reason, mergedRequest);
        const fallbackRequest = {
          ...mergedRequest,
          transport: fallback,
          resume: activeTransport?.resumePoint ?? mergedRequest.resume,
        };
        gate = new HandlerGate<T>(handlers);
        activeTransport = this.createTransport(fallbackRequest, gate.handlers);
        if (mergedRequest.offlineQueue) {
          activeTransport.setOfflineQueue(mergedRequest.offlineQueue);
        }
        try {
          await activeTransport.connectWithTimeout();
        } catch (fallbackError) {
          gate.flush();
          throw fallbackError;
        }
      } else {
        gate.flush();
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
    handlers.error = ((error: AtmosphereError) => {
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

    // Atmosphere's close message tells the server to release the resource an
    // HTTP transport leaves behind (see BaseTransport.needsCloseMessage). It
    // is sent by close() and, as a beacon, when the page is hidden or
    // unloaded: `pagehide` rather than `unload`, which browsers no longer
    // reliably fire. The beacon is marked going-away — a reload or navigation
    // that may come back, as a WebSocket's 1001 close — so the server tells it
    // from close(), a client that left. Only browsers (window with
    // addEventListener) get the listener; React Native and Node have no page
    // lifecycle.
    const withCredentials = mergedRequest.withCredentials;
    let closed = false;
    let pageHideListener: (() => void) | null = null;
    if (currentTransport.needsCloseMessage && typeof window !== 'undefined'
        && typeof window.addEventListener === 'function') {
      pageHideListener = () => {
        const url = currentTransport.closeMessageUrl(true);
        if (url !== null) {
          sendCloseMessage(url, { beacon: true, withCredentials });
        }
      };
      window.addEventListener('pagehide', pageHideListener);
    }

    const subscription: Subscription = {
      id,
      get uuid(): string {
        return currentTransport.uuid;
      },
      get state(): ConnectionState {
        return currentTransport.state;
      },
      get resumePoint(): string | null {
        return currentTransport.resumePoint;
      },
      push: (message: string | object | ArrayBuffer) => {
        const state = currentTransport.state;
        if (state !== 'connected' && mergedRequest.offlineQueue) {
          mergedRequest.offlineQueue.enqueue(message);
          return;
        }
        const data =
          typeof message === 'string'
            ? message
            : message instanceof ArrayBuffer
              ? message
              : JSON.stringify(message);
        currentTransport.send(data);
      },
      close: async () => {
        if (!closed) {
          closed = true;
          if (pageHideListener !== null) {
            window.removeEventListener('pagehide', pageHideListener);
            pageHideListener = null;
          }
          // Read before disconnect(): the message names the resource the
          // request being aborted belongs to.
          const url = currentTransport.closeMessageUrl();
          if (url !== null) {
            sendCloseMessage(url, { beacon: false, withCredentials });
          }
          // Leaving on purpose ends the run on the server: a reload of this
          // tab must not try to reattach to it.
          currentTransport.forgetRunId();
        }
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
    gate.release(() => currentTransport.state === 'disconnected');
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
      case 'webtransport':
        return new WebTransportTransport<T>(request, handlers, interceptors);
      default:
        throw new Error(`Unsupported transport: ${transport}`);
    }
  }
}
