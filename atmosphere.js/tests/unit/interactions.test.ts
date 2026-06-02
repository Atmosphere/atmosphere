import { describe, it, expect, vi } from 'vitest';
import {
  InteractionsClient,
  InteractionsError,
  isTerminal,
  parseFrames,
  type Interaction,
} from '../../src/interactions/client';

/**
 * Client-side coverage for the Atmosphere Interactions API client.
 * Server-side coverage lives in modules/interactions (InteractionServiceSyncTest,
 * BackgroundInteractionTest) and modules/spring-boot-starter (InteractionsEndpointAuthzTest).
 */

function interaction(over: Partial<Interaction> = {}): Interaction {
  return {
    id: 'int-1',
    parentId: null,
    conversationId: 'conv-1',
    agentId: null,
    userId: 'demo-user',
    model: 'gemini-2.5-flash',
    status: 'COMPLETED',
    background: true,
    store: true,
    steps: [],
    finalText: 'hi',
    usage: null,
    errorMessage: null,
    createdAt: '2026-06-02T00:00:00Z',
    updatedAt: '2026-06-02T00:00:01Z',
    ...over,
  };
}

function jsonResponse(body: unknown, init: { ok?: boolean; status?: number } = {}): Response {
  const status = init.status ?? 200;
  return {
    ok: init.ok ?? (status >= 200 && status < 300),
    status,
    statusText: 'STATUS',
    json: async () => body,
  } as unknown as Response;
}

interface Call {
  url: string;
  init: RequestInit;
}

