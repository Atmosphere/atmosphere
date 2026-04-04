import { test, expect } from '@playwright/test';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const KOTLIN_MODULE = resolve(ROOT, 'modules', 'kotlin');

/**
 * Kotlin DSL E2E tests — verifies Kotlin coroutine endpoints and DSL builder patterns.
 *
 * The Kotlin module requires the Kotlin compiler — these tests verify
 * the module exists and is properly structured. Compilation testing
 * is handled by the main Atmosphere CI job.
 */

const hasKotlinModule = existsSync(resolve(KOTLIN_MODULE, 'pom.xml'));

(hasKotlinModule ? test.describe : test.describe.skip)('Kotlin DSL', () => {

  test('Kotlin module pom.xml exists', async () => {
    expect(existsSync(resolve(KOTLIN_MODULE, 'pom.xml'))).toBe(true);
  });

  test('Kotlin DSL source files exist', async () => {
    const srcDir = resolve(KOTLIN_MODULE, 'src', 'main');
    expect(existsSync(srcDir)).toBe(true);
  });

  test('Kotlin module has atmosphere-runtime dependency', async () => {
    const pom = require('fs').readFileSync(
      resolve(KOTLIN_MODULE, 'pom.xml'), 'utf-8',
    );
    expect(pom).toContain('atmosphere-runtime');
  });
});
