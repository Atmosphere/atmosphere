# Atmosphere Quarkus Admin Extension

Quarkus extension for the Atmosphere Admin control plane. Adds a real-time dashboard, REST API, WebSocket event stream, and MCP tools to any Quarkus + Atmosphere application.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-admin-extension</artifactId>
    <version>4.0.33</version>
</dependency>
```

You also need a Quarkus REST implementation for the admin endpoints:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
</dependency>
```

## What You Get

| URL | Description |
|-----|-------------|
| `/admin/` | Real-time dashboard UI with live event feed |
| `/api/admin/overview` | System overview JSON |
| `/api/admin/*` | Full REST API (~25 endpoints) |
| `/atmosphere/admin/events` | WebSocket event stream (via Atmosphere) |

The dashboard is at `/admin/` (not `/atmosphere/admin/`) because Quarkus routes `/atmosphere/*` through the Atmosphere servlet.

## Quarkus Feature

The extension registers as `atmosphere-admin` in Quarkus:

```
Installed features: [atmosphere, atmosphere-admin, cdi, rest, rest-jackson, ...]
```

## How It Works

- **`AdminProducer`** — CDI `@Produces @Singleton` bean that creates `AtmosphereAdmin` after the framework initializes
- **`AdminResource`** — Jakarta REST `@Path("/api/admin")` resource with all endpoints
- **`AdminProcessor`** — `@BuildStep` processor that registers CDI beans, reflection targets, and Jandex indexing
- **Dashboard HTML** — served automatically from `META-INF/resources/admin/`
- **Event stream** — `AdminEventHandler` registered as an Atmosphere handler at `/atmosphere/admin/events`

## REST API

Same as the Spring Boot admin module — see [atmosphere-admin README](../admin/README.md) for the full endpoint reference.

## Requirements

- Quarkus 3.21+
- `atmosphere-quarkus-extension` (transitive)
- `atmosphere-admin` (transitive)
- A Quarkus REST implementation (`quarkus-rest-jackson` recommended)
