import { test } from '@playwright/test';
import { registerAiToolsTests } from '../fixtures/ai-tools-suite';

test.describe('Spring Boot LangChain4j Tools', () => {
  registerAiToolsTests({
    timeToolName: 'cityTime',
    weatherToolName: 'weather',
  });
});