function recordingFetch(handler: (call: Call) => Response) {
  const calls: Call[] = [];
  const fn = vi.fn(async (url: unknown, init: unknown) => {
    const call = { url: String(url), init: (init ?? {}) as RequestInit };
    calls.push(call);
    return handler(call);
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

describe('InteractionsClient', () => {
  it('creates an interaction with a JSON body and parses the result', async () => {
    const { fn, calls } = recordingFetch(() => jsonResponse(interaction({ status: 'RUNNING' })));
    const client = new InteractionsClient({ fetch: fn });

    const result = await client.create({ message: 'go', background: true });

    expect(result.status).toBe('RUNNING');
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('/api/interactions');
    expect(calls[0].init.method).toBe('POST');
    expect(JSON.parse(calls[0].init.body as string)).toEqual({ message: 'go', background: true });
    expect((calls[0].init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('routes continue to /{id}/continue', async () => {
    const { fn, calls } = recordingFetch(() => jsonResponse(interaction({ id: 'int-2', parentId: 'int-1' })));
    const client = new InteractionsClient({ fetch: fn });

    const result = await client.continue('int-1', { message: 'more' });

    expect(result.parentId).toBe('int-1');
    expect(calls[0].url).toBe('/api/interactions/int-1/continue');
    expect(calls[0].init.method).toBe('POST');
  });

  it('gets a single interaction', async () => {
    const { fn, calls } = recordingFetch(() => jsonResponse(interaction()));
    const client = new InteractionsClient({ fetch: fn });

    await client.get('int-1');

    expect(calls[0].url).toBe('/api/interactions/int-1');
    expect(calls[0].init.method).toBe('GET');
  });

  it('lists with an optional conversationId query', async () => {
    const { fn, calls } = recordingFetch(() => jsonResponse([interaction()]));
    const client = new InteractionsClient({ fetch: fn });

    const all = await client.list();
    expect(all).toHaveLength(1);
    expect(calls[0].url).toBe('/api/interactions');

    await client.list({ conversationId: 'conv-9' });
    expect(calls[1].url).toBe('/api/interactions?conversationId=conv-9');
  });

  it('cancel returns the ok flag', async () => {
    const { fn } = recordingFetch(() => jsonResponse({ status: 'cancel requested' }, { ok: true }));
    const client = new InteractionsClient({ fetch: fn });
    expect(await client.cancel('int-1')).toBe(true);

    const fail = recordingFetch(() => jsonResponse({}, { ok: false, status: 404 }));
    const client2 = new InteractionsClient({ fetch: fail.fn });
    expect(await client2.cancel('int-x')).toBe(false);
  });

  it('throws InteractionsError with the server error message and status', async () => {
    const { fn } = recordingFetch(() => jsonResponse({ error: 'Invalid interaction id' }, { ok: false, status: 400 }));
    const client = new InteractionsClient({ fetch: fn });

    await expect(client.get('../evil')).rejects.toMatchObject({
      name: 'InteractionsError',
      message: 'Invalid interaction id',
      status: 400,
    });
  });

  it('applies baseUrl, basePath, and custom headers', async () => {
    const { fn, calls } = recordingFetch(() => jsonResponse(interaction()));
    const client = new InteractionsClient({
      fetch: fn,
      baseUrl: 'https://host:8080/',
      basePath: '/api/interactions',
      headers: { Authorization: 'Bearer t' },
    });

    await client.get('int-1');

    expect(calls[0].url).toBe('https://host:8080/api/interactions/int-1');
    expect((calls[0].init.headers as Record<string, string>).Authorization).toBe('Bearer t');
  });

  it('pollUntilTerminal polls until terminal and reports each update', async () => {
    const states: Interaction['status'][] = ['RUNNING', 'RUNNING', 'COMPLETED'];
    let i = 0;
    const { fn, calls } = recordingFetch(() =>
      jsonResponse(interaction({ status: states[Math.min(i++, states.length - 1)] })),
    );
    const client = new InteractionsClient({ fetch: fn });
    const seen: string[] = [];

    const final = await client.pollUntilTerminal('int-1', {
      intervalMs: 1,
      onUpdate: (r) => seen.push(r.status),
    });

    expect(final.status).toBe('COMPLETED');
    expect(seen).toEqual(['RUNNING', 'RUNNING', 'COMPLETED']);
    expect(calls).toHaveLength(3);
  });

  it('pollUntilTerminal rejects on timeout', async () => {
    const { fn } = recordingFetch(() => jsonResponse(interaction({ status: 'RUNNING' })));
    const client = new InteractionsClient({ fetch: fn });

    await expect(
      client.pollUntilTerminal('int-1', { intervalMs: 5, timeoutMs: 1 }),
    ).rejects.toBeInstanceOf(InteractionsError);
  });

  it('watch yields each poll and ends on the terminal record', async () => {
    const states: Interaction['status'][] = ['RUNNING', 'COMPLETED'];
    let i = 0;
    const { fn } = recordingFetch(() =>
      jsonResponse(interaction({ status: states[Math.min(i++, states.length - 1)] })),
    );
    const client = new InteractionsClient({ fetch: fn });

    const seen: string[] = [];
    for await (const it of client.watch('int-1', { intervalMs: 1 })) {
      seen.push(it.status);
    }
    expect(seen).toEqual(['RUNNING', 'COMPLETED']);
  });

  it('isTerminal classifies states', () => {
    expect(isTerminal('COMPLETED')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
    expect(isTerminal('CANCELLED')).toBe(true);
    expect(isTerminal('RUNNING')).toBe(false);
    expect(isTerminal('CREATED')).toBe(false);
  });

  describe('parseFrames (live stream)', () => {
    it('parses a single frame', () => {
      const out = parseFrames('{"type":"interaction-step","step":{"seq":0}}');
      expect(out).toHaveLength(1);
      expect(out[0].type).toBe('interaction-step');
    });

    it('splits concatenated frames at top-level object boundaries', () => {
      const out = parseFrames(
        '{"type":"interaction-step","step":{"seq":0}}{"type":"interaction-terminal","status":"COMPLETED"}',
      );
      expect(out).toHaveLength(2);
      expect(out[1].status).toBe('COMPLETED');
    });

    it('does not split on braces inside string values', () => {
      const out = parseFrames('{"type":"interaction-step","step":{"text":"a {nested} brace"}}');
      expect(out).toHaveLength(1);
      expect((out[0].step as { text: string }).text).toBe('a {nested} brace');
    });

    it('passes through an already-parsed object', () => {
      const out = parseFrames({ type: 'interaction-terminal', status: 'FAILED' });
      expect(out).toEqual([{ type: 'interaction-terminal', status: 'FAILED' }]);
    });

    it('returns empty for blank or non-JSON input', () => {
      expect(parseFrames('')).toEqual([]);
      expect(parseFrames(null)).toEqual([]);
      expect(parseFrames('not json')).toEqual([]);
    });
  });
});
