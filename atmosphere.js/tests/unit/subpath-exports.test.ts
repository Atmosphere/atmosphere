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

import { describe, it, expect } from 'vitest';

/**
 * Tests that the subpath entry points (atmosphere.js/room, atmosphere.js/streaming,
 * atmosphere.js/queue) re-export the same symbols as the main entry point.
 *
 * These tests import from the entry-point source files directly (not dist/)
 * so they work without a build step.
 */

describe('atmosphere.js/room subpath export', () => {
  it('exports AtmosphereRooms class', async () => {
    const room = await import('../../src/room');
    expect(room.AtmosphereRooms).toBeDefined();
    expect(typeof room.AtmosphereRooms).toBe('function');
  });

  it('exports the same AtmosphereRooms as main entry', async () => {
    const room = await import('../../src/room');
    const main = await import('../../src/index');
    expect(room.AtmosphereRooms).toBe(main.AtmosphereRooms);
  });
});

describe('atmosphere.js/streaming subpath export', () => {
  it('exports subscribeStreaming function', async () => {
    const streaming = await import('../../src/streaming-entry');
    expect(streaming.subscribeStreaming).toBeDefined();
    expect(typeof streaming.subscribeStreaming).toBe('function');
  });

  it('exports the same subscribeStreaming as main entry', async () => {
    const streaming = await import('../../src/streaming-entry');
    const main = await import('../../src/index');
    expect(streaming.subscribeStreaming).toBe(main.subscribeStreaming);
  });
});

describe('atmosphere.js/queue subpath export', () => {
  it('exports OfflineQueue class', async () => {
    const queue = await import('../../src/queue');
    expect(queue.OfflineQueue).toBeDefined();
    expect(typeof queue.OfflineQueue).toBe('function');
  });

  it('exports the same OfflineQueue as main entry', async () => {
    const queue = await import('../../src/queue');
    const main = await import('../../src/index');
    expect(queue.OfflineQueue).toBe(main.OfflineQueue);
  });

  it('OfflineQueue works when imported from subpath', async () => {
    const { OfflineQueue } = await import('../../src/queue');
    const q = new OfflineQueue({ maxSize: 10 });
    const tracked = q.enqueue('test message');
    expect(tracked.id).toBeDefined();
    expect(tracked.state).toBe('pending');
    expect(q.size).toBe(1);
    q.clear();
    expect(q.size).toBe(0);
  });
});
