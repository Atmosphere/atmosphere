import { test } from '@playwright/test';
import { registerAiToolsTests } from '../fixtures/ai-tools-suite';

test.describe('Spring Boot ADK Tools', () => {
  registerAiToolsTests({
    timeToolName: 'getCurrentTime',
    weatherToolName: 'getWeather',
  });
});
