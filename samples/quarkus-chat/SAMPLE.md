# quarkus-chat — mirroir-run replay manifest

Plain chat (no AI) on Quarkus via `@ManagedService(path = "/atmosphere/chat", ...)`. The
Atmosphere admin/console UI is served by `quarkus-admin-extension`. Quarkus default HTTP
port is `8080`. The runner boots via `mvn quarkus:dev`, which compiles + hot-reloads the
sample in dev mode (live-reload server stays open until SIGTERM).

```yaml
version: 1
name: quarkus-chat
description: |
  archetype: chat
  runtime: quarkus
  required scenarios: stream, fallback
session:
  boot_once: true
  boot_ready_port: 8080
  boot_ready_timeout_s: 240
  boot:
    command: "./mvnw -q quarkus:dev -pl samples/quarkus-chat -Dquarkus.console.enabled=false"
    cwd: "../.."
    env:
      ATMOSPHERE_AUTH_ENABLED: "false"
  scenarios:
    must_pass:
      - scenarios/stream.yaml
      - scenarios/fallback.yaml
```

`quarkus.console.enabled=false` disables Quarkus dev-mode's interactive TUI so the boot
subprocess can run unattended in CI. Auth is disabled for replay; see the spring-boot-ai-chat
SAMPLE.md for the rationale (Phase F adds the auth-round-trip scenario).
