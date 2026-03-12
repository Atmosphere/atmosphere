# Atmosphere Framework Overview

Atmosphere is a Java framework for building real-time web applications. It provides a portable API that works across all Java-based web servers including Jetty, Tomcat, Undertow (Quarkus), and Spring Boot.

## Key Features

- **Transport Abstraction**: Unified API across WebSocket, Server-Sent Events (SSE), long-polling, and gRPC
- **Broadcaster Pattern**: Pub/sub messaging through the Broadcaster abstraction
- **Annotation-Based Programming**: Use `@ManagedService`, `@AiEndpoint`, and other annotations to define endpoints
- **AI Integration**: Built-in support for streaming LLM responses via `@AiEndpoint` and `StreamingSession`
- **Framework Integration**: Native starters for Spring Boot 4.0 and Quarkus 3.21+
- **Client Library**: atmosphere.js TypeScript/JavaScript client with automatic transport negotiation

## Architecture

Atmosphere operates at the servlet layer, intercepting HTTP requests and upgrading them to the appropriate real-time transport. The core abstraction is the `AtmosphereResource`, which represents a single client connection regardless of the underlying transport.

The `Broadcaster` is the pub/sub hub that distributes messages to connected clients. Each `AtmosphereResource` is associated with one or more Broadcasters.

## Version History

- Atmosphere 4.x: Java 21, Jakarta Servlet API, Spring Boot 4.0, Quarkus 3.21+
- Atmosphere 3.x: Java 11+, Jakarta Servlet API migration
- Atmosphere 2.x: Java 8, javax.servlet API
