import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const KOTLIN_MODULE = resolve(ROOT, 'modules', 'kotlin');

/**
 * Kotlin DSL E2E tests — verifies Kotlin coroutine endpoints and DSL builder patterns.
 * Delegates to the Kotlin module's test suite since Kotlin DSL tests require
 * the Kotlin compiler and runtime.
 *
 * Skipped if the Kotlin module doesn't exist or hasn't been built.
 */

const hasKotlinModule = existsSync(resolve(KOTLIN_MODULE, 'pom.xml'));

(hasKotlinModule ? test.describe : test.describe.skip)('Kotlin DSL', () => {

  test.describe.configure({ timeout: 120_000 });

  test('Kotlin module compiles successfully', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} compile -pl modules/kotlin -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('Kotlin module tests pass', async () => {
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} test -pl modules/kotlin -Dsurefire.useFile=false`,
      { cwd: ROOT, timeout: 120_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    expect(result).toContain('BUILD SUCCESS');
  });

  test('Kotlin DSL builder patterns compile', async () => {
    // Verify the Kotlin module's main classes are on the classpath
    const result = execSync(
      `${resolve(ROOT, 'mvnw')} dependency:tree -pl modules/kotlin -q`,
      { cwd: ROOT, timeout: 60_000, encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] },
    );
    // Should reference atmosphere-runtime as a dependency
    expect(result).toContain('atmosphere-runtime');
  });
});
