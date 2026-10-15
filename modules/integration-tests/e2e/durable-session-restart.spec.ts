import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectDurableChat, waitFor } from './helpers/durable-chat-client';
import { existsSync, statSync } from 'fs';
import { resolve as repoPath } from 'path';

// The sample persists sessions to data/sessions.db relative to its own cwd
// (SessionStoreConfig -> new SqliteSessionStore(Path.of("data/sessions.db"))).
const SESSIONS_DB = repoPath(
  __dirname, '..', '..', '..', 'samples', 'spring-boot-durable-sessions', 'data', 'sessions.db');

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-durable-sessions']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Run one durable-session handshake over the long-polling transport and return
 * the session token the DurableSessionInterceptor issued. The token is only
 * surfaced on an HTTP transport (the interceptor's response header does not
 * reach the WebSocket 101 upgrade), and it is the survival signal: on a restore
 * the interceptor echoes back the SAME token, on a new session it mints a fresh
 * one. The 501 body is irrelevant — the interceptor runs (and persists/restores)
 * regardless of long-polling being fully wired on this sample.
 */
async function handshakeToken(
  request: import('@playwright/test').APIRequestContext,
  baseUrl: string,
  sessionToken?: string,
): Promise<string | undefined> {
  const url = baseUrl + '/atmosphere/chat?X-Atmosphere-Transport=long-polling'
    + '&X-Atmosphere-tracking-id=0&X-atmo-protocol=true'
    + (sessionToken ? '&X-Atmosphere-Session-Token=' + encodeURIComponent(sessionToken) : '');
  const res = await request.get(url, { timeout: 10_000, failOnStatusCode: false });
  return res.headers()['x-atmosphere-session-token'];
}

test.describe('Durable Session Restart', () => {

  test('session survives full server restart (same token restored)', async ({ request }) => {
    // Fresh handshake — the server mints and persists a durable session token.
    const token = await handshakeToken(request, server.baseUrl);
    expect(token, 'server must issue a session token on first connect').toBeTruthy();

    // The session must have been persisted to SQLite (the whole point of the
    // sample). A prior version of this test asserted none of this — it just
    // checked that a NEW connection could echo a message after restart, which
    // stays green even if durability is completely broken.
    expect(existsSync(SESSIONS_DB), `${SESSIONS_DB} must exist`).toBe(true);
    expect(statSync(SESSIONS_DB).size, 'sessions.db must be non-empty').toBeGreaterThan(0);

    // Full JVM restart — in-memory state is gone; only SQLite remains.
    await server.restart();

    // Reconnect WITH the token. The DurableSessionInterceptor calls
    // store.restore(token); on a hit it echoes the SAME token back (and logs
    // "Restoring durable session"). Had the session not survived the restart,
    // restore() would miss and it would mint a new, different token.
    const restored = await handshakeToken(request, server.baseUrl, token);
    expect(restored,
      'reconnecting with the token after restart must restore the same session')
      .toBe(token);
  });

  test('broadcast resumes after restart', async () => {
    const clientA = await connectDurableChat(server.baseUrl);
    const clientB = await connectDurableChat(server.baseUrl);

    // Verify broadcast works before restart
    clientA.ws.send(JSON.stringify({ author: 'A', message: 'pre-restart-msg' }));
    await waitFor(() => clientB.messages.some(m => m.includes('pre-restart-msg')));

    clientA.close();
    clientB.close();

    // Restart
    await server.restart();

    // Reconnect both clients
    const clientA2 = await connectDurableChat(server.baseUrl);
    const clientB2 = await connectDurableChat(server.baseUrl);

    // Verify broadcast works after restart
    clientA2.ws.send(JSON.stringify({ author: 'A', message: 'post-restart-msg' }));
    await waitFor(() => clientB2.messages.some(m => m.includes('post-restart-msg')));

    clientA2.close();
    clientB2.close();
  });

  test('an unknown token after restart gets a fresh session (not restored)', async ({ request }) => {
    await server.restart();

    // Hand the server a token it never issued — restore() misses, so the
    // interceptor creates a NEW session and returns a NEW token (it never echoes
    // the unknown one back). This is the negative control that proves the
    // survival test above exercises real restoration, not "always echoes back
    // whatever token it was handed".
    const bogus = 'stale-token-from-previous-run';
    const issued = await handshakeToken(request, server.baseUrl, bogus);
    expect(issued, 'server must issue a token').toBeTruthy();
    expect(issued, 'an unknown token must not be echoed back — a new session is minted')
      .not.toBe(bogus);
  });

  test('multiple restarts in sequence work correctly', async () => {
    for (let i = 1; i <= 3; i++) {
      const conn = await connectDurableChat(server.baseUrl);
      conn.ws.send(JSON.stringify({ author: 'Loop', message: `restart-${i}` }));
      await waitFor(() => conn.messages.some(m => m.includes(`restart-${i}`)));
      conn.close();

      if (i < 3) {
        await server.restart();
      }
    }
  });
});
