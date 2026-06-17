export const meta = {
  name: 'doc-drift-audit',
  description: 'Audit docs (both repos) for the SEMANTIC drift classes grep cannot gate: bad citations, unverified superlatives, shipped-without-consumer claims, stale subset enumerations, wrong business facts, capability contradictions',
  whenToUse: 'On-demand / scheduled deep doc audit. The strict cross-repo (atmosphere + atmosphere.github.io) enforcement vehicle for the claim classes the pre-push validators deliberately leave advisory. Pass args to scope: "main", "site", or "all" (default).',
  phases: [
    { title: 'Inventory', detail: 'enumerate doc surfaces across both repos, group by area' },
    { title: 'Audit', detail: 'one agent per group reads prose against code, flags semantic drift' },
    { title: 'Verify', detail: 'adversarially refute each finding; drop the ones that survive as correct/historical' },
    { title: 'Synthesize', detail: 'report confirmed drift + which classes admit a new structural gate' },
  ],
}

// ── Scope ─────────────────────────────────────────────────────────────────
const scope = (typeof args === 'string' && args.trim()) ? args.trim().toLowerCase() : 'all'

// ── Schemas ───────────────────────────────────────────────────────────────
const INVENTORY_SCHEMA = {
  type: 'object',
  properties: {
    groups: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string', description: 'short area label, e.g. "module-readmes" or "website-components"' },
          repo: { type: 'string', enum: ['main', 'site'] },
          files: { type: 'array', items: { type: 'string' }, description: 'absolute or repo-relative paths' },
        },
        required: ['name', 'repo', 'files'],
      },
    },
  },
  required: ['groups'],
}

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          line: { type: 'integer' },
          claim: { type: 'string', description: 'the exact sentence/phrase as written' },
          drift_class: {
            type: 'string',
            enum: ['citation', 'superlative', 'shipped_no_consumer', 'subset_enumeration',
                   'business_fact', 'capability_contradiction', 'other'],
          },
          evidence: { type: 'string', description: 'what the code/git/registry actually says, with the command run' },
          suggested_fix: { type: 'string' },
        },
        required: ['file', 'claim', 'drift_class', 'evidence'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    confirmed: { type: 'boolean', description: 'true only if this is REAL drift; false if the claim is actually correct or legitimately historical' },
    reasoning: { type: 'string' },
    corrected_truth: { type: 'string', description: 'the verified ground truth, if confirmed' },
    gateable: { type: 'boolean', description: 'could a deterministic grep/structural gate catch this class going forward?' },
  },
  required: ['confirmed', 'reasoning'],
}

// ── Inventory ───────────────────────────────────────────────────────────────
phase('Inventory')
const inventory = await agent(
  `Enumerate the documentation surfaces to audit. Scope = "${scope}".

   MAIN repo (cwd is the repo root): run \`git ls-files '*.md'\`.
   SITE repo (sibling): locate it at $ATMOSPHERE_SITE_DIR, else ../atmosphere.github.io,
   else ~/workspace/atmosphere/atmosphere.github.io; if present, list its
   *.astro/*.md/*.mdx (exclude node_modules/.git/dist/.astro/build).

   If scope is "main", include only main-repo files; if "site", only the sibling;
   if "all", both.

   Group the files into 6–14 coherent audit groups by area (e.g. top-level-readme,
   module-readmes-ai, module-readmes-transport, docs-guides, docs-tutorial,
   website-components, website-integration-docs, changelog-and-whats-new). Keep each
   group to a workable size (roughly <= 12 files). Return the groups.`,
  { schema: INVENTORY_SCHEMA, phase: 'Inventory', label: 'inventory' },
)

const groups = (inventory && inventory.groups) ? inventory.groups : []
log(`inventory: ${groups.length} groups, ${groups.reduce((n, g) => n + (g.files?.length || 0), 0)} files`)

// ── Audit → Verify (pipelined per group) ────────────────────────────────────
const AUDIT_CHECKLIST = `
You are auditing documentation prose against the actual codebase for SEMANTIC drift
that no grep gate can catch. For EACH file, read it, then verify its factual claims
against the code/git/registry. Flag only real problems. The six drift classes:

  1. citation            — an external reference mislabeled or moved (e.g. a tutorial
                           cited as the canonical spec; a URL that 301s/404s). Verify
                           with curl -sI when a URL is the claim.
  2. superlative         — "first/only Java framework", "world's first", etc. with no
                           cited survey. Cross-check .harness/facts.json allowed_superlatives.
  3. shipped_no_consumer — "X is shipped/wired/integrated/used by" where X has NO
                           non-test production caller. Verify:
                           git grep '<Symbol>' -- ':!**/test/**' ':!**/*Test.java'
                           Zero qualifying hits on a user-reachable path = drift.
  4. subset_enumeration  — "N of/the-other-N runtimes do X". The denominator gate
                           checks the total; YOU verify the SUBSET numerator N by
                           grepping each runtime's *AgentRuntime.{java,kt} for the call.
  5. business_fact       — dates/years that disagree with .harness/facts.json.
  6. capability_contradiction — a feature/capability claim the code contradicts
                           (e.g. README says a runtime has TOOL_CALLING but its
                           AgentRuntime has no tool-call loop).

For each finding give file, line, the exact claim, drift_class, evidence (INCLUDING the
command you ran and its output), and a suggested fix. Return an empty findings array if
the group is clean. Do NOT flag append-only history (CHANGELOG.md, .harness/drift-log.md,
the site's whats-new.md) or dated audits under docs/audits/ — those legitimately quote
superseded facts.`

