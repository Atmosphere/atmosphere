import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-classroom']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot AI Classroom', () => {
  test('page loads with room selector', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('room-selector')).toBeVisible();
  });

  test('joining math room shows classroom layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('room-math').click();

    await expect(page.getByTestId('classroom-layout')).toBeVisible();
    await expect(page.getByTestId('room-badge')).toContainText('Math');
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('student sends question and sees streaming response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('room-math').click();

    await page.getByTestId('chat-input').fill('What is a prime number?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is a prime number?')).toBeVisible();

    // Wait for demo mode response
    await expect(page.getByText('demo mode', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });
});
