import { test } from '@playwright/test';

/**
 * Quarantine policy for e2e specs.
 *
 * The repo quality gates require that a quarantined test carry an owner, an
 * expiry and a tracking issue, and that quarantined tests still run in a
 * scheduled lane. A bare `test.skip('...')` satisfies none of that: it has no
 * metadata, and because Playwright's static skip cannot be re-enabled by any
 * flag, it runs in no lane at all — the coverage silently disappears.
 *
 * {@link quarantined} replaces that pattern. It returns the real `test`
 * function (so the body actually runs) when either
 *
 *   - `RUN_QUARANTINED=true` is set — the weekly "CI: E2E Quarantine" lane, or
 *   - the entry is past its `expires` date,
 *
 * and `test.skip` otherwise. The expiry therefore bites: once it passes, the
 * test comes back on its own in every lane, so a stale quarantine surfaces as
 * either a pass (the block is gone — delete the metadata) or a failure
 * (someone triages it) instead of sitting inert forever.
 *
 * Tag the test title with `@quarantined` as well, so the scheduled lane can
 * select these specs with `--grep`.
 */
export interface QuarantineMeta {
  /** Who is accountable for retiring this quarantine. */
  owner: string;
  /** ISO-8601 date (YYYY-MM-DD) after which the test runs again everywhere. */
  expires: string;
  /**
   * Tracking issue reference. Use `pending` when the issue has not been filed
   * yet — never invent a number, a wrong reference is worse than none.
   */
  issue: string;
  /** Why the test cannot pass today, in one line. */
  reason: string;
}

/** True when the scheduled quarantine lane is driving the run. */
export function quarantineLaneActive(): boolean {
  return process.env.RUN_QUARANTINED === 'true';
}

function isExpired(expires: string): boolean {
  const deadline = Date.parse(expires);
  if (Number.isNaN(deadline)) {
    // A malformed date must not silently grant an indefinite quarantine.
    throw new Error(
      `Quarantine 'expires' must be an ISO date (YYYY-MM-DD), got: ${expires}`,
    );
  }
  return Date.now() > deadline;
}

/**
 * Resolve the test function for a quarantined spec.
 *
 * @param meta owner / expiry / issue / reason for the quarantine
 * @returns `test` when the quarantine lane is active or the entry has expired,
 *          `test.skip` otherwise
 */
export function quarantined(meta: QuarantineMeta): typeof test {
  if (!meta.owner || !meta.issue || !meta.reason) {
    throw new Error('Quarantine requires owner, issue and reason');
  }
  return quarantineLaneActive() || isExpired(meta.expires) ? test : test.skip;
}
