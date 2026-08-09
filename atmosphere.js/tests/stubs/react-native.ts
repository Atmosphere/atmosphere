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

/**
 * Minimal stand-in for the `react-native` module, aliased in by
 * `vitest.config.ts`.
 *
 * `react-native` is an optional peer dependency and is not installed in
 * this package, so anything under `src/hooks/react-native/` was previously
 * untestable — the RN entry could only be checked by reading its source as
 * text. This stub supplies exactly the surface that entry imports
 * (`AppState`, `View`, `Text`, `StyleSheet`), letting the real hooks run
 * under jsdom.
 *
 * It is a test double, not a React Native emulation: `View`/`Text` are host
 * strings that jsdom happily renders as unknown elements, which is enough
 * for hook-level assertions. Anything asserting native layout or behaviour
 * belongs in the Expo client, not here.
 */

type AppStateListener = (state: string) => void;

const appStateListeners = new Set<AppStateListener>();

export const AppState = {
  currentState: 'active' as string,
  addEventListener(_event: string, listener: AppStateListener): { remove: () => void } {
    appStateListeners.add(listener);
    return {
      remove: () => {
        appStateListeners.delete(listener);
      },
    };
  },
};

/** Test-only helper: drive an AppState transition. */
export function __emitAppState(next: string): void {
  AppState.currentState = next;
  for (const listener of [...appStateListeners]) listener(next);
}

export const View = 'rn-view';
export const Text = 'rn-text';

export const StyleSheet = {
  create<T extends Record<string, unknown>>(styles: T): T {
    return styles;
  },
  flatten<T>(style: T): T {
    return style;
  },
  absoluteFill: {} as Record<string, unknown>,
  hairlineWidth: 1,
};

export type ViewStyle = Record<string, unknown>;
export type TextStyle = Record<string, unknown>;
