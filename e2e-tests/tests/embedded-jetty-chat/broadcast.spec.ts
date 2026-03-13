import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the embedded Jetty WebSocket chat sample.
 *
 * This is the original Atmosphere chat sample using @WebSocketHandlerService
 * with programmatic Jetty embedding.
 */
test.describe('Embedded Jetty Chat — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
