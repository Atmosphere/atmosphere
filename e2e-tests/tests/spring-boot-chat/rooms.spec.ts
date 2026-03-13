import { test, expect } from '@playwright/test';
import {
  waitForConnected,
  joinChat,
  openBrowsers,
  navigateAndConnect,
  closeAll,
} from '../fixtures/chat-helpers';

/**
 * E2E tests for the Spring Boot chat sample's Room API.
 *
 * The spring-boot-chat sample includes a room management system with:
 *   - GET /api/rooms REST endpoint returning room info
 *   - A pre-configured "lobby" room with 50-message history
 *   - Room presence tracking (join/leave events)
 */
test.describe('Spring Boot Chat — Room API', () => {
  test('GET /api/rooms returns room list', async ({ request }) => {
    const response = await request.get('/api/rooms');
    expect(response.ok()).toBeTruthy();

    const rooms = await response.json();
    expect(Array.isArray(rooms)).toBe(true);

    // The "lobby" room should exist by default
    const lobby = rooms.find((r: { name: string }) => r.name === 'lobby');
    expect(lobby).toBeDefined();
    expect(lobby.destroyed).toBe(false);
    expect(typeof lobby.members).toBe('number');
    expect(Array.isArray(lobby.memberDetails)).toBe(true);
  });

  test('room member count increases when users join', async ({ browser, request }) => {
    const { contexts, pages } = await openBrowsers(browser, 2);
    const [page1, page2] = pages;

    try {
      await navigateAndConnect(pages);

      // Check initial member count
      const before = await request.get('/api/rooms');
      const roomsBefore = await before.json();
      const lobbyBefore = roomsBefore.find((r: { name: string }) => r.name === 'lobby');
      const initialMembers = lobbyBefore?.members ?? 0;

      // Join two users
      await joinChat(page1, 'Alice', 'Joined room');
      await joinChat(page2, 'Bob', 'Joined room');

      // Allow time for room membership to update
      await pages[0].waitForTimeout(2_000);

      // Check member count increased
      const after = await request.get('/api/rooms');
      const roomsAfter = await after.json();
      const lobbyAfter = roomsAfter.find((r: { name: string }) => r.name === 'lobby');
      expect(lobbyAfter).toBeDefined();
      expect(lobbyAfter.members).toBeGreaterThanOrEqual(initialMembers + 2);
    } finally {
      await closeAll(contexts);
    }
  });

  test('room memberDetails includes connected users', async ({ browser, request }) => {
    const { contexts, pages } = await openBrowsers(browser, 1);
    const [page1] = pages;

    try {
      await navigateAndConnect(pages);
      await joinChat(page1, 'TestUser', 'Joined room');

      // Allow time for room membership to update
      await page1.waitForTimeout(2_000);

      const response = await request.get('/api/rooms');
      const rooms = await response.json();
      const lobby = rooms.find((r: { name: string }) => r.name === 'lobby');

      expect(lobby).toBeDefined();
      expect(lobby.memberDetails.length).toBeGreaterThanOrEqual(1);

      // Each member should have an id and metadata
      for (const member of lobby.memberDetails) {
        expect(member.id).toBeDefined();
        expect(typeof member.id).toBe('string');
      }
    } finally {
      await closeAll(contexts);
    }
  });
});
