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
export function useStreaming(
  request: AtmosphereRequest,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();

  const tokens: Ref<string[]> = ref([]);
  const isStreaming: Ref<boolean> = ref(false);
  const progress: Ref<string | null> = ref(null);
  const metadata: Ref<Record<string, unknown>> = ref({});
  const stats: Ref<SessionStats | null> = ref(null);
  const routing: Ref<RoutingInfo> = ref({});
  const error: Ref<string | null> = ref(null);

  const fullText: ComputedRef<string> = computed(() => tokens.value.join(''));

  let handle: StreamingHandle | null = null;

  const connect = async () => {
    try {
      handle = await subscribeStreaming(atmosphere, request, {
        onToken: (token) => {
          isStreaming.value = true;
          tokens.value = [...tokens.value, token];
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
        },
        onMetadata: (key, value) => {
          metadata.value = { ...metadata.value, [key]: value };
          if (key.startsWith('routing.')) {
            const field = key.substring('routing.'.length);
            routing.value = { ...routing.value, [field]: value };
          }
        },
        onSessionComplete: (s, r) => {
          stats.value = s;
          routing.value = r;
        },
      });
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err);
    }
  };

  const send = (message: string | object, options?: SendOptions) => {
    isStreaming.value = true;
    error.value = null;
    handle?.send(message, options);
  };

  const reset = () => {
    tokens.value = [];
    isStreaming.value = false;
    progress.value = null;
    metadata.value = {};
    stats.value = null;
    routing.value = {};
    error.value = null;
  };

  connect();

  onUnmounted(() => {
    handle?.close();
  });

  return { fullText, tokens, isStreaming, progress, metadata, stats, routing, error, send, reset };
}
