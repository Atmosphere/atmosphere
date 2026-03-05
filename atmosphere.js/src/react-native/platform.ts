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

import { FetchEventSource } from './event-source-polyfill';
import type { TransportType } from '../types';

/**
 * Capability report returned by {@link setupReactNative}.
 */
export interface RNCapabilities {
  hasReadableStream: boolean;
  hasWebSocket: boolean;
  recommendedTransports: TransportType[];
}

/**
 * Options for {@link setupReactNative}.
 */
export interface SetupReactNativeOptions {
  /**
   * Pass the NetInfo module for network-aware reconnection.
   * Import it in your app code where Metro can resolve it:
   *
   * ```ts
   * import NetInfo from '@react-native-community/netinfo';
   * setupReactNative({ netInfo: NetInfo });
   * ```
   */
  netInfo?: {
    addEventListener(listener: (state: { isConnected: boolean | null; isInternetReachable: boolean | null }) => void): () => void;
  };
}

let _installed = false;
let _netInfoModule: SetupReactNativeOptions['netInfo'] | null = null;

/** Returns the NetInfo module if provided via setupReactNative(). */
export function getRegisteredNetInfo(): SetupReactNativeOptions['netInfo'] | null {
  return _netInfoModule;
}

/**
 * Prepare the atmosphere.js runtime for React Native / Expo.
 *
 * Call this once at app startup (e.g. in your root `App.tsx`) before
 * any Atmosphere subscriptions are created.
 *
 * ```ts
 * import { setupReactNative } from 'atmosphere.js/react-native';
 *
 * const caps = setupReactNative();
 * console.log('Recommended transports:', caps.recommendedTransports);
 * ```
 */
export function setupReactNative(options?: SetupReactNativeOptions): RNCapabilities {
  if (options?.netInfo) {
    _netInfoModule = options.netInfo;
  }
  const hasWebSocket = typeof WebSocket !== 'undefined';
  const hasFetch = typeof fetch !== 'undefined';
  const hasAbortController = typeof AbortController !== 'undefined';

  if (!hasFetch) {
    console.warn('[atmosphere.js] fetch is not available — network transports will not work');
  }
  if (!hasAbortController) {
    console.warn('[atmosphere.js] AbortController is not available — SSE polyfill cannot be cancelled');
  }

  // Detect ReadableStream support (Hermes on RN 0.73+ / Expo SDK 50+)
  const hasReadableStream = typeof ReadableStream !== 'undefined';
  if (!hasReadableStream) {
    console.warn(
      '[atmosphere.js] ReadableStream not available (older Hermes/RN). ' +
      'SSE polyfill will use text-accumulation fallback. ' +
      'Streaming transport will not work. Consider upgrading to RN 0.73+ / Expo SDK 50+.',
    );
  }

  // Install EventSource polyfill if native EventSource is missing
  if (typeof globalThis.EventSource === 'undefined') {
    (globalThis as Record<string, unknown>).EventSource = FetchEventSource;
    if (!_installed) {
      console.info('[atmosphere.js] Installed fetch-based EventSource polyfill');
    }
  }

  _installed = true;

  // Build recommended transport chain
  const recommendedTransports: TransportType[] = ['websocket'];
  if (hasReadableStream) {
    recommendedTransports.push('streaming');
  }
  recommendedTransports.push('long-polling');

  return { hasReadableStream, hasWebSocket, recommendedTransports };
}

/**
 * Returns true if {@link setupReactNative} has been called.
 */
export function isReactNativeSetup(): boolean {
  return _installed;
}
