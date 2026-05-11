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

import { ref, onUnmounted, type Ref } from 'vue';
import type { SubscriptionHandlers } from '../../types';
import { ConnectionStatus } from '../../resilience';
import type {
  ConnectionStatusOptions,
  ConnectionStatusSnapshot,
} from '../../resilience';

/**
 * Vue composable that tracks a {@link ConnectionStatus} instance and
 * exposes a reactive snapshot. Most callers should just read
 * `connectionStatus` from {@link useAtmosphere} or {@link useStreaming}
 * instead — this composable exists for the lower-level pattern where
 * the caller manages the subscription themselves via
 * {@code atmosphere.subscribe()}.
 *
 * ```vue
 * <script setup lang="ts">
 * import { onMounted, onUnmounted } from 'vue';
 * import { useConnectionStatus } from 'atmosphere.js/vue';
 *
 * const { status, wrap, reset } = useConnectionStatus();
 * let sub;
 * onMounted(async () => {
 *   sub = await atmosphere.subscribe(request, wrap({ message: ... }));
 * });
 * onUnmounted(() => sub?.close());
 * </script>
 *
 * <template>
 *   <ConnectionStatusBadge :status="status" />
 * </template>
 * ```
 */
export function useConnectionStatus(options: ConnectionStatusOptions = {}) {
  const instance = new ConnectionStatus(options);
  const status: Ref<ConnectionStatusSnapshot> = ref(instance.snapshot);
  const unsubscribe = instance.onChange((s) => { status.value = s; });

  onUnmounted(() => unsubscribe());

  return {
    status,
    wrap: <T = unknown>(handlers?: SubscriptionHandlers<T>): SubscriptionHandlers<T> =>
      instance.wrap(handlers),
    reset: () => instance.reset(),
  };
}