// agent() returns null when a subagent dies on a terminal error after retries
// (e.g. server rate-limiting). A failed audit/verify agent must NOT be silently
// read as "no drift" — that is the exact false-green this auditor exists to catch.
// We tag failures so the run reports INCOMPLETE instead of clean.
phase('Audit')
const perGroup = await pipeline(
  groups,
  (g) => agent(
    `${AUDIT_CHECKLIST}\n\nGROUP "${g.name}" (${g.repo} repo). Files:\n${(g.files || []).join('\n')}`,
    { schema: FINDINGS_SCHEMA, phase: 'Audit', label: `audit:${g.name}` },
  ).then((a) => (a === null ? { __failed: true, name: g.name } : a)),
  (audit, g) => {
    if (audit && audit.__failed) return [{ __failed: true, name: g.name }]
    const findings = (audit && audit.findings) ? audit.findings : []
    if (!findings.length) return []
    return parallel(findings.map((f) => () =>
      agent(
        `Adversarially REFUTE this documentation-drift finding. Default to confirmed=false
         unless you can independently reproduce the drift with a command. A claim that is
         actually correct, or legitimately historical (changelog/dated audit), is NOT drift.

         Finding (group "${g.name}"):
         ${JSON.stringify(f, null, 2)}

         Re-run the verification yourself (git grep / curl -sI / read the source / check
         .harness/facts.json). Decide confirmed (real drift) or not, give the corrected
         ground truth if confirmed, and whether a deterministic gate could catch this class.`,
        { schema: VERDICT_SCHEMA, phase: 'Verify', label: `verify:${f.drift_class}` },
      ).then((v) => ({ ...f, verdict: (v === null ? { __failed: true } : v) })),
    ))
  },
)

const flat = perGroup.flat().filter(Boolean)
const failedGroups = flat.filter((x) => x && x.__failed).map((x) => x.name)
const verifyFailed = flat.filter((x) => x.verdict && x.verdict.__failed).length
const confirmed = flat.filter((f) => f.verdict && f.verdict.confirmed === true)
const inventoryFailed = inventory === null || !groups.length
const incomplete = inventoryFailed || failedGroups.length > 0 || verifyFailed > 0
log(`confirmed: ${confirmed.length} | failed audit groups: ${failedGroups.length} | failed verifies: ${verifyFailed} | incomplete: ${incomplete}`)

// COMPLETENESS GUARD: never report "clean" on partial coverage. If any agent
// failed (inventory, an audit group, or a verification), the result is
// INCOMPLETE — the caller must re-run (spaced out, or scope smaller / per-group
// to stay under server rate limits), not treat it as drift-free.
if (incomplete) {
  return {
    scope,
    groups: groups.length,
    incomplete: true,
    inventoryFailed,
    failedGroups,
    verifyFailed,
    confirmedSoFar: confirmed,
    summary: `INCOMPLETE — coverage is PARTIAL (most likely server rate-limiting). `
      + `${inventoryFailed ? 'inventory failed; ' : ''}`
      + `${failedGroups.length} audit group(s) failed${failedGroups.length ? ' [' + failedGroups.join(', ') + ']' : ''}; `
      + `${verifyFailed} verification(s) failed. Do NOT treat as clean. `
      + `Re-run spaced out, or run scope="main"/"site" or one group at a time. `
      + `${confirmed.length} finding(s) confirmed before the failures.`,
  }
}

if (!confirmed.length) {
  return {
    scope,
    groups: groups.length,
    incomplete: false,
    confirmed: [],
    summary: 'No confirmed documentation drift (full coverage — every audit and verification agent completed).',
  }
}

// ── Synthesize ──────────────────────────────────────────────────────────────
phase('Synthesize')
const report = await agent(
  `Write a concise Markdown report of confirmed documentation drift. Group by drift_class.
   For each finding: file:line, the claim (quoted), the verified ground truth, and the fix.
   End with a "Gate proposals" section: for any class where verdict.gateable is true and no
   existing scripts/validate-*.sh covers it, propose the specific structural gate (this feeds
   the drift-log's open-gate backlog). Do not invent findings beyond the data below.

   Confirmed findings:
   ${JSON.stringify(confirmed, null, 2)}`,
  { phase: 'Synthesize', label: 'synthesize' },
)

return { scope, groups: groups.length, incomplete: false, confirmedCount: confirmed.length, confirmed, report }
