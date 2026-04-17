# atmosphere-sandbox

Isolated-execution primitive for Atmosphere agents. Agents that run
untrusted code, LLM-generated shell commands, or data transforms route
those calls through a `Sandbox` instead of the hosting JVM.

## SPI

`Sandbox` — per-instance resource with `exec`, `writeFile`, `readFile`,
optional `expose(port)`, `snapshot()`, `hibernate()`.
`SandboxProvider` — factory, ServiceLoader-discovered.
`@SandboxTool` — annotation that binds a tool method to a sandbox backend.

## Backends shipped in-tree

- `DockerSandboxProvider` — default for production. Shells out to the
  `docker` CLI. Requires a running Docker daemon; fails hard when absent.
- `InProcessSandboxProvider` — dev-only reference implementation. Runs
  commands in a tempdir via `ProcessBuilder`. NOT a security boundary.

## Third-party backends

Firecracker, Kata, Vercel Sandbox, E2B, Modal, Blaxel ship in separate
modules that implement `SandboxProvider`. The foundation stays
dependency-free.
