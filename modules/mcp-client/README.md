# atmosphere-mcp-client

Outbound MCP (Model Context Protocol) client for Atmosphere. Wraps the
[official MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
and exposes a remote MCP server's tools as Atmosphere `ToolDefinition`s, so
any `AgentRuntime` that honors `AgentExecutionContext.tools()` can invoke
remote MCP tools without per-runtime wiring.

This is the **outbound** counterpart to `atmosphere-mcp`'s server-side
`McpAgentRegistration`, which exposes locally-defined `@AiTool` methods as
MCP tools to external clients. `atmosphere-mcp-client` is the inverse —
it consumes a remote server's tools as a tool source for the local agent.

## Why this exists

Anthropic's [Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview)
lists MCP servers as a first-class field on the Agent definition — when the
managed agent runs, the harness wires remote MCP tools into its loop by
default. Atmosphere's per-runtime audit found that none of the nine
`AgentRuntime` implementations natively wires outbound MCP servers; this
module fills that gap at the framework-agnostic tool layer rather than
duplicating the wiring nine times.

## Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp-client</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

## Minimal usage

```java
import org.atmosphere.mcp.client.McpToolSource;

// Connect once at startup
var remoteTools = McpToolSource.connect(URI.create("http://localhost:8083"));

// Every prompt: surface remote tools to the runtime
@Prompt
public void onPrompt(AiRequest request, StreamingSession session) {
    var augmented = request.withTools(remoteTools.tools());
    session.stream(augmented);
}

// Shut down cleanly
remoteTools.close();
```

For the Spring Boot interceptor pattern, see
[`samples/spring-boot-personal-assistant`](../../samples/spring-boot-personal-assistant/) (the
`UpstreamMcpAgent` endpoint and `runtime-langchain4j` Maven profile).

## What it does

* Connects to the remote MCP server using the SDK's `McpSyncClient` (default
  transport: `HttpClientSseClientTransport` over Streamable HTTP/SSE).
* Calls `listTools()` once at construction. Each advertised tool is translated
  into a `ToolDefinition` whose `ToolExecutor.execute` round-trips arguments
  to the remote server via `McpSyncClient.callTool`.
* JSON Schema parameter introspection: `string`, `integer`, `number`,
  `boolean`, `object`, `array` types are surfaced; the `required[]` list
  determines which parameters are flagged required to the model.
* Server-reported tool errors (`isError = true`) are returned to the model
  as `"tool error: <text>"` strings rather than thrown — this matches local
  `@AiTool` failure semantics and lets the agent loop decide to retry,
  recover, or surface the error to the user.

## What it deliberately does NOT do

* **No reconnection logic.** The SDK throws from `callTool` on transport
  failure; the executor surfaces that as a checked exception. Reconnection
  on transport failure is the caller's responsibility.
* **No tool-list watching.** Tools are loaded at `connect` time. If the
  remote server's tool set changes at runtime, reconnect to pick up the new
  set. (MCP supports `notifications/tools/list_changed` — wiring it through
  is open work.)
* **No image/embedded-resource passthrough.** `flatten()` only concatenates
  `TextContent` parts because the `ToolExecutor` contract is "result will be
  serialized to JSON and sent back to the model" and most models don't accept
  binary tool returns. Callers needing binary returns should subclass and
  override.
* **No per-runtime registration.** This module produces `ToolDefinition`s
  through Atmosphere's framework-agnostic tool layer; the twelve runtimes
  consume them through the same code path that handles local `@AiTool`s.

## Security note — bring-your-own-credentials

When the remote MCP server requires credentials (GitHub, Gmail, filesystem,
etc.), use the
[`McpTrustProvider`](../ai/src/main/java/org/atmosphere/ai/extensibility/McpTrustProvider.java)
SPI to resolve them per-user before connecting. Direct `McpToolSource.connect`
is appropriate for unauthenticated demo servers and trusted server-side
backends; for end-user-personal MCP servers, layer a `McpTrustProvider` in
front of the connection.

## Tested with

* MCP Java SDK `1.1.1` (`mcp` aggregator pulling `mcp-core 1.0.0` per the
  CVE-driven pin in the root `pom.xml`)
* `spring-boot-mcp-server` (the bundled Atmosphere upstream sample) — verified
  end-to-end via chrome-devtools under **two** AgentRuntime implementations:
  Built-in (priority 0) and LangChain4j (priority 100). Same sample, same
  prompt, same outbound-MCP tool dispatch — only the Maven profile differs
  (`-Pruntime-langchain4j`). Confirms the SPI claim that `ToolDefinition`s
  flow through any runtime that honors `AgentExecutionContext.tools()`.

## Per-tool metrics

Each {@link McpToolSource} captures call count, error count, last latency,
and average latency per advertised tool. Operators read this through the
sample's `/api/mcp-client/sources` endpoint or by injecting `McpToolSource`
into a custom controller:

```java
@Autowired McpToolSource source;

source.metrics().forEach((toolName, m) -> {
    log.info("{}: {} calls, {} errors, last={}ms, avg={}ms",
            toolName, m.calls(), m.errors(), m.lastLatencyMs(), m.avgLatencyMs());
});
```
