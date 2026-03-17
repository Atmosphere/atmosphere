import { test } from '@playwright/test';
import { registerAiChatTests } from '../fixtures/ai-chat-suite';

test.describe('Spring Boot Spring AI Routing', () => {
  registerAiChatTests({ responseIndicator: /real-time|demo mode|routed to/i });
});
