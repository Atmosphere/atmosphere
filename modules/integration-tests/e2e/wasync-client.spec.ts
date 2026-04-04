import { test, expect } from '@playwright/test';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const WASYNC_MODULE = resolve(ROOT, 'modules', 'wasync');

/**
 * wAsync client E2E tests — verifies the wAsync Java client library module.
 *
 * The wAsync module is a Java client library — its tests require the
 * full Maven build. Compilation and test execution are handled by the
 * main Atmosphere CI job. Here we verify module structure.
 */

const hasWasyncModule = existsSync(resolve(WASYNC_MODULE, 'pom.xml'));

(hasWasyncModule ? test.describe : test.describe.skip)('wAsync Client', () => {

  test('wAsync module pom.xml exists', async () => {
    expect(existsSync(resolve(WASYNC_MODULE, 'pom.xml'))).toBe(true);
  });

  test('wAsync module has source files', async () => {
    const srcDir = resolve(WASYNC_MODULE, 'src', 'main');
    expect(existsSync(srcDir)).toBe(true);
  });

  test('wAsync module depends on atmosphere-runtime', async () => {
    const pom = require('fs').readFileSync(
      resolve(WASYNC_MODULE, 'pom.xml'), 'utf-8',
    );
    expect(pom).toContain('atmosphere-runtime');
  });
});
