# Plan: @AiEndpoint Annotation + MCP Integration Tests

## 1. `@AiEndpoint` Annotation (`ai-endpoint`)

**Problem:** All three AI chat samples repeat identical boilerplate:
- `@ManagedService` with same config (120s timeout, DefaultBroadcasterCache)
- `@Inject AtmosphereResource` + `@Inject AtmosphereResourceEvent`
- Identical `@Ready`/`@Disconnect` lifecycle callbacks (just logging)
- `@Message` handler that creates a `StreamingSession` and runs on a virtual thread

A 70-line handler reduces to ~15 lines with `@AiEndpoint`.

**Approach:** New annotation + processor in `modules/ai`, not `modules/cpr` (keeps core framework clean, AI is an opt-in module).

### What `@AiEndpoint` provides
```java
@AiEndpoint(path = "/atmosphere/ai-chat")
public class AiChat {
    @AiEndpoint.Prompt
    public void onPrompt(String userMessage, StreamingSession session) {
        var settings = AiConfig.get();
        var request = ChatCompletionRequest.builder(settings.model())
                .system("You are a helpful assistant.")
                .user(userMessage)
                .build();
        settings.client().streamChatCompletion(request, session);
    }
}
```

The processor handles:
- Wraps in `@ManagedService` equivalent config (timeout, cache)
- Auto-injects `AtmosphereResource` / `AtmosphereResourceEvent`
- Auto-generates `@Ready` / `@Disconnect` logging
- Creates `StreamingSession` and passes to `@Prompt` method
- Runs `@Prompt` on a virtual thread automatically

### Files to create
- `modules/ai/src/main/java/org/atmosphere/ai/annotation/AiEndpoint.java` — the annotation (path, timeout, system prompt)
- `modules/ai/src/main/java/org/atmosphere/ai/annotation/Prompt.java` — method annotation for the message handler
- `modules/ai/src/main/java/org/atmosphere/ai/processor/AiEndpointProcessor.java` — `@AtmosphereAnnotation` processor
- `modules/ai/src/main/java/org/atmosphere/ai/processor/AiEndpointHandler.java` — `AtmosphereHandler` impl that wires lifecycle + streaming
- `modules/ai/src/test/java/org/atmosphere/ai/AiEndpointProcessorTest.java` — unit tests

### Files to modify
- `modules/ai/pom.xml` — may need to add `atmosphere-runtime` dependency if not already there

### Design decisions
- `@Prompt` method receives `(String message, StreamingSession session)` — session is pre-created
- Optional `systemPrompt` attribute on `@AiEndpoint` for the default system message
- Optional `timeout` attribute (default 120000ms)
- Virtual thread execution is automatic (no `Thread.startVirtualThread()` boilerplate)

---

## 2. MCP Integration Tests (`mcp-integration-tests`)

**Problem:** All MCP tests today are unit tests with mocked `AtmosphereResource`. The new session resilience features (TTL, pending notifications, reconnect replay) need live-server testing.

**Approach:** Add MCP integration tests to the existing `modules/integration-tests` module (which already has embedded Jetty + WebSocket infrastructure).

### Test scenarios
1. **MCP initialize + tools/list over WebSocket** — full JSON-RPC round-trip through live server
2. **Session persistence across reconnect** — connect, initialize, disconnect, reconnect with `Mcp-Session-Id`, verify session restored
3. **Pending notification replay** — buffer notifications while client is disconnected, verify they're replayed on reconnect
4. **Session TTL eviction** — create session, wait for TTL, verify session is gone (use short TTL like 1s for testing)
5. **Streamable HTTP POST/GET/DELETE** — standard HTTP transport flow via `HttpClient`

### Files to create
- `modules/integration-tests/src/main/java/org/atmosphere/integrationtests/mcp/TestMcpServer.java` — simple `@McpServer` with a test tool
- `modules/integration-tests/src/test/java/org/atmosphere/integrationtests/mcp/McpWebSocketIntegrationTest.java` — WebSocket-based MCP tests
- `modules/integration-tests/src/test/java/org/atmosphere/integrationtests/mcp/McpHttpIntegrationTest.java` — Streamable HTTP tests

### Files to modify
- `modules/integration-tests/pom.xml` — add `atmosphere-mcp` test dependency

### Infrastructure reuse
- Reuse `EmbeddedAtmosphereServer` with `.withAnnotationPackage("org.atmosphere.integrationtests.mcp")`
- Reuse `CollectingListener`/`MessageLatch` patterns from `WebSocketTransportTest`
- Use Java 21 `HttpClient` for both WebSocket and HTTP tests
- Tests tagged `@Test(groups = "core")` so they run without Docker

---

## Execution Order

1. **ai-endpoint** — annotation + processor + tests (modules/ai)
2. **mcp-integration-tests** — live server tests (modules/integration-tests)

Independent — can be committed separately.
