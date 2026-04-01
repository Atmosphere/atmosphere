import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

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

async function waitFor(fn: () => boolean, timeoutMs = 30_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

function parsed(messages: string[]): unknown[] {
  return messages.map(m => { try { return JSON.parse(m); } catch { return null; } }).filter(Boolean);
}

test.describe('Room Typing & Direct Messages', () => {
  test('@flaky typing indicator is broadcast to other room members', async () => {
    const conn1 = await connectRoomWs(server.baseUrl);
    const conn2 = await connectRoomWs(server.baseUrl);

    // Both join lobby
    conn1.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Typist' }));
    await waitFor(() => conn1.messages.some(m => m.includes('join_ack')));

    conn2.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Observer' }));
    await waitFor(() => conn2.messages.some(m => m.includes('join_ack')));

    // Typist starts typing
    conn1.ws.send(JSON.stringify({ type: 'typing', room: 'lobby', typing: true }));

    // Observer should receive the typing event
    await waitFor(() => parsed(conn2.messages).some(
      (m: any) => m.type === 'typing' && m.typing === true && m.memberId === 'Typist',
    ));

    // Typist stops typing
    conn1.ws.send(JSON.stringify({ type: 'typing', room: 'lobby', typing: false }));

    await waitFor(() => parsed(conn2.messages).some(
      (m: any) => m.type === 'typing' && m.typing === false && m.memberId === 'Typist',
    ));

    conn1.close();
    conn2.close();
  });

  test('@flaky typing indicator is NOT echoed back to sender', async () => {
    const conn = await connectRoomWs(server.baseUrl);

    conn.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'SoloTyper' }));
    await waitFor(() => conn.messages.some(m => m.includes('join_ack')));

    // Clear messages after join
    const countBefore = conn.messages.length;

    conn.ws.send(JSON.stringify({ type: 'typing', room: 'lobby', typing: true }));

    // Wait a moment — sender should NOT get their own typing event
    await new Promise(r => setTimeout(r, 1_000));

    const newMessages = parsed(conn.messages.slice(countBefore));
    const ownTyping = newMessages.filter((m: any) => m.type === 'typing');
    expect(ownTyping.length).toBe(0);

    conn.close();
  });

  test('@flaky direct message reaches only the target member', async () => {
    const conn1 = await connectRoomWs(server.baseUrl);
    const conn2 = await connectRoomWs(server.baseUrl);
    const conn3 = await connectRoomWs(server.baseUrl);

    // All join lobby
    conn1.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Sender' }));
    await waitFor(() => conn1.messages.some(m => m.includes('join_ack')));

    conn2.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Target' }));
    await waitFor(() => conn2.messages.some(m => m.includes('join_ack')));

    conn3.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Bystander' }));
    await waitFor(() => conn3.messages.some(m => m.includes('join_ack')));

    // Record message counts after join
    const targetCountBefore = conn2.messages.length;
    const bystanderCountBefore = conn3.messages.length;

    // Send direct message from Sender to Target
    conn1.ws.send(JSON.stringify({
      type: 'direct',
      room: 'lobby',
      targetId: 'Target',
      data: 'secret-whisper',
    }));

    // Target should receive it
    await waitFor(() => parsed(conn2.messages.slice(targetCountBefore)).some(
      (m: any) => m.type === 'message' && m.data === 'secret-whisper',
    ));

    // Give bystander time to NOT receive it
    await new Promise(r => setTimeout(r, 1_000));

    const bystanderNew = parsed(conn3.messages.slice(bystanderCountBefore));
    const bystanderGotSecret = bystanderNew.some(
      (m: any) => m.type === 'message' && m.data === 'secret-whisper',
    );
    expect(bystanderGotSecret).toBe(false);

    conn1.close();
    conn2.close();
    conn3.close();
  });

  test('@flaky direct message to non-existent member returns error', async () => {
    const conn = await connectRoomWs(server.baseUrl);

    conn.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'ErrorSender' }));
    await waitFor(() => conn.messages.some(m => m.includes('join_ack')));

    const countBefore = conn.messages.length;

    conn.ws.send(JSON.stringify({
      type: 'direct',
      room: 'lobby',
      targetId: 'NonExistentUser',
      data: 'hello?',
    }));

    // Should get an error response
    await waitFor(() => parsed(conn.messages.slice(countBefore)).some(
      (m: any) => m.type === 'error',
    ));

    conn.close();
  });

  test('leave presence is broadcast to remaining members', async () => {
    const conn1 = await connectRoomWs(server.baseUrl);
    const conn2 = await connectRoomWs(server.baseUrl);

    conn1.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Stayer' }));
    await waitFor(() => conn1.messages.some(m => m.includes('join_ack')));

    conn2.ws.send(JSON.stringify({ type: 'join', room: 'lobby', memberId: 'Leaver' }));
    await waitFor(() => conn2.messages.some(m => m.includes('join_ack')));

    // Record Stayer's message count
    const stayerCountBefore = conn1.messages.length;

    // Leaver explicitly leaves
    conn2.ws.send(JSON.stringify({ type: 'leave', room: 'lobby' }));

    // Stayer should receive leave presence
    await waitFor(() => parsed(conn1.messages.slice(stayerCountBefore)).some(
      (m: any) => m.type === 'presence' && m.action === 'leave' && m.memberId === 'Leaver',
    ));

    conn1.close();
    conn2.close();
  });
});
