# Atmosphere 4.0 Samples

Example applications demonstrating Atmosphere 4.0 across different deployment targets.

### Chat & Messaging

| Sample | Stack | Packaging | Rooms | Metrics | Native Image |
|--------|-------|-----------|-------|---------|-------------|
| [chat](chat/) | Servlet (WAR) | WAR | — | — | — |
| [spring-boot-chat](spring-boot-chat/) | Spring Boot 4.0 | JAR | ✅ | ✅ | ✅ |
| [quarkus-chat](quarkus-chat/) | Quarkus 3.21+ | JAR | — | — | ✅ |
| [embedded-jetty-websocket-chat](embedded-jetty-websocket-chat/) | Embedded Jetty | JAR | — | — | — |
| [grpc-chat](grpc-chat/) | gRPC + Spring Boot | JAR | — | — | — |

### AI / LLM Streaming

| Sample | AI Backend | Tool Calling | Description |
|--------|-----------|-------------|-------------|
| [spring-boot-ai-chat](spring-boot-ai-chat/) | Built-in (Gemini/OpenAI/Ollama) | — | Basic AI streaming with `@AiEndpoint` |
| [spring-boot-langchain4j-chat](spring-boot-langchain4j-chat/) | LangChain4j | — | LangChain4j adapter |
| [spring-boot-spring-ai-chat](spring-boot-spring-ai-chat/) | Spring AI | — | Spring AI adapter |
| [spring-boot-adk-chat](spring-boot-adk-chat/) | Google ADK | — | Google ADK adapter |
| [spring-boot-embabel-chat](spring-boot-embabel-chat/) | Embabel | — | Embabel agent adapter |
| [spring-boot-langchain4j-tools](spring-boot-langchain4j-tools/) | LangChain4j | `@Tool` (native) | LangChain4j-native tool calling |
| [spring-boot-ai-tools](spring-boot-ai-tools/) | LangChain4j | `@AiTool` (portable) | Framework-agnostic tool calling pipeline |
| [spring-boot-adk-tools](spring-boot-adk-tools/) | Google ADK | `@AiTool` (portable) | ADK with Atmosphere tool bridge |
| [spring-boot-spring-ai-routing](spring-boot-spring-ai-routing/) | Spring AI | — | Cost/latency-based model routing |
| [spring-boot-ai-classroom](spring-boot-ai-classroom/) | Built-in | — | Multi-persona AI classroom ([Expo client](spring-boot-ai-classroom/expo-client/)) |
| [spring-boot-embabel-horoscope](spring-boot-embabel-horoscope/) | Embabel | — | Embabel agent orchestration |

### Infrastructure & Integration

| Sample | Stack | Description |
|--------|-------|-------------|
| [spring-boot-mcp-server](spring-boot-mcp-server/) | Spring Boot 4.0 | MCP (Model Context Protocol) server |
| [spring-boot-durable-sessions](spring-boot-durable-sessions/) | Spring Boot 4.0 | Persistent sessions with SQLite/Redis |
| [spring-boot-otel-chat](spring-boot-otel-chat/) | Spring Boot 4.0 | OpenTelemetry observability |
| [shared-resources](shared-resources/) | — | Shared frontend assets |

## Quick Start

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

Most samples run on **http://localhost:8080**. The AI samples use different ports to allow running them simultaneously: `spring-boot-langchain4j-chat` on 8081, `spring-boot-embabel-chat` on 8082.

## The Same Handler Everywhere

The core `Chat.java` handler is nearly identical across all samples:

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

## Documentation

- [Full Documentation](../docs/README.md)
- [Getting Started with Spring Boot](../docs/spring-boot.md)
- [Getting Started with Quarkus](../docs/quarkus.md)
- [Core Runtime](../docs/core.md)
