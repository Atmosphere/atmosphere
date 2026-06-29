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

`AdminBundleWiringTest` splits the six families into what the bundle proves at
two honesty levels:

### Four families auto-configure a live bean from the bundle alone

| Family | Representative bean asserted | Wired by |
|--------|------------------------------|----------|
| Runtime | `org.atmosphere.cpr.AtmosphereFramework` | `AtmosphereAutoConfiguration` |
| AI | `org.atmosphere.ai.AiConfig.LlmSettings` (AI pipeline config) | `AtmosphereAiAutoConfiguration` |
| Coordinator | `org.atmosphere.admin.coordinator.CoordinatorController` | `AtmosphereAdminAutoConfiguration` (gated on `AgentFleet`, which rode in on the bundle) |
| Durable sessions | `org.atmosphere.session.SessionStore` + `DurableSessionInterceptor` | `DurableSessionAutoConfiguration` (opt-in via `atmosphere.durable-sessions.enabled=true`) |

### Two families are aggregated as SPIs but need one operator-supplied bean

RAG and checkpoints are genuinely brought onto the classpath by the bundle —
their SPI type and a concrete in-tree implementation are loadable — but neither
**auto-configures a live bean** from the bundle alone:

| Family | SPI on classpath (from bundle) | Why no auto-wired bean |
|--------|--------------------------------|------------------------|
| RAG | `org.atmosphere.ai.ContextProvider` + `InMemoryContextProvider` | `AtmosphereRagAutoConfiguration` is gated on Spring AI's `VectorStore` (not pulled by the bundle) **and** a `VectorStore` bean. Supply both and a `ContextProvider` bean appears. |
| Checkpoints | `org.atmosphere.checkpoint.CheckpointStore` + `InMemoryCheckpointStore` | There is no Spring auto-configuration for `CheckpointStore`. The operator declares a `@Bean CheckpointStore` (exactly as `spring-boot-checkpoint-agent` does). |

`ragAndCheckpointFamiliesAreAggregatedButNotAutoWired()` asserts that true state
— the SPI loads from the bundle classpath, and the context holds **zero** beans
of that type — instead of pretending an auto-configured bean exists. That keeps
the sample honest about what "wires together" means for these two families: the
bundle does the aggregation; activation takes one more bean you own.

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
- `AdminBundleWiringTest.java` — boots the context and asserts the wired beans / aggregated SPIs.
- `application.properties` — enables durable sessions and boots AI without an LLM key (demo).
