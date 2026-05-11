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

import { useEffect, useMemo, useState } from 'react';
import type { SubscriptionHandlers } from '../../types';
import { ConnectionStatus } from '../../resilience';
import type {
  ConnectionStatusOptions,
  ConnectionStatusSnapshot,
} from '../../resilience';

/**
 * Return type of {@link useConnectionStatus}.
 */
export interface UseConnectionStatusResult {
  /** Reactive snapshot — re-renders the component on every lifecycle event. */
  status: ConnectionStatusSnapshot;
  /**
   * Handlers to spread into your {@code atmosphere.subscribe()} call.
   * Mixes with any handlers you already pass — yours are preserved.
   *
   * ```ts
   * const { status, wrap } = useConnectionStatus();
   * const sub = await atmosphere.subscribe(request, wrap({ message: m => ... }));
   * ```
   */
  wrap: <T = unknown>(handlers?: SubscriptionHandlers<T>) => SubscriptionHandlers<T>;
  /** Reset the status back to idle (use after closing a subscription). */
  reset: () => void;
}

/**
 * React hook that tracks a {@link ConnectionStatus} instance and exposes
 * a reactive snapshot. Most callers should just read `connectionStatus`
 * from {@link useAtmosphere} or {@link useStreaming} instead — this hook
 * exists for the lower-level pattern where the caller manages the
 * subscription themselves via {@code atmosphere.subscribe()}.
 *
 * ```tsx
 * const { status, wrap } = useConnectionStatus();
 * useEffect(() => {
 *   let sub: Subscription;
 *   (async () => {
 *     sub = await atmosphere.subscribe(request, wrap({ message: ... }));
 *   })();
 *   return () => { sub?.close(); };
 * }, []);
 *
 * return <ConnectionStatusBadge status={status} />;
 * ```
 */
export function useConnectionStatus(
  options: ConnectionStatusOptions = {},
): UseConnectionStatusResult {
  // One instance per hook lifetime.
  const instance = useMemo(() => new ConnectionStatus(options), [
    options.initialTransport, // recreate if caller swaps starting transport
  ]);

  const [status, setStatus] = useState<ConnectionStatusSnapshot>(instance.snapshot);

  useEffect(() => instance.onChange(setStatus), [instance]);

  return useMemo(
    () => ({
      status,
      wrap: instance.wrap.bind(instance),
      reset: instance.reset.bind(instance),
    }),
    [status, instance],
  );
}
