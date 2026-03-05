---
title: "MCP Server"
description: "Expose tools, resources, and prompts to AI agents with @McpServer annotations"
---

MCP (Model Context Protocol) lets AI agents call your server-side tools, read resources, and use prompt templates. Atmosphere implements an MCP server with annotation-driven configuration over Streamable HTTP, WebSocket, or SSE transport.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-mcp</artifactId>
    <version>4.0.8-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
@McpServer(name = "my-server", path = "/atmosphere/mcp")
public class MyMcpServer {

    @McpTool(name = "greet", description = "Say hello to someone")
    public String greet(
            @McpParam(name = "name", required = true) String name) {
        return "Hello, " + name + "!";
    }

    @McpResource(uri = "atmosphere://server/status",
                 name = "Server Status",
                 description = "Current server status")
    public String serverStatus() {
        return "OK";
    }

    @McpPrompt(name = "summarize", description = "Summarize a topic")
    public List<McpMessage> summarize(
            @McpParam(name = "topic") String topic) {
        return List.of(
            McpMessage.system("You are a summarization expert."),
            McpMessage.user("Summarize: " + topic)
        );
    }
}
```

## Annotations

| Annotation | Target | MCP Operation |
|-----------|--------|--------------|
| `@McpServer` | Class | Registers the MCP endpoint |
| `@McpTool` | Method | `tools/call` |
| `@McpResource` | Method | `resources/read` |
| `@McpPrompt` | Method | `prompts/get` |
| `@McpParam` | Parameter | Parameter metadata for JSON Schema |

## Transports

| Transport | Connection |
|-----------|-----------|
| Streamable HTTP (recommended) | `POST http://host:port/atmosphere/mcp` |
| WebSocket | `ws://host:port/atmosphere/mcp` |
| SSE | `GET http://host:port/atmosphere/mcp` |

Agents get Atmosphere's full transport layer — automatic reconnection, heartbeats, and fallback.

## Connecting AI Clients

Works with Claude Desktop, VS Code Copilot, Cursor, and any MCP-compatible agent:

```json
{
  "mcpServers": {
    "my-server": { "url": "http://localhost:8080/atmosphere/mcp" }
  }
}
```

## Injectable Parameters

`@McpTool` methods can declare framework types that are auto-injected:

| Type | What's injected |
|------|----------------|
| `Broadcaster` | `BroadcasterFactory.lookup(topic, true)` |
| `StreamingSession` | `BroadcasterStreamingSession` for the topic |
| `AtmosphereConfig` | The framework's config |
| `BroadcasterFactory` | The broadcaster factory |
| `AtmosphereFramework` | The framework instance |

Framework types are excluded from the tool's JSON Schema — they're invisible to the AI agent.

### Push Messages to Browser Clients

An MCP tool that broadcasts to web clients:

```java
@McpTool(name = "broadcast", description = "Send a message to a chat topic")
public String broadcast(
        @McpParam(name = "message") String message,
        @McpParam(name = "topic") String topic,
        Broadcaster broadcaster) {
    broadcaster.broadcast(message);
    return "Sent to " + topic;
}
```

When an AI agent calls this tool, the message is delivered to all browser clients subscribed to the topic.

### Stream LLM Tokens to Browsers

```java
@McpTool(name = "ask_ai", description = "Ask AI and stream the answer to a topic")
public String askAi(
        @McpParam(name = "question") String question,
        @McpParam(name = "topic") String topic,
        StreamingSession session) {
    Thread.startVirtualThread(() -> {
        var request = ChatCompletionRequest.builder(model)
                .user(question).build();
        client.streamChatCompletion(request, session);
    });
    return "Streaming to " + topic;
}
```

## OpenTelemetry Tracing

`McpTracing` wraps every tool, resource, and prompt invocation in an OTel span:

| Span Attribute | Description |
|----------------|-------------|
| `mcp.tool.name` | Tool/resource/prompt name |
| `mcp.tool.type` | `"tool"`, `"resource"`, or `"prompt"` |
| `mcp.tool.arg_count` | Number of arguments |
| `mcp.tool.error` | `true` if invocation failed |

With Spring Boot, `McpTracing` is auto-configured when an `OpenTelemetry` bean is present.

## Sample

See [spring-boot-mcp-server](https://github.com/Atmosphere/atmosphere/tree/main/samples/spring-boot-mcp-server/) for a complete example with tools, resources, prompts, and a React frontend.

## Next Steps

- [Chapter 14: Spring Boot](/docs/tutorial/14-spring-boot/) — auto-configuration for MCP
- [Chapter 9: @AiEndpoint](/docs/tutorial/09-ai-endpoint/) — AI streaming endpoints
