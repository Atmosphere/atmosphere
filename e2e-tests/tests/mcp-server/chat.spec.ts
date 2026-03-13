import { test } from '@playwright/test';
import { registerBroadcastTests } from '../fixtures/broadcast-suite';

/**
 * E2E tests for the MCP server sample — chat functionality.
 *
 * Validates that standard chat broadcast works alongside the MCP
 * (Model Context Protocol) server. MCP tool invocation is not tested
 * here as it requires an MCP client protocol implementation.
 */
test.describe('MCP Server — WebSocket Broadcast', () => {
  registerBroadcastTests();
});
