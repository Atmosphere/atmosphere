### Welcome to Atmosphere: The Event Driven Framework supporting WebSocket and HTTP

The Atmosphere Framework contains client and server side components for building Asynchronous Web Applications. Atmosphere transparently supports WebSockets, Server Sent Events (SSE), Long-Polling, HTTP Streaming and JSONP.

The Atmosphere Framework works on all Servlet based servers, [Spring Boot](https://spring.io/projects/spring-boot) and frameworks like [Netty](http://atmosphere.github.io/nettosphere/), [Play! Framework](http://atmosphere.github.io/atmosphere-play/) and [Vert.x](https://github.com/Atmosphere/atmosphere-vertx). We support a variety of [extensions](https://github.com/Atmosphere/atmosphere-extensions/tree/extensions-2.4.x) like [Apache Kafka](https://github.com/Atmosphere/atmosphere-extensions/tree/master/kafka/modules), [Hazelcast](https://github.com/Atmosphere/atmosphere-extensions/tree/master/hazelcast/modules), [RabbitMQ](https://github.com/Atmosphere/atmosphere-extensions/tree/master/rabbitmq/modules), [Redis](https://github.com/Atmosphere/atmosphere-extensions/tree/master/redis/modules) and many more.

Atmosphere's Java/Scala/Android Client is called [wAsync](https://github.com/Atmosphere/wasync).

Query the code using [DeepWiki](https://deepwiki.com/Atmosphere/atmosphere)

Atmosphere 3.0.x requires JDK 17 or later

[![Atmopshere 3.0.x](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml)

Atmosphere 2.7.x on JDK 8 up to 25

[![Atmopshere 2.7.x](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml/badge.svg?branch=atmosphere-2.7.x)](https://github.com/Atmosphere/atmosphere/actions/workflows/maven.yml)


### Commercial support
Commercial Support is available via [Async-IO.org](http://async-io.org)

### To use Atmosphere, add the following dependency:

#### Standard Applications
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-runtime</artifactId>
    <version>3.0.15</version>
</dependency>
```

#### Spring Boot Applications

The `atmosphere-spring-boot-starter` provides zero-configuration integration with Spring Boot 4.0.2+. It is completely optional — applications using `atmosphere-runtime` directly are unaffected.

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>3.0.15</version>
</dependency>
```

This auto-configures AtmosphereServlet, a Spring DI bridge (`SpringAtmosphereObjectFactory`), and an optional Actuator health indicator. Configure via `application.yml`:

```yaml
atmosphere:
  servlet-path: /atmosphere/*
  packages: com.example.atmosphere
  session-support: false
  websocket-support: true
  heartbeat-interval-in-seconds: 60
  init-params:
    org.atmosphere.cpr.broadcasterCacheClass: org.atmosphere.cache.UUIDBroadcasterCache
```

| Property | Default | Description |
|----------|---------|-------------|
| `servlet-path` | `/atmosphere/*` | Servlet URL mapping |
| `packages` | | Annotation scanning packages |
| `order` | `0` | Servlet load-on-startup order |
| `session-support` | `false` | Enable HttpSession support |
| `websocket-support` | | Enable/disable WebSocket |
| `heartbeat-interval-in-seconds` | | Server heartbeat frequency |
| `broadcaster-class` | | Custom Broadcaster implementation |
| `broadcaster-cache-class` | | Custom BroadcasterCache implementation |
| `init-params` | | Map of any `ApplicationConfig` key/value |

#### Atmosphere 2.7.x
```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-{atmosphere-module}</artifactId>
    <version>2.7.15</version>
</dependency>
```

atmosphere-module can be: runtime (main module), jersey, spring, kafka, guice, redis, hazelcast, jms, rabbitmq, jgroups etc. Our official releases are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

### Official Documentation
Complete repository of samples [sample](https://github.com/Atmosphere/atmosphere-samples/).

Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API) for Client Components.

### Getting started

#### Prerequisites
- **Atmosphere 3.0.x**: Java 17 or later
- **Atmosphere 2.7.x**: Java 8 or later

For managing your Java project and its dependencies, you'll need a build automation tool. We recommend [Maven](https://maven.apache.org/), which is widely used in the Java ecosystem.

#### Project Setup
Create a new project using Maven. Add Atmosphere as a dependency in your `pom.xml` to access all the necessary libraries.

#### Server Configuration
In your project, you'll define a server endpoint that listens to incoming connections. Atmosphere's annotations and resource handlers make this process straightforward.

#### Running Your Server

With the server set up, use your IDE or the Maven CLI to compile and run your application.

#### Create a Client
Your web client will need to establish a connection to your server. You can create a simple HTML page with JavaScript to connect and communicate with your server endpoint.

#### Keep Going!
Once you've got the basics down, explore the full range of Atmosphere's capabilities to create more sophisticated real-time applications.

For detailed instructions, examples, and advanced configurations, refer to the [official Atmosphere tutorial](https://www.async-io.org/tutorial.html).

@Copyright 2008-2026 [Async-IO.org](http://async-io.org)
