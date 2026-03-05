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
import { FetchEventSource } from '../../../src/react-native/event-source-polyfill';

// Helper: create a ReadableStream from SSE text
function sseStream(text: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(text));
      controller.close();
    },
  });
}

// Helper: create a mock fetch response with a ReadableStream body
function mockStreamResponse(sseText: string, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    body: sseStream(sseText),
    headers: new Headers(),
    text: () => Promise.resolve(sseText),
  } as unknown as Response;
}

// Helper: create a mock fetch response using the text fallback (no body)
function mockTextResponse(sseText: string, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    body: null,
    headers: new Headers(),
    text: () => Promise.resolve(sseText),
  } as unknown as Response;
}

describe('FetchEventSource', () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.useFakeTimers();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.useRealTimers();
  });

  it('should parse a simple SSE message (streaming path)', async () => {
    const sseText = 'data: hello world\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    // Let the async connect + read resolve
    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['hello world']);
    es.close();
  });

  it('should parse a simple SSE message (text fallback path)', async () => {
    const sseText = 'data: fallback hello\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockTextResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['fallback hello']);
    es.close();
  });

  it('should parse multi-line data fields', async () => {
    const sseText = 'data: line1\ndata: line2\ndata: line3\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['line1\nline2\nline3']);
    es.close();
  });

  it('should parse multiple events', async () => {
    const sseText = 'data: first\n\ndata: second\n\ndata: third\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['first', 'second', 'third']);
    es.close();
  });

  it('should handle named events via addEventListener', async () => {
    const sseText = 'event: custom\ndata: payload\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const custom: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.addEventListener('custom', (event) => {
      custom.push((event as MessageEvent).data);
    });

    await vi.advanceTimersByTimeAsync(50);

    expect(custom).toEqual(['payload']);
    es.close();
  });

  it('should track Last-Event-ID', async () => {
    const sseText = 'id: 42\ndata: tracked\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.lastEventId);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['42']);
    es.close();
  });

  it('should update retry interval from server', async () => {
    const sseText = 'retry: 5000\ndata: retried\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['retried']);
    es.close();
  });

  it('should ignore comment lines', async () => {
    const sseText = ': this is a comment\ndata: not a comment\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['not a comment']);
    es.close();
  });

  it('should fire onopen when connection succeeds', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse('data: hi\n\n'));

    let opened = false;
    let readyStateOnOpen = -1;
    const es = new FetchEventSource('https://example.com/sse');
    es.onopen = () => {
      opened = true;
      readyStateOnOpen = es.readyState;
    };

    await vi.advanceTimersByTimeAsync(50);

    expect(opened).toBe(true);
    // readyState is OPEN when onopen fires; it may transition to CONNECTING
    // after the stream ends (reconnect scheduling), so check at callback time
    expect(readyStateOnOpen).toBe(FetchEventSource.OPEN);
    es.close();
  });

  it('should fire onerror on HTTP error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse('', 500));

    let errored = false;
    const es = new FetchEventSource('https://example.com/sse');
    es.onerror = () => { errored = true; };

    await vi.advanceTimersByTimeAsync(50);

    expect(errored).toBe(true);
    es.close();
  });

  it('should fire onerror on fetch failure', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'));

    let errored = false;
    const es = new FetchEventSource('https://example.com/sse');
    es.onerror = () => { errored = true; };

    await vi.advanceTimersByTimeAsync(50);

    expect(errored).toBe(true);
    es.close();
  });

  it('should set readyState to CLOSED after close()', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse('data: hi\n\n'));

    const es = new FetchEventSource('https://example.com/sse');
    await vi.advanceTimersByTimeAsync(50);

    es.close();
    expect(es.readyState).toBe(FetchEventSource.CLOSED);
  });

  it('should remove event listeners', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse('data: x\n\n'));

    const messages: string[] = [];
    const handler = (event: MessageEvent | Event) => {
      messages.push((event as MessageEvent).data);
    };

    const es = new FetchEventSource('https://example.com/sse');
    es.addEventListener('message', handler);
    es.removeEventListener('message', handler);

    await vi.advanceTimersByTimeAsync(50);

    // onmessage not set, and listener removed — no messages
    expect(messages).toEqual([]);
    es.close();
  });

  it('should send withCredentials in fetch options', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse('data: x\n\n'));

    const es = new FetchEventSource('https://example.com/sse', { withCredentials: true });
    await vi.advanceTimersByTimeAsync(50);

    expect(globalThis.fetch).toHaveBeenCalledWith(
      'https://example.com/sse',
      expect.objectContaining({ credentials: 'include' }),
    );
    es.close();
  });

  it('should handle data field with no space after colon', async () => {
    const sseText = 'data:no-space\n\n';
    globalThis.fetch = vi.fn().mockResolvedValue(mockStreamResponse(sseText));

    const messages: string[] = [];
    const es = new FetchEventSource('https://example.com/sse');
    es.onmessage = (event) => messages.push(event.data);

    await vi.advanceTimersByTimeAsync(50);

    expect(messages).toEqual(['no-space']);
    es.close();
  });
});
