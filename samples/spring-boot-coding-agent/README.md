# spring-boot-coding-agent

Clones a Git repository into a sandbox and reads files. Exercises the primitives the personal-assistant sample does not
touch:

- **`@SandboxTool` + `Sandbox`** — the `@Prompt` method is annotated
  `@SandboxTool(image = "alpine:3.20", network = true)`; the framework
  provisions a Docker sandbox per invocation, injects it as the method's
  `Sandbox` parameter, and closes it when the method returns. Every file
  and command goes through the SPI. The sample clones a Git repository
  into the sandbox and reads the first ~800 characters of its README.
- **`RunRegistry` reattach** — not wired in this sample. The primitive that
  lets a disconnected client reattach by `runId` lives in `modules/agent`.

## Running

```bash
# Build
./mvnw compile -pl samples/spring-boot-coding-agent

# Run with an OpenAI key (optional — sample works without)
export OPENAI_API_KEY=sk-your-key

# Start the sample
./mvnw spring-boot:run -pl samples/spring-boot-coding-agent

# Open http://localhost:8081 and ask:
#   "clone https://github.com/atmosphere/atmosphere.git and read README.md"
```

## Sandbox requirements

The sample requires the Docker sandbox backend: `@SandboxTool` defaults to
`backend = "docker"` and fails fast — by design, with no in-JVM fallback —
when the backend is unavailable. If `docker` is on PATH and the daemon is
reachable, each prompt runs inside a fresh `alpine:3.20` container with
1 CPU / 512 MB / 5 min limits and network egress enabled for the clone.

When Docker is absent, the prompt fails with a descriptive error naming
the backend and how to enable it; the endpoint keeps running. The dev-only
in-process provider can be selected explicitly with
`@SandboxTool(backend = "in-process", ...)` plus
`-Datmosphere.sandbox.insecure=true`, but **it is NOT a security
boundary** — never expose it to untrusted input in production.

## What the agent does

1. The framework provisions the `@SandboxTool` sandbox and injects it into
   the `@Prompt` method (the method never creates or closes it — the
   sandbox is framework-owned).
2. The agent extracts the repo URL from the user message (strict GitHub
   URL allowlist).
3. Clones the repository at depth 1.
4. Reads `README.md` (or `README`) and returns the first 800 characters.

The sample reads a file; it does not generate a patch. The `@SandboxTool`
annotation wires a method to a sandbox backend, and `Sandbox.writeFile` +
`Sandbox.exec(List.of("git", "diff", ...))` are the primitives a patch flow
would build on.

## Notes

- The sample does not `git commit` the proposed patch. A production
  coding agent pairs this flow with `PermissionMode.PLAN` from the
  `AgentIdentity` primitive so the user approves before the commit
  lands.
- The sample sets `@SandboxTool(network = true)` (`NetworkPolicy.FULL`) so
  the clone step can reach GitHub. The annotation exposes only the two
  enforced modes (`false` → `NONE`, `true` → `FULL`); a tighter egress
  posture (`GIT_ONLY` / `ALLOWLIST`) requires manual `SandboxLimits`
  wiring plus an external egress firewall that honors the policy labels.

## Planning & workspace files (Console → Workspace tab)

`CodingAgent` declares no `harness` attribute, so it gets the `@Agent`
default — `harness = {Harness.ALL}` — which includes the **PLANNING** and
**FILESYSTEM** primitives. No configuration: at deploy time the framework
registers the built-in plan tool

- `write_todos` — the model passes the full todo list every call
  (`{content, status, activeForm}` items; statuses `pending` /
  `in_progress` / `completed` / `abandoned`), replacing the previous list

and the six built-in file tools over a bounded, conversation-scoped
workspace store:

- `ls`, `read_file`, `glob`, `grep` (read)
- `write_file`, `edit_file` (write — `edit_file` requires `old_text` to
  match exactly once)

No runtime on this sample's classpath advertises a native plan or file
surface, so the `AUTO` mode default lands on the built-in floors and
`GET /api/console/info` reports the attach-time truth:
`"planning": "ACTIVE(builtin)"` and `"filesystem": "ACTIVE(builtin)"` —
runtime state, not config intent.

**What you see.** The Atmosphere Console grows a **Workspace** tab whenever
`GET /api/admin/workspace/owners` lists at least one owner — here
`coding-agent`, with both surfaces attached. The tab renders the agent's
plan as a checklist that ticks live (every `write_todos` call emits a
`plan-update` event) and a file browser over the conversation's workspace,
backed by the read-only admin endpoints
`GET /api/admin/agents/coding-agent/plan?sessionId=...`,
`.../files?sessionId=...` and `.../files/content?sessionId=...&path=...`.
The skill file (`prompts/coding-agent.md`) instructs the model to plan
multi-step work with `write_todos` first and stage cross-step results with
the file tools. Note the sample's built-in clone flow is deterministic (the
`@Prompt` body drives the sandbox directly, no LLM), so that flow does not
write plans itself — the harness surfaces attach and report `ACTIVE`
keylessly, and the tools fire on any model-driven turn.

**Storage & bounds.** Plans persist per conversation under the agent
workspace's `plans/` directory; files under `files/{conversationId}/`.
Every write is bounds-checked with a clear rejection message (defaults:
512 KB per file, 256 files, 16 MB total), `grep` output is capped at
500 hits, and model-supplied paths are validated at the boundary (relative
only — no `..`, no absolute paths).

**Opting down.** Declare `harness = {}` on the `@Agent` to strip the agent
back to a bare loop, or narrow to individual features (e.g.
`harness = {Harness.PLANNING}`). The app-wide kill switch
`atmosphere.ai.harness.enabled=false` (a Spring property) beats every
annotation. The per-primitive surface knobs are JVM system properties /
env vars — `atmosphere.ai.planning` (`LLM_PLANNING`) and
`atmosphere.ai.filesystem` (`LLM_FILESYSTEM`) — accepting `auto`
(default: native surface wins when the runtime advertises one, built-in
floor otherwise), `builtin`, or `native`.

## Stateful Interactions (Console → Interactions tab)

This sample includes `atmosphere-interactions`, so the Atmosphere Console
(`/atmosphere/console/`) gains an **Interactions** tab over
`POST/GET /api/interactions`. A coding task is long-running, so launching it as
a **background** interaction — returning immediately — is the natural pattern;
the run is retrievable after a disconnect and can be **continued** via
`previous_interaction_id`. While it runs, the Console subscribes to the
per-interaction stream (`/atmosphere/interactions-stream?id=<id>`) over WebSocket
and renders each durable `steps[]` entry **live** as it is produced, falling back
to polling the `GET /api/interactions/{id}` snapshot if the socket cannot open.

The mutating endpoints are default-deny (Correctness Invariant #6); for this
local demo `application.yml` sets `atmosphere.interactions.http-write-enabled=true`
and `demo-principal: demo-user` — **never enable either in production.**
