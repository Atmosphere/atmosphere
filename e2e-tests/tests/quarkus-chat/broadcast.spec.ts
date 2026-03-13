import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the Quarkus chat sample.
 *
 * Validates that the same Chat.java handler works correctly when deployed
 * as a Quarkus application with the atmosphere-quarkus-extension.
 * This is key to verifying Atmosphere's "write once, deploy everywhere" promise.
 */
test.describe('Quarkus Chat — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
