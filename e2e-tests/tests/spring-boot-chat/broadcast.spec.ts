import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the Spring Boot chat sample.
 *
 * Validates that the same Chat.java handler works correctly when deployed
 * as a Spring Boot application with the atmosphere-spring-boot-starter.
 *
 * This sample uses the Room Protocol, so the join confirmation message
 * is "Joined room" (from the join_ack), not "X has joined!".
 */
test.describe('Spring Boot Chat — WebSocket Broadcast', () => {
  registerBroadcastTests({
    joinConfirmation: () => 'Joined room',
  });
});
