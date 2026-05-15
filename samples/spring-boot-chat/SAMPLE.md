# spring-boot-chat — mirroir-run replay manifest

Plain real-time chat (no AI) over Atmosphere's `@ManagedService`. The Vue console UI bundled
in `atmosphere-spring-boot-starter` is the chat surface; `console-endpoint: /atmosphere/chat`
in `application.yml` points it at this sample's handler. WebSocket primary with SSE /
long-poll graceful fallback for replay across chromium + firefox + webkit.

```yaml
version: 1
name: spring-boot-chat
description: |
  archetype: chat
  runtime: spring-boot-4
  required scenarios: stream, fallback
session:
  boot_once: true
  boot_ready_port: 8080
  boot_ready_timeout_s: 240
  boot:
    command: "./mvnw -q spring-boot:run -pl samples/spring-boot-chat"
    cwd: "../.."
    env:
      ATMOSPHERE_AUTH_ENABLED: "false"
  scenarios:
    must_pass:
      - scenarios/stream.yaml
      - scenarios/fallback.yaml
```

`ATMOSPHERE_AUTH_ENABLED=false` matches the pilot sample's posture (auth gate disabled for
replay; the Vue console's `authToken` is commented out in
`modules/spring-boot-starter/frontend/src/App.vue`, so the default auth-on boot returns
401 on the console pageload). A Phase F scenario will exercise the auth round-trip.
