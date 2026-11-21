import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectDurableChat, waitFor } from './helpers/durable-chat-client';
import { existsSync, statSync } from 'fs';
import { execFileSync } from 'child_process';
import { resolve as repoPath } from 'path';

// The sample persists sessions to data/sessions.db relative to its own cwd
// (SessionStoreConfig -> new SqliteSessionStore(Path.of("data/sessions.db"))).
const SESSIONS_DB = repoPath(
  __dirname, '..', '..', '..', 'samples', 'spring-boot-durable-sessions', 'data', 'sessions.db');

let server: SampleServer;

/** The broadcaster of the sample's chat endpoint. */
const CHAT_BROADCASTER = '/atmosphere/chat';

/** The broadcaster ids SqliteSessionStore saved for `token` (a JSON array column), [] when none. */
function storedBroadcasters(token: string): string[] {
  const column = execFileSync('sqlite3',
    [SESSIONS_DB, `SELECT broadcasters FROM durable_sessions WHERE token = '${token.replace(/'/g, "''")}';`],
    { encoding: 'utf-8' }).trim();
  if (column === '') {
    return [];
  }
  const parsed: unknown = JSON.parse(column);
  return Array.isArray(parsed) ? parsed.map(String) : [];
}

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

  test('sweep 4.0.78: over WebSocket, a session connected at stop is restored after the restart', async () => {
    // The packaged sample never restored a WebSocket session: the token went
    // out only as an upgrade response header (unreadable by a WebSocket
    // client), a session still connected at stop was saved with
    // broadcasters=[], and every restore logged "... is not suspended ...
    // Suspend first". The client now asks for the token in band.
    const client = await connectDurableChat(server.baseUrl, { inband: true });
    await waitFor(() => client.sessionToken !== undefined);
    const token = client.sessionToken!;

    // Restart with the client still connected.
    await server.restart();
    const sinceRestart = server.getOutput().length;

    // SqliteSessionStore keeps the set as a JSON array: an empty set is "[]",
    // so the column must be parsed, not just be non-empty.
    expect(storedBroadcasters(token),
      'the stop must save the live connection\'s broadcasters, the chat one included')
      .toContain(CHAT_BROADCASTER);

    const back = await connectDurableChat(server.baseUrl, { inband: true, sessionToken: token });
    await waitFor(() => back.sessionToken !== undefined);
    expect(back.sessionToken, 'the reconnect must restore the same session, not mint one').toBe(token);
    const log = server.getOutput().slice(sinceRestart);
    expect(log).toContain(`Restoring durable session ${token}`);
    expect(log).not.toContain('Suspend first');

    // The restored connection is a chat member again, once: the restore's
    // re-join adds no second membership that would deliver a message twice.
    const peer = await connectDurableChat(server.baseUrl);
    peer.ws.send(JSON.stringify({ author: 'peer', message: 'after-ws-restore' }));
    await waitFor(() => back.messages.some((m) => m.includes('after-ws-restore')));
    peer.ws.send(JSON.stringify({ author: 'peer', message: 'after-ws-restore-fence' }));
    await waitFor(() => back.messages.some((m) => m.includes('after-ws-restore-fence')));
    expect(back.messages.filter((m) => m.includes('after-ws-restore') && !m.includes('fence')),
      'the restored connection receives a chat message exactly once').toHaveLength(1);

    // The restore moved the session to the new connection and kept what it
    // holds: the stored session still names the chat broadcaster.
    expect(storedBroadcasters(token),
      'the restored session must still name the chat broadcaster').toContain(CHAT_BROADCASTER);
    back.close();
    peer.close();
    client.close();
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
