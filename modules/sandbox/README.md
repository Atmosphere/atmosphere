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
| `NetworkPolicy` | `NONE`, `ALLOWLIST`, `FULL`. Per-sandbox, not global. |
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

```java
@Agent(name = "coding-agent")
public class CodingAgent {

    @jakarta.inject.Inject
    private SandboxProvider sandboxes;

    @AiTool(description = "Clone a GitHub repo into a sandbox and read a file")
    @SandboxTool(network = NetworkPolicy.ALLOWLIST,
                 allowlist = { "github.com" })
    public String readFile(
            @Param("url") String gitUrl,
            @Param("path") String path) throws Exception {
        var limits = SandboxLimits.builder()
                .memoryMb(512).cpuQuotaMicros(100_000).wallClockMillis(30_000)
                .build();
        try (var sandbox = sandboxes.allocate(limits)) {
            sandbox.exec("git", "clone", "--depth=1", gitUrl, "/workspace");
            return sandbox.readFile("/workspace/" + path);
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
- `NetworkPolicy.FULL` is never the default. Apps that need it declare
  it explicitly per-tool via `@SandboxTool(network = NetworkPolicy.FULL)`
  — the annotation acts as the authorization receipt.

## Testing notes

- `DockerSandboxProviderTest` is skipped when the Docker daemon is
  unavailable (local macOS developer without Docker Desktop). CI
  (ubuntu-latest) ships with Docker, so the test runs there on every PR.
- `InProcessSandboxProviderTest` runs unconditionally with the insecure
  flag set — it's a reference-impl test, not a security test.
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
