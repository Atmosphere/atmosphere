import { test } from '@playwright/test';
import { registerAiChatTests } from '../fixtures/ai-chat-suite';

test.describe('Spring Boot LangChain4j Chat', () => {
  registerAiChatTests();
});
