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

import {
  createContext,
  useContext,
  createElement,
  type ReactNode,
} from 'react';
import { Atmosphere } from '../../core/atmosphere';
import type { AtmosphereConfig } from '../../types';

/**
 * Context holding a shared Atmosphere client instance.
 */
const AtmosphereContext = createContext<Atmosphere | null>(null);

/**
 * Props for {@link AtmosphereProvider}.
 */
export interface AtmosphereProviderProps {
  /** Optional pre-built Atmosphere instance. A default one is created if omitted. */
  instance?: Atmosphere;
  /** Configuration used when creating a default instance (ignored if `instance` is provided). */
  config?: AtmosphereConfig;
  children: ReactNode;
}

/**
 * Provides a shared {@link Atmosphere} client to the React tree.
 *
 * ```tsx
 * <AtmosphereProvider config={{ logLevel: 'info' }}>
 *   <App />
 * </AtmosphereProvider>
 * ```
 */
export function AtmosphereProvider({
  instance,
  config,
  children,
}: AtmosphereProviderProps) {
  const client = instance ?? new Atmosphere(config);
  return createElement(AtmosphereContext.Provider, { value: client }, children);
}

/**
 * Returns the Atmosphere instance from the nearest {@link AtmosphereProvider}.
 * Throws if used outside a provider.
 */
export function useAtmosphereContext(): Atmosphere {
  const ctx = useContext(AtmosphereContext);
  if (!ctx) {
    throw new Error(
      'useAtmosphereContext must be used within an <AtmosphereProvider>',
    );
  }
  return ctx;
}
