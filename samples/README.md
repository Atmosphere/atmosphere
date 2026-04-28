# Atmosphere 4.0 Samples

Example applications demonstrating Atmosphere 4.0 across different deployment targets.

All samples inherit their Atmosphere version from the parent POM. The target stack is Java 21, Spring Boot 4.0.5, Spring Framework 6.2.8, and Quarkus 3.31.3.

### Chat & Messaging

| Sample | Stack | Packaging | Rooms | Metrics | Native Image |
|--------|-------|-----------|-------|---------|-------------|
| [chat](chat/) | Servlet (WAR) | WAR | — | — | — |
| [spring-boot-chat](spring-boot-chat/) | Spring Boot 4.0.5 | JAR | ✅ | ✅ | ✅ |
| [quarkus-chat](quarkus-chat/) | Quarkus 3.31.3 | JAR | — | — | ✅ |
| [embedded-jetty-websocket-chat](embedded-jetty-websocket-chat/) | Embedded Jetty | JAR | — | — | — |
| [grpc-chat](grpc-chat/) | gRPC + Spring Boot | JAR | — | — | — |

### AI / LLM Streaming

| Sample | AI Backend | Tool Calling | Description |
|--------|-----------|-------------|-------------|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | Built-in (Gemini/OpenAI/Ollama) | — | Basic AI streaming with `@AiEndpoint` |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | Built-in / any `AgentRuntime` | `@AiTool` (portable) | Framework-agnostic tool calling pipeline with live `AiEvent` tool activity |
| [spring-boot-koog-chat](spring-boot-koog-chat/) | JetBrains Koog | — | Koog `PromptExecutor` via the `AgentRuntime` SPI |
| [spring-boot-embabel-chat](spring-boot-embabel-chat/) | Embabel GOAP (Kotlin, SB 3.5) | — | Embabel `AgentPlatform` planning via the `AgentRuntime` SPI |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | Built-in | — | Multi-room collaborative AI streaming ([Expo client](spring-boot-ai-classroom/expo-client/)) |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | Built-in + Spring AI VectorStore | `@AiTool` | RAG agent with knowledge base search tools |

### Agents (`@Agent` + `@Command`)

One agent class — slash commands and AI work on Web, Slack, Telegram, Discord, WhatsApp, and Messenger simultaneously.

| Sample | Features | Channels | Description |
|--------|----------|----------|-------------|
| [spring-boot-dentist-agent](spring-boot-dentist-agent/) | `@Agent`, `@Command`, `@AiTool`, skill file | Web + Slack + Telegram | Multi-channel dental emergency agent |
| [spring-boot-channels-chat](spring-boot-channels-chat/) | `@AiEndpoint`, channels | Web + Slack + Telegram + Discord + WhatsApp + Messenger | Omnichannel AI assistant |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | `@Agent`, `@Command`, `@AiTool`, RAG | Web | Knowledge base agent with document search tools |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | `@Coordinator`, `@Agent`, `CheckpointStore` | Web | Durable HITL workflow — approval-gated agent chaining |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | `@Coordinator`, `@Fleet`, A2A, SQLite checkpoints, WebTransport | Web | 5 collaborating agents (CEO + 4 specialists) with parallel/sequential dispatch |
| [spring-boot-personal-assistant](spring-boot-personal-assistant/) | `@Coordinator`, `@Fleet`, `AgentState`, `AgentWorkspace`, `AgentIdentity`, `ToolExtensibilityPoint`, `AiGateway`, `InMemoryProtocolBridge` | Web | Proof sample #1 for the v0.5 foundation primitives. Primary assistant delegates to scheduler / research / drafter crew over InMemoryProtocolBridge; ships an OpenClaw-compatible workspace |
| [spring-boot-coding-agent](spring-boot-coding-agent/) | `@Agent`, `Sandbox`, `AgentResumeHandle` | Web | Proof sample #2 for the v0.5 foundation primitives. Clones a repo into a Docker sandbox, reads files, proposes a patch |

### Agent Protocols

| Sample | Protocol | Description |
|--------|----------|-------------|
| [spring-boot-mcp-server](spring-boot-mcp-server/) | MCP | Model Context Protocol — expose tools, resources, prompts to AI agents |
| [spring-boot-a2a-agent](spring-boot-a2a-agent/) | A2A | Agent-to-Agent — discoverable skills via Agent Card, JSON-RPC 2.0 |
| [spring-boot-agui-chat](spring-boot-agui-chat/) | AG-UI | Agent-User Interaction — stream agent state to frontends via SSE |

