import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the Spring Boot chat sample.
 *
 * Validates that the same Chat.java handler works correctly when deployed
 * as a Spring Boot application with the atmosphere-spring-boot-starter.
 */
test.describe('Spring Boot Chat — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
