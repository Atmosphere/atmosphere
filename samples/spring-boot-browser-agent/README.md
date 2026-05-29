# Spring Boot — Code-as-Action Browser Agent

The AI model is given **one tool — `code_exec`** — and accomplishes web tasks by
*writing Playwright code* that drives a headless browser, instead of predicting
individual clicks. Each round of code runs in an **isolated, ephemeral container**;
the screenshots it produces stream back to the Atmosphere Console **live** as the
agent works.

This is the "a terminal is all you need" idea applied to Atmosphere: the durable
artifact is the code the agent writes and the logs/screenshots it produces, and
Atmosphere's real-time transport turns the agent's session into something you can
*watch*.

## What it demonstrates

- **Code-as-action** — the model writes a block of Playwright/JS per step rather
  than negotiating many fine-grained tool calls. The framework lifts the tool-loop
  ceiling so the agent can iterate write → run → observe → revise many times.
- **Sandboxed execution** — every session gets a throwaway container with
  hardening applied (non-root, `--cap-drop ALL`, `no-new-privileges`, memory/cpu/pid
  caps, read-only rootfs + a bounded tmpfs workspace). The container is torn down on
  every terminal path (success, error, disconnect, timeout).
- **Live streaming** — each `code_exec` round emits an `AgentStep` event and streams
  any screenshots the code saved as image content frames, which the Console renders
  inline.

## Prerequisites

- **A container engine** (Docker or Podman) running locally. The sandbox pulls
  `mcr.microsoft.com/playwright:v1.60.0-noble` on first use (~2 GB, one-time).
- **A Cohere API key.** Set `COHERE_API_KEY`. The default model is
  `command-a-03-2025`. The sample bundles the Cohere `AgentRuntime` (priority
  100), so the SPI resolver forces Cohere over the built-in fallback. Cohere's
  Command models support tool calling, which `code_exec` requires — and unlike a
  free-tier Gemini key they don't rate-limit the multi-round code-action loop.

## Run

```bash
export COHERE_API_KEY=...
# ensure Docker is running: docker info
./mvnw spring-boot:run -pl samples/spring-boot-browser-agent
```

Open <http://localhost:8090/> — it redirects to the Atmosphere Console. Ask the
agent a web task, for example:

> What is the top story on news.ycombinator.com right now? Show me a screenshot.

Watch the Console: you'll see each `code_exec` step appear, the agent's logs, and
the screenshots stream in as it browses.

## Security posture

Code execution is **disabled by default** in Atmosphere (default-deny). This sample
turns it on **on purpose** — it is the feature being demonstrated — in
`BrowserAgentApplication.main()`, and logs a prominent warning at startup. It also
opts the sandbox into outbound network (`bridge`), because a browser agent must
reach the web; the product default is `none` (no network at all).

For a real deployment you would:

- Opt in explicitly (`org.atmosphere.ai.code.enabled=true`) and document it.
- Choose a network policy deliberately (`org.atmosphere.ai.code.network`) — an
  allowlisted proxy rather than open `bridge` if the agent only needs specific hosts.
- Pin and vet the sandbox image, and set tighter resource caps for your workload.

Set `ATMO_CODE_EXEC=false` to run the endpoint with code execution off (the agent
then explains it needs the sandbox).

## Configuration

The sandbox reads `org.atmosphere.ai.code.*` system properties / environment
variables (default-deny; hardened defaults):

| Property | Default | Meaning |
|----------|---------|---------|
| `org.atmosphere.ai.code.enabled` | `false` (this sample sets `true`) | Master switch |
| `org.atmosphere.ai.code.image` | `mcr.microsoft.com/playwright:v1.60.0-noble` | Sandbox image |
| `org.atmosphere.ai.code.network` | `none` (this sample sets `bridge`) | Container network mode |
| `org.atmosphere.ai.code.memory` | `512m` | Memory cap |
| `org.atmosphere.ai.code.cpus` | `1.0` | CPU cap |
| `org.atmosphere.ai.code.execTimeoutSeconds` | `60` | Per-command timeout |
| `org.atmosphere.ai.code.sandboxTtlSeconds` | `300` | Max sandbox lifetime |

## Key code

- `BrowserAgentApplication` — enables code execution + outbound network for the demo
  (with a startup warning), redirects `/` to the Console.
- `BrowserAgent` — the `@AiEndpoint`. Its system prompt hands the model the
  `code_exec` tool and asks it to drive a browser via Playwright, saving screenshots
  to `/workspace/artifacts/`. The `code_exec` tool itself is registered automatically
  by the framework when code execution is enabled — it is not declared in `tools()`.