### Infrastructure & Integration

| Sample | Stack | Description |
|--------|-------|-------------|
| [spring-boot-durable-sessions](spring-boot-durable-sessions/) | Spring Boot 4.0.5 | Persistent sessions with SQLite/Redis |
| [spring-boot-otel-chat](spring-boot-otel-chat/) | Spring Boot 4.0.5 | OpenTelemetry observability |
| [shared-resources](shared-resources/) | — | Shared static assets (CSS, Grafana dashboard). Not a Maven module — no `pom.xml`. |

## Quick Start

### Atmosphere CLI (recommended)

The fastest way to try any sample:

```bash
# Install the CLI
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh

# Interactive picker — browse samples, pick one, run it
atmosphere install

# Or run a specific sample directly
atmosphere run spring-boot-chat
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key

# List all available samples
atmosphere list
atmosphere list --tag ai

# Scaffold a new project from a sample as a standalone starter
# (sparse-clones the sample and rewrites its pom.xml to resolve the
#  atmosphere-project parent from Maven Central — compiles out of the box)
atmosphere new my-chat-app --template chat
atmosphere new my-ai-app --template ai-chat
atmosphere new my-fleet --template multi-agent
atmosphere new my-classroom --template classroom
```

Available `--template` values: `chat`, `ai-chat`, `ai-tools`, `mcp-server`, `rag`, `agent`, `koog`, `embabel`, `multi-agent`, `classroom`. See [cli/README.md](../cli/README.md#available-templates) for the template-to-sample mapping.

Or with npx (zero install — delegates to the `atmosphere` CLI):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

See [cli/README.md](../cli/README.md) for full CLI documentation.

### Manual Build

Each sample can be built independently:

```bash
# WAR sample (Jetty Maven plugin)
cd chat && mvn clean install && mvn jetty:run

# Spring Boot
cd spring-boot-chat && mvn clean package && java -jar target/*.jar

# Quarkus
cd quarkus-chat && mvn clean package && java -jar target/quarkus-app/quarkus-run.jar

# Embedded Jetty
cd embedded-jetty-websocket-chat && mvn clean install && mvn -Pserver
```

Most samples run on **http://localhost:8080**. A few AI samples use different ports so they can run simultaneously (for example `spring-boot-mcp-server` on 8083, `spring-boot-a2a-agent` on 8084, `spring-boot-agui-chat` on 8085, `spring-boot-ai-tools` on 8090). Check each sample's `application.yml` / `application.properties` for the exact port.

### Picking an LLM provider

- **Gemini (free tier).** Fast to set up, but the free tier caps at roughly **20 requests/day** across all models. A single multi-agent sample (e.g. `spring-boot-multi-agent-startup-team`) can burn 5+ requests per turn, so expect `429 RESOURCE_EXHAUSTED` after a handful of prompts. Fine for a one-off demo, not for iterating on tool-heavy or coordinator samples.
- **OpenAI / Anthropic (paid).** Practical for local development — higher per-minute limits, no daily cap. Point `LLM_BASE_URL` / `LLM_API_KEY` at the provider and you are done.
- **Ollama (local).** Zero quota, no network required. Start `ollama serve`, then `export LLM_BASE_URL=http://localhost:11434/v1 LLM_MODEL=llama3.2` and every sample will hit the local model.
- **[`dravr-embacle`](https://github.com/dravrtx/dravr-embacle).** OpenAI-compatible proxy in front of the Claude Code CLI / GitHub Copilot CLI — useful when you want quota-free local testing without running Ollama.

Samples fall back to `DemoAgentRuntime` (canned responses through the real pipeline) when no LLM is configured, so the UI still demos even without a key.

## The Same Handler Everywhere

The core `Chat.java` handler is nearly identical across all chat samples:

```java
@ManagedService(path = "/chat")
public class Chat {

    @Ready
    public void onReady() { }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

Only packaging and configuration differ — your business logic is portable across Spring Boot, Quarkus, and plain Servlet containers.

## Wave 1-6 Unified @Agent Feature Matrix

| Sample | Tool calling | HITL approval | Prompt caching | Lifecycle listeners | Embeddings |
|--------|:------------:|:-------------:|:--------------:|:-------------------:|:----------:|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | — | — | ✅ `promptCache = CONSERVATIVE` | — | startup log |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | ✅ | ✅ `@RequiresApproval` | — | ✅ `ToolAuditListener` | — |
| [spring-boot-rag-chat](spring-boot-rag-chat/) | ✅ | — | — | — | ✅ via `ContextProvider` |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | ✅ | ✅ | — | — | — |
| [spring-boot-dentist-agent](spring-boot-dentist-agent/) | ✅ | ✅ | — | — | — |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | ✅ | ✅ durable | — | — | — |
| [spring-boot-orchestration-demo](spring-boot-orchestration-demo/) | ✅ | ✅ | — | — | — |

**Feature reference:**
- **Tool calling + HITL approval** (Phase 0 + Wave 1): `@AiTool` + `@RequiresApproval`; every runtime routes through `ToolExecutionHelper.executeWithApproval`.
- **Multi-modal** (Wave 2): `Content.Image` / `Audio` / `File` on `context.parts()`; Spring AI `Media`, LC4j `ImageContent`, ADK `Part.fromBytes`, Built-in OpenAI `image_url`.
- **Lifecycle listeners** (Wave 3): implement `AgentLifecycleListener` and attach via `context.withListeners(...)`. Bridges fire `onToolCall`/`onToolResult` on every round.
- **Prompt caching** (Wave 4): `@AiEndpoint(promptCache = CachePolicy.CONSERVATIVE)` or `CacheHint` on context metadata. Spring AI/LC4j/Built-in emit OpenAI `prompt_cache_key`; pipeline-level `ResponseCache` also replays identical requests across all runtimes.
- **Embeddings** (Wave 5): `EmbeddingRuntime` SPI auto-discovered via `ServiceLoader`. Ships for Spring AI, LangChain4j, Built-in OpenAI, Embabel, Semantic Kernel.
- **Retry policy** (Wave 6): `@AiEndpoint(retry = @Retry(maxRetries = 5))` or programmatic `context.withRetryPolicy(...)`.

See the [atmosphere-ai capability matrix](../modules/ai/README.md#capability-matrix) for the cross-runtime support view.

## Governance coverage across samples

The governance policy plane has four axes: accepting MS Agent Governance
Toolkit YAML, architectural scope enforcement, signed commitment records
on cross-agent dispatch, and OWASP + compliance evidence. Each sample
below exercises at least one axis with CI-verified end-to-end tests that
boot the Spring Boot context and assert decisions fire live. See
[docs/governance-policy-plane.md](../docs/governance-policy-plane.md)
for the full picture.

| Sample | MS YAML | Scope | Commitments | OWASP | Atmosphere-unique angle |
|---|:-:|:-:|:-:|:-:|---|
| [spring-boot-ms-governance-chat](spring-boot-ms-governance-chat/) | ✅ | ✅ | — | ✅ | MS Agent Governance Toolkit YAML accepted verbatim |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | ✅ | ✅ | — | — | **Per-request scope install** — one endpoint, four YAML-backed scopes |
| [spring-boot-multi-agent-startup-team](spring-boot-multi-agent-startup-team/) | ✅ | ✅ | ✅ | ✅ | Streaming + signed `CommitmentRecord`s + `GovernanceFleetInterceptor` at every dispatch |
| [spring-boot-checkpoint-agent](spring-boot-checkpoint-agent/) | — | — | ✅ | — | **Signed audit trail across HITL pause** — durable + cryptographic in one |
| [spring-boot-mcp-server](spring-boot-mcp-server/) | — | ✅ | — | ✅ | MCP tool-call governance over the streaming transport (MS gateway is HTTP-only) |

### Admin control plane — try it on any of the samples above

```bash
curl http://localhost:8080/api/admin/governance/policies       # installed policies + sha256 digests
curl http://localhost:8080/api/admin/governance/health         # kill-switch + dry-run + slos
curl http://localhost:8080/api/admin/governance/agt-verify     # OWASP + compliance, agt verify schema
curl http://localhost:8080/api/admin/governance/decisions      # recent policy decisions (ring buffer)

# Break-glass: halt all AI traffic without a redeploy
curl -X POST http://localhost:8080/api/admin/governance/kill-switch/arm \
     -H 'Content-Type: application/json' \
     -d '{"reason":"incident","operator":"oncall"}'
```

## Documentation

- [Full Documentation](https://atmosphere.github.io/docs/)
- [Getting Started with Spring Boot](https://atmosphere.github.io/docs/integrations/spring-boot/)
- [Getting Started with Quarkus](https://atmosphere.github.io/docs/integrations/quarkus/)
- [Core Runtime](https://atmosphere.github.io/docs/reference/core/)
- [Governance policy plane](../docs/governance-policy-plane.md)
