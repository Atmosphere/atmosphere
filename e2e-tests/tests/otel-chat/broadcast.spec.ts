import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the OpenTelemetry Chat sample.
 *
 * This sample uses the same chat UI as spring-boot-chat (broadcast chat
 * with Room Protocol) plus OpenTelemetry tracing. The broadcast tests
 * validate the core chat functionality works with OTel instrumentation.
 */
test.describe('OTel Chat — WebSocket Broadcast', () => {
  registerBroadcastTests({ joinConfirmation: () => 'Joined room' });
});
