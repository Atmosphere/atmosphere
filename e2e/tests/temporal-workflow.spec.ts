import { expect, test } from '@playwright/test';

/**
 * Temporal adapter e2e — proves a `Workflow<S>` executed through
 * `atmosphere-checkpoint-temporal` lands on a real Temporal service, by
 * asserting the execution as rendered in the Temporal Web UI.
 *
 * REQUIRES:
 *   1. `temporal server start-dev` (UI on :8233, gRPC on :7233), and
 *   2. one prior run of TemporalWorkflowE2ERunner (see its Javadoc), which
 *      executes a two-step workflow named `e2e-doc-pipeline` through
 *      `Workflow.run()` and exits non-zero unless the temporal provider ran it.
 *
 * ATMO_E2E_BASE_URL must point at the Temporal UI (http://127.0.0.1:8233).
 */

test.describe('Temporal durable-execution adapter', () => {
  test('the workflow run appears Completed in the Temporal UI', async ({
    page,
  }) => {
    await page.goto('/namespaces/default/workflows');

    // The provider names executions atmosphere-<workflowName>-<uuid>.
    const row = page
      .getByRole('link', { name: /^atmosphere-e2e-doc-pipeline-/ })
      .first();
    await expect(row, 'the adapter-started execution is listed').toBeVisible({
      timeout: 15000,
    });
    await expect(
      page.getByRole('link', { name: 'AtmosphereTemporalWorkflow' }).first(),
      'the generic adapter workflow type is shown'
    ).toBeVisible();
    await expect(
      page.getByText('Completed', { exact: true }).first(),
      'the execution completed'
    ).toBeVisible();
  });

  test('the execution history shows the adapter driving each step as an activity', async ({
    page,
  }) => {
    await page.goto('/namespaces/default/workflows');
    const row = page
      .getByRole('link', { name: /^atmosphere-e2e-doc-pipeline-/ })
      .first();
    await expect(row).toBeVisible({ timeout: 15000 });
    await row.click();
    await page.waitForURL(/\/workflows\/atmosphere-e2e-doc-pipeline-/);

    // Detail header: type + task queue are the adapter's, not a sample's.
    await expect(
      page.getByRole('link', { name: 'AtmosphereTemporalWorkflow' }).first()
    ).toBeVisible();
    await expect(
      page.getByRole('link', { name: 'atmosphere-workflow' }).first(),
      'runs on the adapter task queue'
    ).toBeVisible();

    await page.getByRole('tab', { name: /Event History/ }).click();

    // The adapter's two activities: resume resolution + one per step.
    await expect(
      page.getByText('ResolveStartIndex').first(),
      'resume-index resolution ran as a Temporal activity'
    ).toBeVisible();
    await expect(
      page.getByText('ExecuteStep').first(),
      'workflow steps ran as Temporal activities'
    ).toBeVisible();

    // Terminal result recorded in Temporal history matches the runner's
    // workflow: Completed after the `publish` step.
    await expect(
      page
        .getByText('{"kind":"COMPLETED","lastStepName":"publish","reason":null}')
        .first(),
      'the terminal result in Temporal history is Completed at publish'
    ).toBeVisible();
  });
});
