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

import { ref, onUnmounted, type Ref, type ShallowRef, shallowRef } from 'vue';
import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
} from '../../types';
import { Atmosphere } from '../../core/atmosphere';
import { ConnectionStatus } from '../../resilience';
import type { ConnectionStatusSnapshot } from '../../resilience';

/**
 * Optional lifecycle callbacks paired with the server-side
 * resilience primitives. Full classic Atmosphere 3.x surface so
 * consumers can show transport-failure, reopen, and exhaustion
 * indicators without inspecting the subscription themselves.
 */
export interface VueAtmosphereLifecycle<T = unknown> {
  onOpen?: () => void;
  onMessage?: (data: T) => void;
  onClose?: () => void;
  onError?: (err: Error) => void;
  onReconnect?: () => void;
  onReopen?: () => void;
  onTransportFailure?: (reason: string) => void;
  onClientTimeout?: () => void;
  onFailureToReconnect?: () => void;
}

/**
 * Vue composable that manages an Atmosphere subscription lifecycle.
 *
 * Connects on call, disconnects when the component is unmounted.
 * Exposes a reactive `connectionStatus` ref containing the unified
 * resilience snapshot (phase + last event + transport + attempt counter).
 *
 * ```vue
 * <script setup lang="ts">
 * import { useAtmosphere, ConnectionStatusBadge } from 'atmosphere.js/vue';
 *
 * const { data, connectionStatus, push } = useAtmosphere<ChatMessage>({
 *   url: '/chat',
 *   transport: 'websocket',
 *   fallbackTransport: 'sse',
 * });
 * </script>
 *
 * <template>
 *   <ConnectionStatusBadge :status="connectionStatus" />
 * </template>
 * ```
 */
export function useAtmosphere<T = unknown>(
  request: AtmosphereRequest,
  instance?: Atmosphere,
  lifecycle?: VueAtmosphereLifecycle<T>,
) {
  const atmosphere = instance ?? new Atmosphere();
  const state: Ref<ConnectionState> = ref('disconnected');
  const data: Ref<T | null> = ref(null) as Ref<T | null>;
  const error: Ref<Error | null> = ref(null);
  const subscription: ShallowRef<Subscription | null> = shallowRef(null);

  const status = new ConnectionStatus({ initialTransport: request.transport });
  const connectionStatus: Ref<ConnectionStatusSnapshot> = ref(status.snapshot);
  const unsubscribeStatus = status.onChange((s) => { connectionStatus.value = s; });

  const connect = async () => {
    try {
      const sub = await atmosphere.subscribe<T>(request, status.wrap({
        open: () => {
          state.value = 'connected';
          lifecycle?.onOpen?.();
        },
        message: (response) => {
          state.value = 'connected';
          data.value = response.responseBody;
          lifecycle?.onMessage?.(response.responseBody);
        },
        close: () => {
          state.value = 'closed';
          lifecycle?.onClose?.();
        },
        error: (err) => {
          state.value = 'error';
          error.value = err;
          lifecycle?.onError?.(err);
        },
        reconnect: () => {
          state.value = 'reconnecting';
          lifecycle?.onReconnect?.();
        },
        reopen: () => {
          state.value = 'connected';
          lifecycle?.onReopen?.();
        },
        transportFailure: (reason) => {
          lifecycle?.onTransportFailure?.(reason);
        },
        clientTimeout: () => {
          lifecycle?.onClientTimeout?.();
        },
        failureToReconnect: () => {
          state.value = 'error';
          lifecycle?.onFailureToReconnect?.();
        },
      }));
      subscription.value = sub;
      state.value = sub.state;
    } catch (err) {
      state.value = 'error';
      error.value = err instanceof Error ? err : new Error(String(err));
    }
  };

  const push = (message: string | object | ArrayBuffer) => {
    subscription.value?.push(message);
  };

  connect();

  onUnmounted(() => {
    unsubscribeStatus();
    subscription.value?.close();
    subscription.value = null;
  });

  return { subscription, state, data, error, push, connectionStatus };
}
