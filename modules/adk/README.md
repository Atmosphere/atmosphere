# atmosphere-adk — Google ADK Integration

Bridges [Google Agent Development Kit (ADK)](https://github.com/google/adk-java) agent
streams to Atmosphere's real-time broadcast infrastructure. ADK agents can now push
streaming texts to WebSocket, SSE, and gRPC browser clients.

## Architecture

```
Browser ← WS/SSE/gRPC → Broadcaster ← AdkEventAdapter ← Flowable<Event> ← Runner ← LlmAgent
```

## Quick Start

### 1. Add Dependencies

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-adk</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk</artifactId>
    <version>1.2.0</version>
</dependency>
```

### 2. Stream ADK Events to Browsers

```java
// Build an ADK agent
LlmAgent agent = LlmAgent.builder()
    .name("assistant")
    .model("gemini-2.0-flash")
    .instruction("You are a helpful assistant.")
    .build();

Runner runner = Runner.builder()
    .agent(agent)
    .appName("my-app")
    .build();

// Run the agent and bridge events to an Atmosphere Broadcaster
Flowable<Event> events = runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText(prompt)));
AdkEventAdapter.bridge(events, broadcaster);
// All connected WebSocket/SSE/gRPC clients on the broadcaster now receive streaming texts
```

### 3. Let ADK Agents Broadcast to Browsers

Give your ADK agent a tool that can push messages to browser clients:

```java
// Create a broadcast tool for a specific topic
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcaster);

// Or create one that can target any topic
AdkBroadcastTool broadcastTool = new AdkBroadcastTool(broadcasterFactory);

LlmAgent agent = LlmAgent.builder()
    .name("notifier")
    .model("gemini-2.0-flash")
    .instruction("Use the broadcast tool to send updates to users.")
    .tools(broadcastTool)
    .build();
```

### 4. Use the AiStreamingAdapter SPI

If you prefer the atmosphere-ai adapter pattern:

```java
AdkStreamingAdapter adapter = new AdkStreamingAdapter();
StreamingSession session = StreamingSessions.start(resource);
adapter.stream(new AdkRequest(runner, userId, sessionId, "Tell me about Java 25"), session);
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `AdkAgentRuntime` | `AgentRuntime` SPI implementation (priority 100); builds per-request `Runner` for tool-calling paths |
| `AdkEventAdapter` | Subscribes to `Flowable<Event>` and forwards streaming texts to `StreamingSession` |
| `AdkToolBridge` | Translates Atmosphere `ToolDefinition` to ADK `BaseTool` with HITL approval gating |
| `AdkBroadcastTool` | ADK `BaseTool` that broadcasts messages via Atmosphere `Broadcaster` |
| `AdkStreamingAdapter` | `AiStreamingAdapter` SPI impl bridging ADK Runner to StreamingSession |
| `AdkRootAgent` | Per-request override for the `App.rootAgent` slot — wires `SequentialAgent` / `ParallelAgent` / `LoopAgent` topologies |
| `AdkArtifactService` | ADK `BaseArtifactService` bridging the native artifact store onto Atmosphere's bounded `AgentFileSystem` |
| `AdkSaveArtifactTool` | Model-facing `save_artifact` write tool (ADK ships `load_artifacts` but no write counterpart) |

## Multi-Agent Composition (`AdkRootAgent`)

By default `AdkAgentRuntime` builds a single `LlmAgent` and wires it as the
ADK `App.rootAgent`. To swap that with one of ADK's orchestration agents
(`SequentialAgent`, `ParallelAgent`, `LoopAgent`) or any custom `BaseAgent`
subclass on a **per-request** basis, attach it via `AdkRootAgent`:

```java
var planner  = LlmAgent.builder().name("planner").model("gemini-2.5-flash")
        .instruction("Plan the steps.").build();
var coder    = LlmAgent.builder().name("coder").model("gemini-2.5-flash")
        .instruction("Write the code.").build();
var reviewer = LlmAgent.builder().name("reviewer").model("gemini-2.5-flash")
        .instruction("Review the code.").build();

var pipeline = SequentialAgent.builder()
        .name("code-pipeline")
        .subAgents(planner, coder, reviewer)
        .build();

// Attach via an interceptor so every prompt routes through the pipeline:
@Component
class PipelineInterceptor implements AiInterceptor {
    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request.withMetadata(Map.of(
                AdkRootAgent.METADATA_KEY, pipeline));
    }
}
```

When a custom root is attached, the runtime:

- Forces a per-request `Runner` (App.rootAgent is set at App.Builder time and
  cannot be swapped on the cached default runner).
- **Does not** construct an `LlmAgent` of its own and **does not** apply
  `AdkToolBridge` to the user's sub-tree. Tool wiring belongs on the leaf
  `LlmAgent` instances at construction time — orchestration shells do not
  call models.
- Still applies cache config (`CacheHint`), gateway admission, history
  seeding, and the `ExecutionHandle` cancel path. The bridge replaces only
  the root agent slot, not the surrounding Atmosphere safety rail.

For a process-wide custom root, supply your own pre-built `Runner` via
`AdkAgentRuntime.setRunner(Runner)` instead.

## Native Virtual Filesystem (`AiCapability.VIRTUAL_FILESYSTEM`)

When the harness `FILESYSTEM` primitive resolves for a dispatch (a
conversation-scoped `AgentFileSystem` is in the session's tool scope and
`atmosphere.ai.filesystem` is `AUTO` or `NATIVE`), the runtime exposes
Atmosphere's bounded `files/{conversationId}/` store through ADK's **own**
artifact machinery instead of the portable file tools:

- `AdkArtifactService` is injected via `Runner.Builder.artifactService(...)`,
  so `ToolContext.saveArtifact(...)` / `loadArtifact(...)` in any user tool
  read and write the Atmosphere store.
- ADK's shipped `load_artifacts` read tool plus Atmosphere's `save_artifact`
  write complement (`AdkSaveArtifactTool`) are registered on the built
  `LlmAgent` — a user tool claiming either name wins and the native tool is
  skipped.

Semantics: ADK artifacts are integer-versioned but `AgentFileSystem` keeps no
history, so versions collapse to **overwrite semantics** — every save reports
version `0`, `listVersions` returns at most `[0]`, and loading any other
version completes empty. `deleteArtifact` signals
`UnsupportedOperationException` (the store exposes no delete). The store is
UTF-8 text only: text `Part`s pass through, inline-data parts must decode as
strict UTF-8, and bounds/traversal rejections surface to the model as clear
tool-result errors.

With a custom root (`AdkRootAgent`), the artifact-service bridge is still
wired at Runner level, but no tools are attached to the user's topology —
register `LoadArtifactsTool.INSTANCE` / `new AdkSaveArtifactTool()` on your
leaf `LlmAgent`s to give the model direct file access. Set
`atmosphere.ai.filesystem=builtin` to keep the portable file-tool floor and
suppress the native surface entirely.

## Reuse from atmosphere-ai

This module depends on `atmosphere-ai` and reuses its streaming infrastructure:

- **`StreamingSession`** — the SPI interface for streaming text delivery
- **`BroadcasterStreamingSession`** — topic-based streaming (no `AtmosphereResource` needed)
- **`StreamingSessions`** — factory for creating sessions

No streaming code is duplicated. The ADK module adds only the ADK-specific bridge logic.

## Wire Protocol

Streaming texts delivered to browsers use the same JSON format as all atmosphere-ai adapters:

```json
{"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
{"type":"streaming-text","data":" world","sessionId":"abc-123","seq":2}
{"type":"complete","sessionId":"abc-123","seq":3}
```
