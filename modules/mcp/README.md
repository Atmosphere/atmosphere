# Atmosphere MCP

MCP (Model Context Protocol) server module for Atmosphere. Exposes annotation-driven tools, resources, and prompt templates to AI agents over Streamable HTTP, WebSocket, or SSE transport.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp</artifactId>
    <version>4.0.2</version>
</dependency>
```

## Minimal Example

```java
@McpServer(name = "my-server", path = "/atmosphere/mcp")
public class MyMcpServer {

    @McpTool(name = "greet", description = "Say hello")
    public String greet(@McpParam(name = "name", required = true) String name) {
        return "Hello, " + name + "!";
    }

    @McpResource(uri = "atmosphere://server/status",
                 name = "Server Status",
                 description = "Current server status")
    public String serverStatus() {
        return "OK";
    }

    @McpPrompt(name = "summarize", description = "Summarize a topic")
    public List<McpMessage> summarize(@McpParam(name = "topic") String topic) {
        return List.of(
            McpMessage.system("You are a summarization expert."),
            McpMessage.user("Summarize: " + topic)
        );
    }
}
```

## Annotations

| Annotation | Target | Description |
|-----------|--------|-------------|
| `@McpServer` | Class | Marks the class as an MCP server and sets the endpoint path |
| `@McpTool` | Method | Exposes a method as a callable tool (`tools/call`) |
| `@McpResource` | Method | Exposes a method as a read-only resource (`resources/read`) |
| `@McpPrompt` | Method | Exposes a method as a prompt template (`prompts/get`) |
| `@McpParam` | Parameter | Annotates method parameters with name, description, and required flag |

## Supported Transports

| Transport | How to connect |
|-----------|---------------|
| Streamable HTTP (recommended) | `POST http://host:port/atmosphere/mcp` |
| WebSocket | `ws://host:port/atmosphere/mcp` |
| SSE | `GET http://host:port/atmosphere/mcp` |

Agents get automatic reconnection, heartbeats, and transport fallback from Atmosphere's transport layer.

## Connecting Clients

Works with Claude Desktop, VS Code Copilot, Cursor, and any MCP-compatible agent:

```json
{
  "mcpServers": {
    "my-server": { "url": "http://localhost:8080/atmosphere/mcp" }
  }
}
```

For clients that only support stdio, build the bridge JAR with `mvn package -Pstdio-bridge -DskipTests` and point the client at the resulting `atmosphere-mcp-*-stdio-bridge.jar`.

## Sample

- [Spring Boot MCP Server](../../samples/spring-boot-mcp-server/) -- tools, resources, and prompts with a React frontend

## Injectable Parameters

`@McpTool` methods can declare framework types as parameters. These are auto-injected at invocation time and excluded from the tool's JSON Schema (MCP clients never see them):

| Type | What's injected | Requires |
|------|----------------|----------|
| `Broadcaster` | `BroadcasterFactory.lookup(topic, true)` | A `@McpParam(name="topic")` argument in the call |
| `StreamingSession` | `BroadcasterStreamingSession` wrapping the topic's Broadcaster | A `@McpParam(name="topic")` argument + `atmosphere-ai` on classpath |
| `AtmosphereConfig` | The framework's `AtmosphereConfig` | Nothing |
| `BroadcasterFactory` | The framework's `BroadcasterFactory` | Nothing |
| `AtmosphereFramework` | The framework instance | Nothing |

### Example: Push Messages to Browser Clients

```java
@McpServer(name = "my-server", path = "/atmosphere/mcp")
public class MyMcpServer {

    @McpTool(name = "broadcast", description = "Send a message to a chat topic")
    public String broadcast(
            @McpParam(name = "message") String message,
            @McpParam(name = "topic") String topic,
            Broadcaster broadcaster) {
        broadcaster.broadcast(message);
        return "sent to " + topic;
    }
}
```

When an AI agent calls this tool with `{"message": "hello", "topic": "/chat"}`, the message is broadcast to all WebSocket/SSE/gRPC clients subscribed to `/chat`.

### Example: Stream LLM Tokens to Browsers

With `atmosphere-ai` on the classpath, inject a `StreamingSession` that wraps the topic's Broadcaster:

```java
@McpTool(name = "ask_ai", description = "Ask AI and stream answer to a topic")
public String askAi(
        @McpParam(name = "question") String question,
        @McpParam(name = "topic") String topic,
        StreamingSession session) {
    // session.send() broadcasts to all clients on the topic
    Thread.startVirtualThread(() -> {
        var request = ChatCompletionRequest.builder(model).user(question).build();
        client.streamChatCompletion(request, session);
    });
    return "streaming to " + topic;
}
```

See [atmosphere-ai README](../ai/README.md) for more on `StreamingSession` and wire protocol.

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
