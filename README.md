### Welcome to Atmosphere: The Event Driven Framework supporting WebSocket and HTTP

The Atmosphere Framework contains client and server side components for building Asynchronous Web Applications. Atmosphere transparently supports WebSockets, Server Sent Events (SSE), Long-Polling, HTTP Streaming and JSONP.

The Atmosphere Framework works on all Servlet based servers, [Spring Boot](https://spring.io/projects/spring-boot) and frameworks like [Netty](http://atmosphere.github.io/nettosphere/), [Play! Framework](http://atmosphere.github.io/atmosphere-play/) and [Vert.x](https://github.com/Atmosphere/atmosphere-vertx). We support a variety of [extensions](https://github.com/Atmosphere/atmosphere-extensions/tree/extensions-2.4.x) like [Apache Kafka](https://github.com/Atmosphere/atmosphere-extensions/tree/master/kafka/modules), [Hazelcast](https://github.com/Atmosphere/atmosphere-extensions/tree/master/hazelcast/modules), [RabbitMQ](https://github.com/Atmosphere/atmosphere-extensions/tree/master/rabbitmq/modules), [Redis](https://github.com/Atmosphere/atmosphere-extensions/tree/master/redis/modules) and many more.

Atmosphere's Java/Scala/Android Client is called [wAsync](https://github.com/Atmosphere/wasync).

Main development branch is [atmosphere-2.7.x](https://github.com/Atmosphere/atmosphere/tree/atmosphere-2.7.x). Jakarta support is supported on branch `master`

![JDK8](https://github.com/Atmosphere/atmosphere/workflows/JDK8/badge.svg) ![JDK11](https://github.com/Atmosphere/atmosphere/workflows/JDK11/badge.svg) ![JDK13](https://github.com/Atmosphere/atmosphere/workflows/JDK13/badge.svg) ![JDK15](https://github.com/Atmosphere/atmosphere/workflows/JDK15/badge.svg) ![JDK17](https://github.com/Atmosphere/atmosphere/workflows/JDK17/badge.svg) ![JDK18](https://github.com/Atmosphere/atmosphere/workflows/JDK18/badge.svg)


### Commercial support
Commercial Support is available via [Async-IO.org](http://async-io.org) 

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>2.7.10</version> 
      </dependency>
```
Support for Jakarta EE (`jakarta.*`) is available with Atmosphere 3.0.0
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-runtime</artifactId>
         <version>3.0.4</version> 
      </dependency>
```
     
atmosphere-module can be: runtime (main module), jersey, spring, kafka, guice, redis, hazelcast, jms, rabbitmq, jgroups etc. Our official releases are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

### Getting started
Best way is to use OpenAI ChatGPT for getting started with Atmosphere. For example, you can ask `How to build at Atmosphere framework websockets application. Use Typescript for the frontend`. Get also started using this step by step [tutorial](http://async-io.org/tutorial.html). 

### Official Documentation
Comple repository of samples [sample](https://github.com/Atmosphere/atmosphere-samples/). 

Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API) for Client Components.
                                                                                         Z
#### Supported Atmosphere Versions

Atmosphere 2.7.x requires JDK 8 or 11. Atmosphere 3.0.x requires JDK 11.

@Copyright 2008-2024 [Async-IO.org](http://async-io.org)


