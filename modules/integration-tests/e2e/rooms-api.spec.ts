import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { ChatPage } from './helpers/chat-page';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Send a Room Protocol JSON message over a raw WebSocket.
 */
function connectRoomWs(
  baseUrl: string,
): Promise<{ ws: WebSocket; messages: string[]; close: () => void }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);
    const messages: string[] = [];

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (text) messages.push(text);
    });

    ws.on('open', () => resolve({ ws, messages, close: () => ws.close() }));
    ws.on('error', reject);
    setTimeout(() => reject(new Error('WebSocket connect timeout')), 10_000);
  });
}

/** Wait for a condition with polling. */
async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

test.describe('Room API & Protocol', () => {
  test('REST API /api/rooms returns room list', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/rooms`);
    expect(res.ok()).toBeTruthy();

    const rooms = await res.json();
    expect(Array.isArray(rooms)).toBeTruthy();
    // The "lobby" room is pre-provisioned by RoomsConfig
    const lobby = rooms.find((r: { name: string }) => r.name === 'lobby');
    expect(lobby).toBeDefined();
    expect(lobby.members).toBeGreaterThanOrEqual(0);
  });

  test('user joins lobby room via Room Protocol', async () => {
    const { ws, messages, close } = await connectRoomWs(server.baseUrl);

    // Send join message
    ws.send(JSON.stringify({
      type: 'join',
      room: 'lobby',
      memberId: 'RoomTestUser',
      metadata: { joinedAt: Date.now() },
    }));

    // Wait for join_ack
    await waitFor(() => messages.some(m => {
      try {
        const parsed = JSON.parse(m);
        return parsed.type === 'join_ack';
      } catch {
        return false;
      }
    }));

    const ack = messages
      .map(m => { try { return JSON.parse(m); } catch { return null; } })
      .find(m => m?.type === 'join_ack');

    expect(ack).toBeDefined();
    expect(ack.members).toBeDefined();

    close();
  });

  test('two users in same room see each other via presence', async () => {
    const conn1 = await connectRoomWs(server.baseUrl);
    const conn2 = await connectRoomWs(server.baseUrl);

    // User 1 joins
    conn1.ws.send(JSON.stringify({
      type: 'join',
      room: 'lobby',
      memberId: 'Alice',
      metadata: {},
    }));
    await waitFor(() => conn1.messages.some(m => m.includes('join_ack')));

    // User 2 joins — User 1 should get a presence event
    conn2.ws.send(JSON.stringify({
      type: 'join',
      room: 'lobby',
      memberId: 'Bob',
      metadata: {},
    }));

    await waitFor(() => conn2.messages.some(m => m.includes('join_ack')));

    // User 1 should see Bob's join via presence
    await waitFor(() => conn1.messages.some(m => {
      try {
        const parsed = JSON.parse(m);
        return parsed.type === 'presence' && parsed.memberId === 'Bob' && parsed.action === 'join';
      } catch {
        return false;
      }
    }));

    conn1.close();
    conn2.close();
  });

  test('broadcast message is received by all room members', async () => {
    const conn1 = await connectRoomWs(server.baseUrl);
    const conn2 = await connectRoomWs(server.baseUrl);

    // Both join lobby
    conn1.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Sender' }));
    await waitFor(() => conn1.messages.some(m => m.includes('join_ack')));

    conn2.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Receiver' }));
    await waitFor(() => conn2.messages.some(m => m.includes('join_ack')));

    // Sender broadcasts
    conn1.ws.send(JSON.stringify({
      type: 'broadcast',
      room: 'lobby',
      data: 'Hello room!',
    }));

    // Receiver should get the message
    await waitFor(() => conn2.messages.some(m => {
      try {
        const parsed = JSON.parse(m);
        return parsed.type === 'message' && parsed.data === 'Hello room!';
      } catch {
        return false;
      }
    }));

    conn1.close();
    conn2.close();
  });

  test('/api/rooms reflects connected members', async ({ request }) => {
    const conn = await connectRoomWs(server.baseUrl);

    conn.ws.send(JSON.stringify({
      type: 'join',
      room: 'lobby',
      memberId: 'ApiTestUser',
      metadata: { role: 'tester' },
    }));
    await waitFor(() => conn.messages.some(m => m.includes('join_ack')));

    // Give server time to update room state
    await new Promise(r => setTimeout(r, 1000));

    const res = await request.get(`${server.baseUrl}/api/rooms`);
    const rooms = await res.json();
    const lobby = rooms.find((r: { name: string }) => r.name === 'lobby');

    expect(lobby).toBeDefined();
    expect(lobby.members).toBeGreaterThanOrEqual(1);

    conn.close();
  });

  test('rooms tab in browser shows room list', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('chat-layout').waitFor({ state: 'visible' });

    // Click the Rooms tab
    await page.getByText('Rooms').click();

    // The rooms panel fetches /api/rooms — should show "lobby"
    await expect(page.getByText('lobby')).toBeVisible({ timeout: 10_000 });
  });
});
