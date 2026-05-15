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

import { ref, computed, onUnmounted, type Ref, type ComputedRef } from 'vue';
import type { AtmosphereRequest } from '../../types';
import type { StreamingHandle, SessionStats, RoutingInfo, SendOptions } from '../../streaming/types';
import { subscribeStreaming } from '../../streaming';
import { Atmosphere } from '../../core/atmosphere';
import { ConnectionStatus } from '../../resilience';
import type { ConnectionStatusSnapshot } from '../../resilience';

/**
 * Vue composable for AI/LLM streaming via Atmosphere.
 *
 * ```vue
 * <script setup lang="ts">
 * import { useStreaming } from 'atmosphere.js/vue';
 *
 * const { fullText, isStreaming, send } = useStreaming(
 *   { url: '/ai/chat', transport: 'websocket' },
 * );
 * </script>
 *
 * <template>
 *   <button @click="send('What is Atmosphere?')">Ask</button>
 *   <p>{{ fullText }}</p>
 * </template>
 * ```
 */
/**
 * Optional lifecycle callbacks paired with the server-side @AiEndpoint
 * resilience primitives (disconnect cancellation, streamCache replay,
 * heartbeat). Identical surface to the React hook for parity.
 */
export interface VueStreamingLifecycle {
  onOpen?: () => void;
  onClose?: () => void;
  onReconnect?: () => void;
  onClientTimeout?: () => void;
  /** Called when the connection is re-established after a disconnect. */
  onReopen?: () => void;
  /** Called when the primary transport fails and a fallback is attempted. */
  onTransportFailure?: (reason: string) => void;
  /** Called when reconnect attempts have been exhausted. */
  onFailureToReconnect?: () => void;
}

/** Connection-state classification surfaced to consumers. */
export type StreamingConnectionState =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'closed'
  | 'error';

export interface VueAiEvent {
  event: string;
  data: Record<string, unknown>;
}

export function useStreaming(
  request: AtmosphereRequest,
  instance?: Atmosphere,
  lifecycle?: VueStreamingLifecycle,
) {
  const atmosphere = instance ?? new Atmosphere();

  const streamingTexts: Ref<string[]> = ref([]);
  const isStreaming: Ref<boolean> = ref(false);
  const progress: Ref<string | null> = ref(null);
  const metadata: Ref<Record<string, unknown>> = ref({});
  const stats: Ref<SessionStats | null> = ref(null);
  const routing: Ref<RoutingInfo> = ref({});
  const aiEvents: Ref<VueAiEvent[]> = ref([]);
  const error: Ref<string | null> = ref(null);
  const connectionState: Ref<StreamingConnectionState> = ref('idle');
  const isReconnecting: ComputedRef<boolean> = computed(
    () => connectionState.value === 'reconnecting',
  );

  const fullText: ComputedRef<string> = computed(() => streamingTexts.value.join(''));

  // Seed ConnectionStatus; replaced once subscribeStreaming returns the
  // handle's own instance (so events from the transport drive the snapshot).
  let statusInstance: ConnectionStatus = new ConnectionStatus({ initialTransport: request.transport });
  const connectionStatus: Ref<ConnectionStatusSnapshot> = ref(statusInstance.snapshot);
  let unsubscribeStatus: () => void = statusInstance.onChange((s) => { connectionStatus.value = s; });

  let handle: StreamingHandle | null = null;

  const connect = async () => {
    connectionState.value = 'connecting';
    try {
      handle = await subscribeStreaming(atmosphere, request, {
        onOpen: () => {
          connectionState.value = 'connected';
          lifecycle?.onOpen?.();
        },
        onClose: () => {
          connectionState.value = 'closed';
          lifecycle?.onClose?.();
        },
        onReconnect: () => {
          connectionState.value = 'reconnecting';
          lifecycle?.onReconnect?.();
        },
        onReopen: () => {
          connectionState.value = 'connected';
          lifecycle?.onReopen?.();
        },
        onClientTimeout: () => {
          connectionState.value = 'reconnecting';
          lifecycle?.onClientTimeout?.();
        },
        onTransportFailure: (reason: string) => {
          lifecycle?.onTransportFailure?.(reason);
        },
        onFailureToReconnect: () => {
          connectionState.value = 'error';
          lifecycle?.onFailureToReconnect?.();
        },
        onStreamingText: (text) => {
          isStreaming.value = true;
          streamingTexts.value = [...streamingTexts.value, text];
        },
        onProgress: (msg) => {
          progress.value = msg;
        },
        onComplete: () => {
          isStreaming.value = false;
        },
        onError: (err) => {
          error.value = err;
          isStreaming.value = false;
          connectionState.value = 'error';
        },
        onMetadata: (key, value) => {
          metadata.value = { ...metadata.value, [key]: value };
          if (key.startsWith('routing.')) {
            const field = key.substring('routing.'.length);
            routing.value = { ...routing.value, [field]: value };
          }
        },
        onAiEvent: (event, data) => {
          aiEvents.value = [...aiEvents.value, { event, data }];
        },
        onSessionComplete: (s, r) => {
          stats.value = s;
          routing.value = r;
        },
      });
      // Adopt the handle's ConnectionStatus so the snapshot reflects
      // real transport-layer events.
      unsubscribeStatus();
      statusInstance = handle.connectionStatus;
      connectionStatus.value = statusInstance.snapshot;
      unsubscribeStatus = statusInstance.onChange((s) => { connectionStatus.value = s; });
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err);
      connectionState.value = 'error';
    }
  };

  const send = (message: string | object, options?: SendOptions) => {
    isStreaming.value = true;
    error.value = null;
    handle?.send(message, options);
  };

  const reset = () => {
    streamingTexts.value = [];
    isStreaming.value = false;
    progress.value = null;
    metadata.value = {};
    stats.value = null;
    routing.value = {};
    aiEvents.value = [];
    error.value = null;
  };

  const close = () => {
    handle?.close();
    isStreaming.value = false;
  };

  connect();

  onUnmounted(() => {
    unsubscribeStatus();
    handle?.close();
  });

  return {
    fullText, streamingTexts, isStreaming, progress, metadata, stats, routing, aiEvents, error,
    send, reset, close, connectionState, isReconnecting, connectionStatus,
  };
}
