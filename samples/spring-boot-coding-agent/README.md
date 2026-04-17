# spring-boot-coding-agent

Proof sample #2 for the v0.5 foundation primitive set. Clones a Git
repository into a sandbox, reads files, and proposes a patch. Exercises
the primitives that the personal-assistant sample does not touch:

- **`Sandbox`** — every file and command goes through the SPI. Docker is
  the production default; the in-process provider is the dev fallback.
- **`AgentResumeHandle`** — a long-running clone + patch flow registers
  with the `RunRegistry` so a disconnected client can reattach by
  `runId` and replay missed events (wire-in lands in Phase 1.5).

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

Extending to real patch proposals is left to the reader — the `@SandboxTool`
annotation wires a method to a sandbox backend, and `Sandbox.writeFile` +
`Sandbox.exec(List.of("git", "diff", ...))` produce the diff.

## Notes

- The sample does not `git commit` the proposed patch. A production
  coding agent pairs this flow with `PermissionMode.PLAN` from the
  `AgentIdentity` primitive so the user approves before the commit
  lands.
- Egress policy extension (per v0.6 follow-up): swap `SandboxLimits.network`
  from boolean to `NetworkPolicy` (`NONE` / `GIT_ONLY` / `ALLOWLIST`) so
  the clone step can reach GitHub while downstream tool calls cannot reach
  the wider internet.
