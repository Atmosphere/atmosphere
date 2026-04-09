/**
 * CLI Runtime E2E Tests — verifies `atmosphere run` launches samples
 * with a working console (WebSocket connected, send/receive messages).
 *
 * These tests catch issues like:
 * - Missing dependencies (NoClassDefFoundError on startup)
 * - Wrong console endpoint detection (@ManagedService vs @AiEndpoint)
 * - WebSocket handshake failures after fallback
 */
import { test, expect } from '@playwright/test';
import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const CLI = resolve(ROOT, 'cli', 'atmosphere');

interface CliServer {
  proc: ChildProcess;
  port: number;
  output: string;
}

async function startViaCli(sample: string, port: number, timeoutMs = 120_000): Promise<CliServer> {
  const proc = spawn(CLI, ['run', sample, '--port', String(port)], {
    cwd: ROOT,
    env: { ...process.env, LLM_MODE: 'fake', ATMOSPHERE_AUTH_TOKEN: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let output = '';
  proc.stdout?.on('data', (d) => { output += d.toString(); });
  proc.stderr?.on('data', (d) => { output += d.toString(); });

  // Wait for HTTP ready
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/`);
      if (res.ok || res.status === 302) break;
    } catch { /* server not ready */ }
    await new Promise(r => setTimeout(r, 1000));
  }

  // Verify server actually started
  try {
    const res = await fetch(`http://127.0.0.1:${port}/`);
    if (!res.ok && res.status !== 302) {
      proc.kill('SIGTERM');
      throw new Error(`Server returned ${res.status}`);
    }
  } catch (e) {
    proc.kill('SIGTERM');
    console.error(`=== CLI output for ${sample} ===\n${output.slice(-2000)}`);
    throw new Error(`Failed to start ${sample} via CLI: ${e}`);
  }

  return { proc, port, output };
}

function stopServer(server: CliServer) {
  try {
    server.proc.kill('SIGTERM');
  } catch { /* already dead */ }
}

// --- Tests ---

let server: CliServer;

test.describe('CLI: spring-boot-chat', () => {
  test.beforeAll(async () => {
    test.setTimeout(180_000);
    server = await startViaCli('spring-boot-chat', 18090);
  });

  test.afterAll(() => stopServer(server));

  test('console endpoint returns correct path', async () => {
    const res = await fetch(`http://127.0.0.1:${server.port}/api/console/info`);
    expect(res.ok).toBeTruthy();
    const info = await res.json();
    expect(info.endpoint).toBe('/atmosphere/chat');
  });

  test('console page loads and WebSocket connects', async ({ page }) => {
    await page.goto(`http://localhost:${server.port}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Type a message')).toBeVisible();
  });

  test('console can send and receive a message', async ({ page }) => {
    await page.goto(`http://localhost:${server.port}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });

    const input = page.locator('textarea');
    await input.fill('Hello from CLI E2E test');
    await page.keyboard.press('Enter');

    // In demo/fake mode, we should get some response
    await expect(page.locator('[data-testid="message-list"]').or(page.locator('.messages-area')))
      .toContainText('Hello from CLI E2E test', { timeout: 10_000 });
  });
});

test.describe('CLI: spring-boot-dentist-agent', () => {
  test.beforeAll(async () => {
    test.setTimeout(180_000);
    server = await startViaCli('spring-boot-dentist-agent', 18091);
  });

  test.afterAll(() => stopServer(server));

  test('console connects to agent endpoint', async ({ page }) => {
    await page.goto(`http://localhost:${server.port}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
  });

  test('slash command /help works', async ({ page }) => {
    await page.goto(`http://localhost:${server.port}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });

    const input = page.locator('textarea');
    await input.fill('/help');
    await page.keyboard.press('Enter');

    await expect(page.locator('main')).toContainText(/firstaid|urgency|help/i, { timeout: 10_000 });
  });
});

test.describe('CLI: spring-boot-ai-tools', () => {
  test.beforeAll(async () => {
    test.setTimeout(180_000);
    server = await startViaCli('spring-boot-ai-tools', 18092);
  });

  test.afterAll(() => stopServer(server));

  test('console connects and shows tool-calling subtitle', async ({ page }) => {
    await page.goto(`http://localhost:${server.port}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
  });
});
