# Deep-Agent Harness

The deep-agent harness *completes* an agent: on top of whatever endpoint or
agent you already have, it adds long-term memory, a prompt-cache default, and
selectable conversation compaction — and reports each primitive's *actual*
runtime state (never configuration intent) at `/api/console/info`. The engine is
`org.atmosphere.ai.preset.DeepAgentPreset`: a flag-and-attribute-driven preset,
not a new annotation to learn.

Turn it on three ways:

- **(a) One full agent** — add `deepAgent = true` to an `@Agent`:

  ```java
  @Agent(name = "research-agent", deepAgent = true, endpoint = "/atmosphere/a2a/research")
  public class ResearchAgent { /* @AgentSkill handlers */ }
  ```

- **(b) App-wide** — turn the harness on for every `@AiEndpoint` and `@Agent`:

  ```yaml
  atmosphere:
    ai:
      deep-agent:
        enabled: true
        exclude-paths: /atmosphere/internal-webhook   # optional carve-outs
  ```

- **(c) A bare memory-bearing endpoint** — a plain `@AiEndpoint` plus that flag.
  No per-class wiring; the flag makes the framework attach the harness. (The
  `spring-boot-personal-assistant` sample's `UpstreamMcpAgent` is exactly this.)

With no further configuration the preset completes an agent with:

| Primitive | What the preset does |
|---|---|
| Conversation memory | Multi-turn history on, sliding-window compaction by default. |
| Long-term memory | Attaches a `LongTermMemoryInterceptor`: facts extracted at session close, recalled into the system prompt on the next connection. Store resolution: a bridged `LongTermMemory` bean → a `ServiceLoader` `LongTermMemoryProvider` → the zero-dep `InMemoryLongTermMemory` fallback. |
| Prompt-cache default | Seeds a `CONSERVATIVE` `CacheHint` (wire emission still gated by the tri-state `atmosphere.ai.prompt-cache-key` mode and its default-deny host allow-list). |
| Delegation | On a `@Coordinator`, a built-in `delegate_task` tool plus default-on outbound-dispatch governance. |
| Durable runs | Consumed when a container installs the journal spine. |
| Skills | `META-INF/skills/<name>/SKILL.md` convention loading. |

## Runtime truth

The harness never advertises a primitive it did not actually activate.
`DeepAgentPreset.install(...)` publishes a per-primitive runtime-state map that
the console surfaces at `/api/console/info` under `deepAgent`:

```json
"deepAgent": {
  "conversation-memory": "ACTIVE",
  "long-term-memory": "ACTIVE(org.atmosphere.ai.memory.InMemoryLongTermMemory)",
  "prompt-cache-default": "conservative",
  "compaction": "sliding-window",
  "skills": "CONVENTION",
  "durable-runs": "CONTAINER-MANAGED",
  "delegation": "INACTIVE(disabled)"
}
```

`ACTIVE` / `INACTIVE(reason)` / `CONVENTION` reflect what an endpoint got, not
what was configured — a long-term-memory store only reads `ACTIVE(<class>)` once
the interceptor is genuinely attached.

## Selectable compaction

Beyond the default sliding window, select LLM-backed summarizing compaction
framework-wide:

```
-Dorg.atmosphere.ai.compaction=summarizing
```

`LlmSummarizingCompaction` condenses evicted turns via the resolved
`AgentRuntime` and falls back to local truncation on any error.

## Sandboxed code execution

Code execution stays **default-off** (security posture unchanged). Enable it and
the `code_exec` tool is offered once a container engine is confirmed present:

```
-Dorg.atmosphere.ai.code.enabled=true
```

Separately, `@SandboxTool` on a tool method routes that one tool through an
isolated `Sandbox` (provisioned per invocation, framework-closed on every
terminal path, fail-closed when no backend is available) — see
[`modules/sandbox`](../modules/sandbox/README.md).

## Mode parity

The harness registers identically across containers via one engine,
`DeepAgentPreset.install(AtmosphereFramework)`; the containers only bridge config
into framework init-params. Spring Boot binds `atmosphere.ai.deep-agent.*`;
Quarkus binds `quarkus.atmosphere.ai.deep-agent.*`; plain servlet reads the
`org.atmosphere.ai.deep-agent.*` init-params. The `@Agent(deepAgent = true)`
attribute opts a single agent in on all three.

## Batteries-included dependency

`atmosphere-ai-spring-boot-starter` bundles the modules the primitives resolve
against — `atmosphere-ai`, `-agent`, `-coordinator`, `-checkpoint`, `-skills`,
and `-sandbox` — so a primitive lights up when it is used rather than staying
dark for a missing jar.

## Example

`samples/spring-boot-personal-assistant` turns the harness on app-wide
(`atmosphere.ai.deep-agent.enabled=true`), so its plain `@AiEndpoint`
`UpstreamMcpAgent` gains long-term memory with no per-class wiring — replacing
the sample's former three-class static-holder wiring — while its `ResearchAgent`
crew member opts in on its own with `@Agent(deepAgent = true)`. The sample's
`LongTermMemoryConsumerTest` pins cross-session fact recall through the preset,
and its README walks the full flow.
