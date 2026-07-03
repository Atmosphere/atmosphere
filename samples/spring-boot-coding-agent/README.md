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
