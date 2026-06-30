# atmosphere-ai-spring-boot-starter

AI-inclusive Spring Boot starter for Atmosphere. **One dependency → a running
`@Agent` chat app.**

## Why this exists

The base `atmosphere-spring-boot-starter` is transport-first by design: it
declares the AI layer (`atmosphere-ai`, `atmosphere-agent`,
`atmosphere-coordinator`) as `<optional>true</optional>`. That keeps the
transport starter lean, but it means the base starter **alone cannot run an
`@Agent`** — an app would also have to add the AI modules by hand.

This starter is a thin dependency aggregator (no business code, like any Spring
Boot `*-starter`) that pins the full AI layer **non-optionally** on top of the
base starter, so adding this one dependency is genuinely enough.

## What it pulls in (all non-optional)

| Dependency | What it brings |
|---|---|
| `atmosphere-spring-boot-starter` | Framework runtime, the Spring Boot auto-configuration, and the Atmosphere Console static assets |
| `atmosphere-ai` | AI pipeline, `AiConfig`, and the built-in `AgentRuntime` discovery the `@Agent` processor needs |
| `atmosphere-agent` | The `@Agent` annotation and its `AgentProcessor` |
| `atmosphere-coordinator` | `@Coordinator` / `AgentFleet` multi-agent composition |

Because the base starter's AI auto-configurations
(`AtmosphereAiAutoConfiguration`, `AtmosphereCoordinatorAutoConfiguration`, the
admin AI/coordinator controllers) are gated `@ConditionalOnClass` on those
modules, pulling them in non-optionally is all it takes to light the AI layer up
— there is no new auto-configuration code here.

## Usage

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-spring-boot-starter</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
<!-- a servlet web host, e.g. spring-boot-starter-web -->
```

```java
@Agent(name = "assistant")
public class AssistantAgent {
    @Prompt
    public void onMessage(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

Point the framework's annotation scanner at your agent package
(`atmosphere.packages=com.example.agents`), set an LLM key
(`atmosphere.ai.api-key` / `LLM_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY`),
and the agent is live at `/atmosphere/agent/assistant` with the Console at
`/atmosphere/console/`.

## Proof: the one-dependency promise is tested, not asserted

`OneDependencyAgentWiringTest` boots a `@SpringBootTest` whose **only**
Atmosphere dependency is this starter, registers a single `@Agent` class, and
asserts live wiring — never classpath presence:

- the `AtmosphereFramework` runtime bean is live;
- `AiConfig.LlmSettings` and `AtmosphereAiEndpointRegistrar` confirm the AI
  pipeline auto-configured;
- the framework's handler registry contains `/atmosphere/agent/oneDepAgent`,
  proving `AgentProcessor` actually processed the `@Agent`;
- `atmosphereConsoleFilter` confirms the Console is served;
- `CoordinatorController` confirms `atmosphere-coordinator` rode in
  non-optionally.

The test deliberately declares no direct dependency on `atmosphere-ai` or
`atmosphere-agent`; those types reach it only transitively through this starter.
