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

/**
 * Vue composable that manages an Atmosphere subscription lifecycle.
 *
 * Connects on call, disconnects when the component is unmounted.
 *
 * ```vue
 * <script setup lang="ts">
 * import { useAtmosphere } from 'atmosphere.js/vue';
 *
 * const { data, state, push } = useAtmosphere<ChatMessage>({
 *   url: '/chat',
 *   transport: 'websocket',
 * });
 * </script>
 * ```
 */
export function useAtmosphere<T = unknown>(
  request: AtmosphereRequest,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();
  const state: Ref<ConnectionState> = ref('disconnected');
  const data: Ref<T | null> = ref(null) as Ref<T | null>;
  const error: Ref<Error | null> = ref(null);
  const subscription: ShallowRef<Subscription | null> = shallowRef(null);

  const connect = async () => {
    try {
      const sub = await atmosphere.subscribe<T>(request, {
        open: () => { state.value = 'connected'; },
        message: (response) => {
          state.value = 'connected';
          data.value = response.responseBody;
        },
        close: () => { state.value = 'closed'; },
        error: (err) => {
          state.value = 'error';
          error.value = err;
        },
        reconnect: () => { state.value = 'reconnecting'; },
      });
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
    subscription.value?.close();
    subscription.value = null;
  });

  return { subscription, state, data, error, push };
}
