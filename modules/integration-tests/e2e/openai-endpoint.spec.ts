import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * OpenAI-compatible serving endpoint (atmosphere.ai.openai.enabled=true in
 * the spring-boot-ai-chat sample). Exercises the wire contract an OpenAI SDK
 * client relies on: model discovery, non-streaming and SSE-streaming chat
 * completions, and the OpenAI error envelope for bad input.
 *
 * The e2e fixture forces ATMOSPHERE_AUTH_ENABLED=true, so the framework
 * AuthInterceptor gates this endpoint like every other handler — requests
 * must present X-Atmosphere-Auth: demo-token (also asserted negatively).
 *
 * No LLM key is configured in CI: the DemoAgentRuntime serves canned text
 * through the real AiPipeline, so the wire format is fully exercised.
 */

const AUTH = { 'X-Atmosphere-Auth': 'demo-token' };
const JSON_HEADERS = { ...AUTH, 'Content-Type': 'application/json' };

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('OpenAI-compatible endpoint', () => {
  test('@smoke GET /v1/models lists the configured model', async ({ request }) => {
    const response = await request.get(server.baseUrl + '/atmosphere/v1/models', {
      headers: AUTH,
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.object).toBe('list');
    const ids = (body.data as { id: string }[]).map((m) => m.id);
    expect(ids).toContain('atmosphere-ai-chat');
  });

  test('@smoke non-streaming completion returns a chat.completion envelope', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: {
          model: 'atmosphere-ai-chat',
          messages: [{ role: 'user', content: 'Hello from the OpenAI wire' }],
        },
      },
    );
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.object).toBe('chat.completion');
    expect(body.model).toBe('atmosphere-ai-chat');
    expect(body.id).toMatch(/^chatcmpl-/);
    expect(body.choices[0].finish_reason).toBe('stop');
    expect(body.choices[0].message.role).toBe('assistant');
    // Key-agnostic: the demo runtime (keyless CI) and a real LLM both must
    // produce a non-empty assistant turn through the pipeline.
    expect(typeof body.choices[0].message.content).toBe('string');
    expect(body.choices[0].message.content.length).toBeGreaterThan(0);
  });

  test('streaming completion emits chunk frames and terminates with [DONE]', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: {
          model: 'atmosphere-ai-chat',
          stream: true,
          messages: [{ role: 'user', content: 'Stream me a reply' }],
        },
      },
    );
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/event-stream');

    const raw = await response.text();
    const frames = raw
      .split('\n\n')
      .filter((f) => f.startsWith('data: '))
      .map((f) => f.substring('data: '.length).trim());
    expect(frames[frames.length - 1]).toBe('[DONE]');

    const chunks = frames
      .filter((f) => f !== '[DONE]')
      .map((f) => JSON.parse(f) as Record<string, any>);
    expect(chunks.length).toBeGreaterThan(1);
    for (const chunk of chunks) {
      expect(chunk.object).toBe('chat.completion.chunk');
    }
    // First frame opens the assistant turn; content deltas reassemble the
    // reply (key-agnostic: any non-empty text from demo or real runtime).
    expect(chunks[0].choices[0].delta.role).toBe('assistant');
    const text = chunks
      .filter((c) => c.choices?.[0]?.delta?.content)
      .map((c) => c.choices[0].delta.content)
      .join('');
    expect(text.length).toBeGreaterThan(0);
    expect(
      chunks.some((c) => c.choices?.[0]?.finish_reason === 'stop'),
    ).toBe(true);
  });

  test('multi-turn history is accepted and answered', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: {
          model: 'atmosphere-ai-chat',
          messages: [
            { role: 'system', content: 'Client-side context.' },
            { role: 'user', content: 'First question' },
            { role: 'assistant', content: 'First answer' },
            { role: 'user', content: 'Follow-up question' },
          ],
        },
      },
    );
    expect(response.status()).toBe(200);
    const body = await response.json();
    // Structural acceptance of multi-turn history (the history→pipeline
    // threading itself is pinned by OpenAiChatHandlerTest); with a real
    // LLM key this same call answers from the supplied context.
    expect(body.object).toBe('chat.completion');
    expect(body.choices[0].message.content.length).toBeGreaterThan(0);
  });

  test('unknown model returns a 404 OpenAI error envelope', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: {
          model: 'not-a-model',
          messages: [{ role: 'user', content: 'Hi' }],
        },
      },
    );
    expect(response.status()).toBe(404);
    const body = await response.json();
    expect(body.error.type).toBe('invalid_request_error');
    expect(body.error.code).toBe('model_not_found');
  });

  test('malformed JSON returns a 400 OpenAI error envelope', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: 'this is not json {{{',
      },
    );
    expect(response.status()).toBe(400);
    const body = await response.json();
    expect(body.error.type).toBe('invalid_request_error');
  });

  test('tools passthrough is rejected as unsupported_parameter', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: JSON_HEADERS,
        data: {
          model: 'atmosphere-ai-chat',
          tools: [{ type: 'function', function: { name: 'f', parameters: {} } }],
          messages: [{ role: 'user', content: 'Hi' }],
        },
      },
    );
    expect(response.status()).toBe(400);
    const body = await response.json();
    expect(body.error.code).toBe('unsupported_parameter');
    expect(body.error.param).toBe('tools');
  });

  test('framework auth gates the endpoint when enabled', async ({ request }) => {
    const response = await request.post(
      server.baseUrl + '/atmosphere/v1/chat/completions',
      {
        headers: { 'Content-Type': 'application/json' },
        data: {
          model: 'atmosphere-ai-chat',
          messages: [{ role: 'user', content: 'Hi' }],
        },
      },
    );
    // ATMOSPHERE_AUTH_ENABLED=true in the fixture: without X-Atmosphere-Auth
    // the AuthInterceptor rejects before the handler runs.
    expect(response.status()).toBe(401);
  });
});
