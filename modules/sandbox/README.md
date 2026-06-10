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
| `@SandboxTool` | Annotation that binds an `@AiTool` method to a specific provider's capabilities. |

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
foundation stays dependency-free.

## Minimal usage

The `spring-boot-coding-agent` sample resolves a provider through
`ServiceLoader` (picking the first `isAvailable()` backend) and drives it
with the real SPI — there is no fluent builder, `allocate`, or varargs
`exec`:

```java
@Agent(name = "coding-agent")
public class CodingAgent {

    // Discover a backend via ServiceLoader; pick the first available one.
    private static SandboxProvider resolveProvider() {
        for (var provider : ServiceLoader.load(SandboxProvider.class)) {
            if (provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    public String readFile(String gitUrl, String path) {
        var provider = resolveProvider();
        // SandboxLimits is a record: (cpuFraction, memoryBytes, wallTime, networkPolicy).
        // Cloning needs egress, so override the default NONE policy.
        var limits = new SandboxLimits(
                1.0, 512L * 1024L * 1024L, Duration.ofSeconds(30),
                NetworkPolicy.FULL);
        try (Sandbox sandbox = provider.create("alpine:3.20", limits,
                Map.of("owner", "coding-agent"))) {
            // exec takes a List<String> command and a per-call timeout.
            sandbox.exec(
                    List.of("git", "clone", "--depth", "1", gitUrl, "/workspace"),
                    Duration.ofMinutes(2));
            return sandbox.readFile(Path.of("/workspace/" + path));
        }
    }
}
```

The `try-with-resources` close terminates the sandbox (container stop,
volumes unmounted, tempdir removed). Callers never need to worry about
leaks — the provider's lifecycle is tied to the `Sandbox` handle.

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
  Note: no production code consumes `@SandboxTool` yet; the sample wires
  limits and the policy directly via the `SandboxProvider` SPI.

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
- Regression for the command-injection hardening lives in
  `spring-boot-coding-agent`'s clone/read Playwright spec. The
  `foundation-e2e.yml` workflow used to skip this with
  `SKIP_SANDBOX_E2E=true`; that flag was removed in Phase 6 so the
  hardening stays gated on every PR.

## Related reading

- Inner SPI notes: [`src/main/java/org/atmosphere/ai/sandbox/README.md`](src/main/java/org/atmosphere/ai/sandbox/README.md)
- Correctness Invariants: root [`AGENTS.md`](../../AGENTS.md) §
  "Correctness Invariants (Blocking)" — sandbox lifecycle lands under
  Ownership (#1), Terminal Paths (#2), Backpressure (#3), Boundary
  Safety (#4), Security (#6).
