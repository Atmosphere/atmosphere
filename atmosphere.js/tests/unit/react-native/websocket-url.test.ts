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
import { WebSocketTransport } from '../../../src/transports/websocket';
import type { AtmosphereRequest } from '../../../src/types';

// Access the private buildWebSocketUrl method for testing
function getBuildUrl(transport: WebSocketTransport): (url: string) => string {
  return (transport as unknown as { buildWebSocketUrl: (url: string) => string }).buildWebSocketUrl.bind(transport);
}

describe('WebSocket URL building in non-browser environments', () => {
  let originalWindow: typeof globalThis.window;

  beforeEach(() => {
    originalWindow = globalThis.window;
  });

  afterEach(() => {
    // Restore window
    if (originalWindow !== undefined) {
      globalThis.window = originalWindow;
    }
  });

  it('should accept absolute HTTP URLs and convert to WS', () => {
    const request: AtmosphereRequest = {
      url: 'https://example.com/chat',
      transport: 'websocket',
    };

    const transport = new WebSocketTransport(request, {});
    const buildUrl = getBuildUrl(transport);
    const result = buildUrl(request.url);

    expect(result).toMatch(/^wss:\/\/example\.com\/chat/);
  });

  it('should accept absolute WS URLs', () => {
    const request: AtmosphereRequest = {
      url: 'wss://example.com/chat',
      transport: 'websocket',
    };

    const transport = new WebSocketTransport(request, {});
    const buildUrl = getBuildUrl(transport);
    const result = buildUrl(request.url);

    expect(result).toMatch(/^wss:\/\/example\.com\/chat/);
  });

  it('should resolve relative URLs when window.location is available', () => {
    // jsdom provides window.location — relative URLs should resolve
    const request: AtmosphereRequest = {
      url: '/chat',
      transport: 'websocket',
    };

    const transport = new WebSocketTransport(request, {});
    const buildUrl = getBuildUrl(transport);
    const result = buildUrl(request.url);

    // Should produce a ws:// or wss:// URL
    expect(result).toMatch(/^wss?:\/\//);
  });

  it('should throw a clear error for relative URLs without window.location', () => {
    // Simulate React Native environment: no window.location
    const savedLocation = window.location;
    // @ts-expect-error - deliberately removing location for test
    delete window.location;

    try {
      const request: AtmosphereRequest = {
        url: '/chat',
        transport: 'websocket',
      };

      const transport = new WebSocketTransport(request, {});
      const buildUrl = getBuildUrl(transport);

      expect(() => buildUrl(request.url)).toThrow(
        /absolute URL/i,
      );
    } finally {
      window.location = savedLocation;
    }
  });
});
