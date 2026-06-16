# spring-boot-coding-agent

Clones a Git repository into a sandbox and reads files. Exercises the primitives the personal-assistant sample does not
touch:

- **`Sandbox`** — every file and command goes through the SPI. Docker is
  the production default; the in-process provider is the dev fallback. The
  sample clones a Git repository into the sandbox and reads the first ~800
  characters of its README.
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

The sample prefers the Docker sandbox backend. If `docker` is on PATH and
the daemon is reachable, operations run inside a fresh `alpine:3.20`
container with 1 CPU / 512 MB / 5 min / no network beyond the initial
clone.

When Docker is absent, the sample falls back to the in-process provider
for demonstration purposes only. **The in-process provider is NOT a
security boundary** — never expose the in-process backend to untrusted
input in production.

## What the agent does

1. Extracts the repo URL from the user message.
2. Provisions a sandbox with default limits.
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
- The sample sets `NetworkPolicy.FULL` so the clone step can reach GitHub.
  A tighter egress posture uses `SandboxLimits.network` as a
  `NetworkPolicy` (`NONE` / `GIT_ONLY` / `ALLOWLIST`) so the clone can
  reach GitHub while downstream tool calls cannot reach the wider internet.

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
