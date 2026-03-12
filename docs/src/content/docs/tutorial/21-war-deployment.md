---
title: "WAR Deployment"
description: "Deploy Atmosphere as a standard WAR to Tomcat, GlassFish, Payara, WildFly, or Jetty"
sidebar:
  order: 21
---

Deploy Atmosphere as a standard WAR to any Servlet 6.0+ container with WebSocket support.

## Prerequisites

- JDK 21+
- A Servlet 6.0+ container: Tomcat 11+, GlassFish 8+, Payara 7+, WildFly 35+, Jetty 12+
- Maven 3.9+

## 1. Create the Project

```xml
<project>
    <groupId>com.example</groupId>
    <artifactId>my-chat</artifactId>
    <packaging>war</packaging>

    <dependencies>
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-runtime</artifactId>
            <version>LATEST</version> <!-- check Maven Central for latest -->
        </dependency>

        <dependency>
            <groupId>jakarta.inject</groupId>
            <artifactId>jakarta.inject-api</artifactId>
            <version>4.0.13</version>
        </dependency>

        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>4.0.13</version>
        </dependency>
    </dependencies>
</project>
```

## 2. Configure web.xml

Create `src/main/webapp/WEB-INF/web.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
            https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
         version="6.0">

    <servlet>
        <servlet-name>AtmosphereServlet</servlet-name>
        <servlet-class>org.atmosphere.cpr.AtmosphereServlet</servlet-class>

        <!-- Scan this package for @ManagedService and other annotations -->
        <init-param>
            <param-name>org.atmosphere.cpr.packages</param-name>
            <param-value>com.example.chat</param-value>
        </init-param>

        <!-- Optional: heartbeat frequency -->
        <init-param>
            <param-name>org.atmosphere.interceptor.HeartbeatInterceptor.clientHeartbeatFrequencyInSeconds</param-name>
            <param-value>10</param-value>
        </init-param>

        <load-on-startup>0</load-on-startup>
        <async-supported>true</async-supported>
    </servlet>

    <servlet-mapping>
        <servlet-name>AtmosphereServlet</servlet-name>
        <url-pattern>/chat/*</url-pattern>
    </servlet-mapping>
</web-app>
```

> **Important:** `<async-supported>true</async-supported>` is required for WebSocket and long-polling to work.

## 3. Create the Chat Service

```java
package com.example.chat;

@ManagedService(path = "/chat",
    atmosphereConfig = "org.atmosphere.cpr.maxInactiveActivity=120000")
public class Chat {

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        // Connection is ready
    }

    @Disconnect
    public void onDisconnect() {
        if (event.isClosedByClient()) {
            // Client closed
        }
    }

    @Message(encoders = {JacksonEncoder.class}, decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        return message;
    }
}
```

## 4. Build and Deploy

```bash
mvn clean package
```

Copy `target/my-chat.war` to your container's deployment directory:

| Container | Deploy To |
|-----------|-----------|
| Tomcat 11 | `$CATALINA_HOME/webapps/` |
| GlassFish 8 | `asadmin deploy target/my-chat.war` |
| Payara 7 | `asadmin deploy target/my-chat.war` |
| WildFly 35 | `$JBOSS_HOME/standalone/deployments/` |
| Jetty 12 | `$JETTY_HOME/webapps/` |

## Container-Specific Notes

### Tomcat 11+

Works out of the box. Atmosphere auto-detects JSR-356 WebSocket support.

### GlassFish 8+ / Payara 7+

Works out of the box. WebSocket support is built into the container.

### WildFly 35+

Ensure the `undertow` subsystem has WebSocket enabled (it is by default).

### Jetty 12+

If using Jetty's `ee10` module, WebSocket is available via `jakarta.websocket`. No extra configuration needed.

## Servlet Init Parameters

Common parameters to set in `web.xml`:

| Parameter | Description |
|-----------|-------------|
| `org.atmosphere.cpr.packages` | Comma-separated packages to scan |
| `org.atmosphere.websocket.maxTextMessageSize` | Max WebSocket message size |
| `org.atmosphere.cpr.maxInactiveActivity` | Idle timeout in ms |
| `org.atmosphere.cpr.broadcasterClass` | Custom Broadcaster class |
| `org.atmosphere.cpr.broadcasterCacheClass` | Custom BroadcasterCache class |
| `org.atmosphere.websocket.WebSocketProtocol` | WebSocket protocol handler |

## Using atmosphere.xml (Alternative)

Instead of `web.xml` init params, you can create `META-INF/atmosphere.xml` for Atmosphere-specific configuration. See the legacy documentation for the full schema.

## Client-Side

Include `atmosphere.js` in your HTML and connect:

```html
<script src="atmosphere.js"></script>
<script>
const subscription = await atmosphere.atmosphere.subscribe(
    {
        url: '/my-chat/chat',
        transport: 'websocket',
        fallbackTransport: 'long-polling',
        trackMessageLength: true,
        contentType: 'application/json'
    },
    {
        open: (response) => console.log('Connected:', response.transport),
        message: (response) => console.log('Message:', response.responseBody),
        close: () => console.log('Disconnected')
    }
);

// Send a message
subscription.push(JSON.stringify({ author: 'Alice', message: 'Hello!' }));
</script>
```

See [Chapter 19: atmosphere.js Client](/docs/tutorial/19-client/) for the full client API.

## Reverse Proxy (Nginx)

When deploying Atmosphere behind Nginx, the reverse proxy must be configured to pass WebSocket upgrade headers. Without this, WebSocket connections will fail and clients will fall back to long-polling.

Add the following to your Nginx configuration:

```nginx
map $http_upgrade $connection_upgrade {
    default Upgrade;
    ''      close;
}

server {
    listen 80;

    location /chat {
        proxy_pass http://127.0.0.1:8080/chat;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_buffering off;
        proxy_ignore_client_abort off;
    }
}
```

Key settings:

| Directive | Purpose |
|-----------|---------|
| `proxy_http_version 1.1` | Required for WebSocket; HTTP/1.0 does not support connection upgrades |
| `proxy_set_header Upgrade` | Forwards the client's `Upgrade: websocket` header to the backend |
| `proxy_set_header Connection` | Sets `Connection: Upgrade` when upgrading, `close` otherwise |
| `proxy_buffering off` | Disables response buffering so long-polling and SSE responses are delivered immediately |
| `proxy_ignore_client_abort off` | Ensures the backend is notified when the client disconnects |

For TLS termination at Nginx, change `proxy_pass` to `http://` (not `https://`) if the backend is plain HTTP, and add your `ssl_certificate` / `ssl_certificate_key` directives to the `server` block.
