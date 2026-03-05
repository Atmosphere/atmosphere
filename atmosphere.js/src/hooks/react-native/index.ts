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

// RN-specific platform setup
export { setupReactNative, isReactNativeSetup } from '../../react-native/platform';
export type { RNCapabilities, SetupReactNativeOptions } from '../../react-native/platform';

// RN-aware hooks
export { useAtmosphereRN } from './useAtmosphereRN';
export type { UseAtmosphereRNOptions, UseAtmosphereRNResult, BackgroundBehavior } from './useAtmosphereRN';

export { useStreamingRN } from './useStreamingRN';
export type { UseStreamingRNOptions, UseStreamingRNResult } from './useStreamingRN';

// Re-export core hooks that work as-is in RN
export { AtmosphereProvider, useAtmosphereContext } from '../react/provider';
export { useRoom } from '../react/useRoom';
export { usePresence } from '../react/usePresence';
