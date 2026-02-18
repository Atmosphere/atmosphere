# Atmosphere 4.0 Samples

Example applications demonstrating Atmosphere 4.0 across different deployment targets.

| Sample | Stack | Packaging | Rooms | Metrics | Native Image |
|--------|-------|-----------|-------|---------|-------------|
| [chat](chat/) | Servlet (WAR) | WAR | — | — | — |
| [spring-boot-chat](spring-boot-chat/) | Spring Boot 4.0 | JAR | ✅ | ✅ | ✅ |
| [quarkus-chat](quarkus-chat/) | Quarkus 3.21+ | JAR | — | — | ✅ |
| [embedded-jetty-websocket-chat](embedded-jetty-websocket-chat/) | Embedded Jetty | JAR | — | — | — |
| [spring-boot-ai-chat](spring-boot-ai-chat/) | Spring Boot 4.0 | JAR | — | — | — |
| [spring-boot-langchain4j-chat](spring-boot-langchain4j-chat/) | Spring Boot 4.0 | JAR | — | — | — |
| [spring-boot-embabel-chat](spring-boot-embabel-chat/) | Spring Boot 4.0 | JAR | — | — | — |
| [shared-resources](shared-resources/) | — | — | — | — | — |

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

All samples run on **http://localhost:8080** — open multiple browser tabs to chat.

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

- [Wiki](https://github.com/Atmosphere/atmosphere/wiki)
- [Getting Started with Spring Boot](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-Spring-Boot)
- [Getting Started with Quarkus](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-Quarkus)
- [WAR Deployment](https://github.com/Atmosphere/atmosphere/wiki/WAR-Deployment)
