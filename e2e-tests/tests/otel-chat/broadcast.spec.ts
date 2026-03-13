import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the OpenTelemetry chat sample.
 *
 * Validates that standard chat broadcast works with OpenTelemetry
 * tracing instrumentation enabled. Trace verification is not tested
 * here (would require Jaeger setup).
 */
test.describe('OTel Chat — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
