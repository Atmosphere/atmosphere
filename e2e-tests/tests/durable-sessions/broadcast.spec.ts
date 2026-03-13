import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the durable sessions sample — basic chat functionality.
 *
 * Validates that standard chat broadcast works with durable session
 * persistence enabled (SQLite backend). Persistence-specific tests
 * are in persistence.spec.ts.
 */
test.describe('Durable Sessions — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
