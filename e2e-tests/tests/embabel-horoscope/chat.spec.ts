import { test } from '@playwright/test';
import { registerAiChatTests } from '../fixtures/ai-chat-suite';

test.describe('Spring Boot Embabel Horoscope', () => {
  registerAiChatTests({ responseIndicator: /horoscope|celestial|zodiac|demo mode/i });
});
