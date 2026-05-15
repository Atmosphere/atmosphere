# quarkus-ai-chat — mirroir-run replay manifest

AI chat on Quarkus + Atmosphere AI (LangChain4j-backed). `@AiEndpoint` at
`/atmosphere/ai-chat`. Sample pins `quarkus.http.port=18810` to avoid colliding with
`spring-boot-ai-chat`'s 8080 (so both can be replayed side-by-side in a single CI lane).

```yaml
version: 1
name: quarkus-ai-chat
description: |
  archetype: chat-streaming
  runtime: quarkus + langchain4j
  required scenarios: stream, fallback
session:
  boot_once: true
  boot_ready_port: 18810
  boot_ready_timeout_s: 240
  boot:
    command: "./mvnw -q quarkus:dev -pl samples/quarkus-ai-chat -Dquarkus.console.enabled=false"
    cwd: "../.."
    env:
      QUARKUS_LANGCHAIN4J_OPENAI_API_KEY: "fake"
      ATMOSPHERE_AUTH_ENABLED: "false"
  scenarios:
    must_pass:
      - scenarios/stream.yaml
      - scenarios/fallback.yaml
```

`QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=fake` keeps Quarkus's LangChain4j config bound to
something non-empty so the AI endpoint compiles cleanly even though we never call the real
provider — the pilot exercises the wire path, not real LLM scoring. Phase D will swap to
`LLM_MODE=local` + Ollama for semantic judging.
