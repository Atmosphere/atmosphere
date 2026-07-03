# Deep-Agent Harness

Atmosphere agents are deep agents out of the box — the harness *completes* an
agent: on top of whatever endpoint or agent you already have, it adds
long-term memory, a prompt-cache default, selectable conversation compaction
and the fleet delegation primitive — and reports each primitive's *actual*
runtime state (never configuration intent) at `/api/console/info`. The engine
is `org.atmosphere.ai.preset.HarnessPreset`, driven by one granular attribute:
`harness()` on `@Agent` and `@AiEndpoint`, typed by the
`org.atmosphere.ai.preset.Harness` enum:

| Feature | What it turns on |
|---|---|
| `Harness.MEMORY` | Conversation memory, the auto-attached long-term-memory interceptor, and the compaction seam. |
| `Harness.CACHE` | Prompt-cache default seeding — endpoints whose annotation keeps `promptCache = NONE` are seeded with a `CONSERVATIVE` policy. |
| `Harness.DELEGATION` | The fleet delegation primitive — the built-in `delegate_task` tool plus the governance wrap on the outbound dispatch edge. |
| `Harness.ALL` | Sentinel that expands to `{MEMORY, CACHE, DELEGATION}` — the full harness. |

## The API surface

- **`@Agent` — batteries-included by default.** `harness()` defaults to
  `{Harness.ALL}`, so a plain `@Agent` gets the full harness with no
  attribute at all:

  ```java
  @Agent(name = "research-agent", endpoint = "/atmosphere/a2a/research")
  public class ResearchAgent { /* @AgentSkill handlers */ }
  ```

  Narrow the set to pick individual features — `@Agent(harness =
  {Harness.MEMORY})` keeps memory but skips prompt-cache seeding and
  delegation — or opt an agent down to a bare loop with
  `@Agent(harness = {})`.

- **`@AiEndpoint` — bare by default, per-endpoint opt-in.** `harness()`
  defaults to `{}`; an endpoint opts in explicitly:

  ```java
  @AiEndpoint(path = "/atmosphere/assistant", harness = {Harness.ALL})
  public class Assistant { /* @Prompt handler */ }
  ```

  (The `spring-boot-personal-assistant` sample's `UpstreamMcpAgent` is
  exactly this — a plain endpoint that gains long-term memory with no
  per-class wiring.)

- **App-wide flag — tri-state.** `atmosphere.ai.harness.enabled` refines the
  annotations:

  | `atmosphere.ai.harness.enabled` | `@Agent` (default `{ALL}`) | `@AiEndpoint` (default `{}`) |
  |---|---|---|
  | unset (the default) | full harness | bare — opts in via `harness = {...}` |
  | `true` | full harness | full harness even when the annotation stays empty |
  | `false` (kill switch) | off | off |

  `false` is the operational / compliance kill switch: harness features stay
  off everywhere, beating every annotation. `@Agent(harness = {})` is an
  explicit per-agent opt-down that even `true` does not override.

- **Path carve-outs.** `atmosphere.ai.harness.exclude-paths` is a
  comma-separated list of exact endpoint paths the harness skips:

  ```yaml
  atmosphere:
    ai:
      harness:
        enabled: true
        exclude-paths: /atmosphere/internal-webhook   # optional carve-outs
  ```

Precedence, strongest first: kill switch (`false`) → `exclude-paths` → the
annotation's `harness()` (any non-empty value, including `@Agent`'s
batteries-included default) → app-wide `true` → off.

`@Coordinator` carries no `harness()` attribute; its delegation primitive
resolves from the app-wide flag.

## What the preset completes

With a feature resolved for a path, the preset completes the agent with:

| Primitive | Feature | What the preset does |
|---|---|---|
| Conversation memory | `MEMORY` | Multi-turn history on, sliding-window compaction by default. |
| Long-term memory | `MEMORY` | Attaches a `LongTermMemoryInterceptor`: facts extracted at session close, recalled into the system prompt on the next connection. Store resolution: a bridged `LongTermMemory` bean → a `ServiceLoader` `LongTermMemoryProvider` → the zero-dep `InMemoryLongTermMemory` fallback. |
| Prompt-cache default | `CACHE` | Seeds a `CONSERVATIVE` `CacheHint` (wire emission still gated by the tri-state `atmosphere.ai.prompt-cache-key` mode and its default-deny host allow-list). |
| Delegation | `DELEGATION` | On a `@Coordinator`, a built-in `delegate_task` tool plus default-on outbound-dispatch governance. |
| Durable runs | — | Consumed when a container installs the journal spine. |
| Skills | — | `META-INF/skills/<name>/SKILL.md` convention loading. |

## Runtime truth

The harness never advertises a primitive it did not actually activate.
`HarnessPreset.install(...)` publishes a per-primitive runtime-state map that
the console surfaces at `/api/console/info` under `harness`:

```json
"harness": {
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
the interceptor is genuinely attached, and a feature absent from a path's
resolved set stays `INACTIVE(disabled)`.

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
`HarnessPreset.install(AtmosphereFramework)`; the containers only bridge config
into framework init-params. Spring Boot binds `atmosphere.ai.harness.*`;
Quarkus binds `quarkus.atmosphere.ai.harness.*`; plain servlet reads the
`org.atmosphere.ai.harness.*` init-params. The `harness()` annotation
attribute behaves identically on all three.

## Batteries-included dependency

`atmosphere-ai-spring-boot-starter` bundles the modules the primitives resolve
against — `atmosphere-ai`, `-agent`, `-coordinator`, `-checkpoint`, `-skills`,
and `-sandbox` — so a primitive lights up when it is used rather than staying
dark for a missing jar.

## Example

`samples/spring-boot-personal-assistant` exercises both annotation surfaces:
its plain `@AiEndpoint` `UpstreamMcpAgent` opts in with
`harness = {Harness.ALL}` and gains long-term memory with no per-class
wiring — replacing the sample's former three-class static-holder wiring —
while its `ResearchAgent` crew member rides the `@Agent` batteries-included
default. The sample's `LongTermMemoryConsumerTest` pins cross-session fact
recall through the preset, and its README walks the full flow.
