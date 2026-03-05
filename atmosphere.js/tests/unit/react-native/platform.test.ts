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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('setupReactNative', () => {
  let originalEventSource: typeof globalThis.EventSource;
  let originalReadableStream: typeof globalThis.ReadableStream;

  beforeEach(() => {
    originalEventSource = globalThis.EventSource;
    originalReadableStream = globalThis.ReadableStream;
    // Reset module cache so _installed flag is fresh
    vi.resetModules();
  });

  afterEach(() => {
    (globalThis as Record<string, unknown>).EventSource = originalEventSource;
    (globalThis as Record<string, unknown>).ReadableStream = originalReadableStream;
  });

  it('should install EventSource polyfill when EventSource is missing', async () => {
    delete (globalThis as Record<string, unknown>).EventSource;

    const { setupReactNative } = await import('../../../src/react-native/platform');
    const caps = setupReactNative();

    expect(typeof globalThis.EventSource).toBe('function');
    expect(caps.hasWebSocket).toBe(true); // jsdom provides WebSocket
  });

  it('should not overwrite existing EventSource', async () => {
    // Manually set a fake EventSource to simulate a browser or RN env that has one
    const FakeEventSource = class {} as unknown as typeof EventSource;
    (globalThis as Record<string, unknown>).EventSource = FakeEventSource;

    const { setupReactNative } = await import('../../../src/react-native/platform');
    setupReactNative();

    expect(globalThis.EventSource).toBe(FakeEventSource);
  });

  it('should detect ReadableStream support and recommend transports', async () => {
    // ReadableStream is available in our test env (jsdom/node)
    const { setupReactNative } = await import('../../../src/react-native/platform');
    const caps = setupReactNative();

    expect(caps.hasReadableStream).toBe(true);
    expect(caps.recommendedTransports).toContain('websocket');
    expect(caps.recommendedTransports).toContain('streaming');
    expect(caps.recommendedTransports).toContain('long-polling');
  });

  it('should omit streaming transport when ReadableStream is missing', async () => {
    delete (globalThis as Record<string, unknown>).ReadableStream;

    const { setupReactNative } = await import('../../../src/react-native/platform');
    const caps = setupReactNative();

    expect(caps.hasReadableStream).toBe(false);
    expect(caps.recommendedTransports).not.toContain('streaming');
    expect(caps.recommendedTransports).toEqual(['websocket', 'long-polling']);
  });

  it('should report isReactNativeSetup() correctly', async () => {
    const { setupReactNative, isReactNativeSetup } = await import('../../../src/react-native/platform');

    // Fresh module — not yet set up (module was reset)
    expect(isReactNativeSetup()).toBe(false);

    setupReactNative();
    expect(isReactNativeSetup()).toBe(true);
  });
});
