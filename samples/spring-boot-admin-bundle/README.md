# spring-boot-admin-bundle

A minimal Spring Boot host that proves the Atmosphere 4 blog claim (§12):

> "you can pull a single `atmosphere-admin-bundle` that wires the runtime, AI,
> coordinator, RAG, checkpoints, and durable sessions together."

This module's `pom.xml` declares **exactly one `org.atmosphere.*` dependency** —
`atmosphere-admin-bundle`. Everything else (the servlet container, the test
harness, logback) is generic Spring Boot infrastructure, not an Atmosphere
module. So whatever Atmosphere wiring shows up in the application context got
there because the single bundle dependency brought it.

## Why bean-presence is the proof

`atmosphere-admin-bundle` is a `pom`-packaged **dependency aggregator** — its
whole job is wiring. The honest test of "it wires X" is therefore *not* "the X
jar is on the classpath" but "a representative auto-configured **bean** from X is
live in a real Spring `ApplicationContext`." `AdminBundleWiringTest` boots this
app with `@SpringBootTest` and pulls each bean straight out of the context.

## What the bundle actually wires

All six families auto-configure a **live bean** from the bundle alone.
`AdminBundleWiringTest` boots the app and pulls each one straight out of the
context:

| Family | Representative bean asserted | Wired by |
|--------|------------------------------|----------|
| Runtime | `org.atmosphere.cpr.AtmosphereFramework` | `AtmosphereAutoConfiguration` |
| AI | `org.atmosphere.ai.AiConfig.LlmSettings` (AI pipeline config) | `AtmosphereAiAutoConfiguration` |
| Coordinator | `org.atmosphere.admin.coordinator.CoordinatorController` | `AtmosphereAdminAutoConfiguration` (gated on `AgentFleet`, which rode in on the bundle) |
| RAG | `org.atmosphere.ai.ContextProvider` (`InMemoryContextProvider` default) | `AtmosphereContextProviderAutoConfiguration` |
| Checkpoints | `org.atmosphere.checkpoint.CheckpointStore` (`InMemoryCheckpointStore` default) | `AtmosphereCheckpointAutoConfiguration` |
| Durable sessions | `org.atmosphere.session.SessionStore` + `DurableSessionInterceptor` | `DurableSessionAutoConfiguration` (opt-in via `atmosphere.durable-sessions.enabled=true`) |

### Safe defaults, with a startup warning

RAG and checkpoints have no operator-supplied backing bean in this sample, so
their auto-configurations contribute **safe in-memory defaults** and log a
startup `WARN` so the development-grade choice is never mistaken for production:

- **ContextProvider** → an empty `InMemoryContextProvider` (retrieves nothing).
  WARN: *"No ContextProvider configured — using empty in-memory RAG provider…"*
- **CheckpointStore** → an `InMemoryCheckpointStore` (lost on restart).
  WARN: *"No CheckpointStore configured — using in-memory…"*

Both defaults are gated `@ConditionalOnMissingBean`, so an operator who declares
their own `ContextProvider` (a Spring AI `VectorStore` bridge, pgvector, Qdrant…)
or `CheckpointStore` (`SqliteCheckpointStore`, Postgres — exactly as
`spring-boot-checkpoint-agent` does) **replaces** the default rather than adding a
second bean. `ragAndCheckpointDefaultsAreSingleConditionalBeans()` asserts exactly
one bean of each type — the default — confirming that guard.

## Run the test (the proof)

```bash
# from the repo root
./mvnw test -pl samples/spring-boot-admin-bundle \
  -Dtest=AdminBundleWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Run the app

```bash
./mvnw spring-boot:run -pl samples/spring-boot-admin-bundle
# starts on http://localhost:8100 with the admin control plane wired in
```

There is no application code beyond the `@SpringBootApplication` entry point on
purpose — the bundle supplies the wiring, and the test is the deliverable.

## Files

- `AdminBundleApplication.java` — the entry point; nothing but `@SpringBootApplication`.
- `AdminBundleWiringTest.java` — boots the context and asserts all six families wire as live beans.
- `application.properties` — enables durable sessions and boots AI without an LLM key (demo).
