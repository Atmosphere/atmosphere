# atmosphere-sandbox

Pluggable isolated-execution primitive for Atmosphere agents. Agents
that run untrusted code, LLM-generated shell commands, or data
transforms route those calls through a `Sandbox` instead of the hosting
JVM.

## SPI

| Type | Role |
|------|------|
| `Sandbox` | Per-instance resource with `exec`, `writeFile`, `readFile`, optional `expose(port)`, `snapshot()`, `hibernate()`. Never leaks to the hosting JVM. |
| `SandboxProvider` | Factory. Discovered via `ServiceLoader` — applications never `new` a provider directly. |
| `SandboxLimits` | Memory / CPU / wall-clock ceilings. Enforced by each backend; exceeding a limit terminates the sandbox. |
| `NetworkPolicy` | Egress policy. `Mode` is `NONE`, `GIT_ONLY`, `ALLOWLIST`, `FULL`; pre-built constants are `NetworkPolicy.NONE`, `NetworkPolicy.GIT_ONLY`, `NetworkPolicy.FULL` (build an allowlist with `NetworkPolicy.allowlist(host...)`). Per-sandbox, not global. |
| `@SandboxTool` | Annotation that routes an `@AiTool` or `@Prompt` method through a named backend: the framework provisions a `Sandbox` per invocation, injects it as a method parameter, and closes it afterwards. Implemented by `SandboxToolBinding` via the `ToolSandboxBinding` SPI in `atmosphere-ai`. |

## Backends shipped in-tree

- **`DockerSandboxProvider`** — default for production. Shells out to the
  `docker` CLI with argv-form exec (no shell interpolation), per-call
  `--rm` + `--network none` (unless `NetworkPolicy.FULL` is set), strict
  working-directory mount. Requires a running Docker daemon; fails hard
  when absent (Correctness Invariant #5 — Runtime Truth).
- **`InProcessSandboxProvider`** — dev-only reference implementation.
  Runs commands via `ProcessBuilder` inside a tempdir. **Not a security
  boundary** — gated behind `-Datmosphere.sandbox.insecure=true` and
  emits a WARN at startup. Tests and samples that cannot run Docker
  locally enable it explicitly.

## Third-party backends

Firecracker, Kata, Vercel Sandbox, E2B, Modal, Blaxel ship in separate
modules that implement `SandboxProvider` and register via
`META-INF/services/org.atmosphere.ai.sandbox.SandboxProvider`. The
foundation stays free of third-party SDK dependencies (its only
Atmosphere dependency is `atmosphere-ai`, for the `ToolSandboxBinding`
tool-layer SPI).

## Minimal usage

The `spring-boot-coding-agent` sample is annotation-driven: `@SandboxTool`
on its `@Prompt` method makes the framework provision the sandbox before
the method runs, inject it as the `Sandbox` parameter, and close it when
the method returns — success and exception paths alike. The method never
selects a provider, builds limits, or closes anything:

```java
@Agent(name = "coding-agent")
public class CodingAgent {

    @Prompt
    @SandboxTool(image = "alpine:3.20", network = true)  // backend defaults to "docker"
    public void onPrompt(String message, StreamingSession session, Sandbox sandbox) {
        // exec takes a List<String> command and a per-call timeout.
        sandbox.exec(
                List.of("git", "clone", "--depth", "1", repoUrl, "/workspace/repo"),
                Duration.ofMinutes(2));
        session.send(sandbox.readFile(Path.of("/workspace/repo/README.md")));
        // Do NOT close the sandbox — it is framework-owned (Invariant #1).
    }
}
```

When the named backend is unavailable (Docker daemon down, module missing)
the invocation fails fast with an error naming the backend and how to
enable it; the endpoint keeps running and there is no in-JVM fallback.

For manual wiring (no annotation), resolve a provider through the
tier-aware `Sandboxes.select(IsolationTier)` call — a floor that prefers
the strongest available isolation — build a `SandboxLimits`
(`cpuFraction`, `memoryBytes`, `wallTime`, `networkPolicy`), and drive
`provider.create(image, limits, metadata)` inside `try-with-resources`:
the close terminates the sandbox (container stop, volumes unmounted,
tempdir removed). In manual mode the creator owns the handle.

## Security notes

- Every `exec` is argv-form — no `sh -c` wrapping. The `CodingAgent`
  sample exercises this against a strict GitHub URL regex so shell
  metacharacters cannot escape.
- `DockerSandboxProvider` rejects volume mounts outside the
  per-sandbox workdir; path traversal is blocked by
  `Path.resolve().normalize().startsWith(workdir)`.
- `NetworkPolicy.FULL` is never the default. The `@SandboxTool`
  annotation (members: `backend`, required `image`, `cpuFraction`,
  `memoryBytes`, `wallTimeSeconds`, and `boolean network()` defaulting to
  `false`) opts a tool into egress with `@SandboxTool(image = "ubuntu:24.04", network = true)`
  — the explicit `network = true` acts as the authorization receipt.
  `network` maps only to the enforced modes: `false` → `NONE`,
  `true` → `FULL`. The label-only `GIT_ONLY` / `ALLOWLIST` tiers are not
  reachable from the annotation.
- `@SandboxTool` is consumed in production by the tool layer:
  `SandboxToolBinding` (registered under
  `META-INF/services/org.atmosphere.ai.tool.ToolSandboxBinding`) is picked
  up by `atmosphere-ai`'s `DefaultToolRegistry` (reflective `@AiTool`
  executor) and `PromptMethodInvoker` (`@Prompt` dispatch for the web,
  A2A, and AG-UI paths). The `spring-boot-coding-agent` sample runs on
  this wiring.

## Testing notes

- `SandboxTest` covers both providers in one class. Its
  `dockerProviderReportsAvailabilityHonestly` test asserts
  `DockerSandboxProvider.isAvailable()` returns a truthful boolean without
  throwing (so it passes whether or not the Docker daemon is present —
  e.g. a local macOS developer without Docker Desktop).
- The same `SandboxTest` class exercises `InProcessSandboxProvider` via the
  insecure opt-in: `inProcessProviderStaysUnavailableWithoutExplicitOptIn`
  verifies it is unavailable by default, while
  `inProcessProviderAvailableWhenOptInSet` plus the `inProcessSandbox*`
  exec/file tests set `InProcessSandboxProvider.INSECURE_OPT_IN` — they are
  reference-impl tests, not security tests.
- `SandboxToolBindingTest` pins the `@SandboxTool` annotation contract
  (limits/network mapping, provider-by-name resolution, descriptive
  fail-fast with no fallback, idempotent scope close);
  `SandboxToolIntegrationTest` drives the real `atmosphere-ai` consumers
  (`DefaultToolRegistry`, `PromptMethodInvoker`) against a recording
  test provider — no Docker required.
- Regression for the command-injection hardening lives in
  `e2e/tests/coding-agent.spec.ts`. The
  `foundation-e2e.yml` workflow used to skip this with
  `SKIP_SANDBOX_E2E=true`; that flag was removed in Phase 6 so the
  hardening stays gated on every PR.

## Related reading

- Inner SPI notes: [`src/main/java/org/atmosphere/ai/sandbox/README.md`](src/main/java/org/atmosphere/ai/sandbox/README.md)
- Correctness Invariants: root [`AGENTS.md`](../../AGENTS.md) §
  "Correctness Invariants (Blocking)" — sandbox lifecycle lands under
  Ownership (#1), Terminal Paths (#2), Backpressure (#3), Boundary
  Safety (#4), Security (#6).
