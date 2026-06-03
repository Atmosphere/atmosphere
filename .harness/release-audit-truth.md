# Release-Audit Source-of-Truth Count Ledger

Pinned during the 4.x release-readiness honesty audit. Every count-stating file and the
release announcements MUST cite these numbers. Each row names the authoritative source —
the file the audit treats as ground truth when prose disagrees.

Version at audit time: **4.0.50-SNAPSHOT**.

| Fact | Count | Authoritative source | Notes |
|------|-------|----------------------|-------|
| Sample applications | **26** | `samples/` dirs == `cli/samples.json` entries | Both enumerate the identical 26 names; in sync. |
| CLI scaffolding templates | **13** | `cli/atmosphere` `cmd_new()` `case "$template"` block | chat, ai-chat, ai-tools, mcp-server, rag, agent, multi-agent, classroom, ms-governance, coding-agent, guarded-agent, assistant, browser-agent |
| CLI runtime overlays (`--runtime` values) | **12** | `cli/runtime-overlays.json` → `overlays` keys | adk, agentscope, anthropic, builtin, cohere, crewai, embabel, koog, langchain4j, semantic-kernel, spring-ai, spring-ai-alibaba |
| AgentRuntime adapters | **12** | `.harness/capabilities.snapshot.json` → `runtimes.count` (+ 12 `*RuntimeContractTest`) | Adk, AgentScope, Anthropic, BuiltIn, Cohere, CrewAi, Embabel, Koog, LangChain4j, SemanticKernel, SpringAi, SpringAiAlibaba |
| `AiCapability` enum values | **20** | `.harness/capabilities.snapshot.json` → `capabilities.count` | Pinned by `CapabilitySnapshotTest`. |
| Maven module directories | **50** | `find modules -maxdepth 1 -mindepth 1 -type d` | Not a marketing number; informational only. |

## Drifts found & resolved during count reconciliation (auto-fix, mechanical)

1. **`cli/README.md:69` — template list wrong.** Listed `koog` and `embabel` as templates
   (they are *runtimes*, not templates) and omitted `ms-governance`, `coding-agent`,
   `guarded-agent`, `assistant`. Corrected to the 13-template set above (source: the
   `cmd_new` case statement). Residual of the runtime/template confusion class.
2. **`cli/README.md:71` — runtime list incomplete.** Listed 9 runtimes, omitting `anthropic`,
   `cohere`, `crewai` — all three have overlays in `cli/runtime-overlays.json` and are valid
   `--runtime` values. Corrected to the 12-runtime set above. This is the un-swept prose
   residual of **drift-log #59** (which fixed the overlay JSON, BOM, and parent pom but not
   this README sentence).

Both fixes are mechanical sweeps of prose against an authoritative machine-readable source
(the shell `case` block and the overlays JSON), not judgment calls.
