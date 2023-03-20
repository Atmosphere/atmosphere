### Welcome to Atmosphere: The Event Driven Framework supporting WebSocket and HTTP

The Atmosphere Framework contains client and server side components for building Asynchronous Web Applications. Atmosphere transparently supports WebSockets, Server Sent Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

The Atmosphere Framework can be used with 

<p align="center">
  <img src="http://atmosphere.github.io/atmosphere/images/stack.png"Atmosphere Framework Stack alt="Atmosphere Framework"/>
</p>

The Atmosphere Framework works on all Servlet based servers, [Spring Boot](https://spring.io/projects/spring-boot) and frameworks like [Netty](http://atmosphere.github.io/nettosphere/), [Play! Framework](http://atmosphere.github.io/atmosphere-play/) and [Vert.x](https://github.com/Atmosphere/atmosphere-vertx). We support a variety of [extensions](https://github.com/Atmosphere/atmosphere-extensions/tree/extensions-2.4.x) like [Apache Kafka](https://github.com/Atmosphere/atmosphere-extensions/tree/master/kafka/modules), [Hazelcast](https://github.com/Atmosphere/atmosphere-extensions/tree/master/hazelcast/modules), [RabbitMQ](https://github.com/Atmosphere/atmosphere-extensions/tree/master/rabbitmq/modules), [Redis](https://github.com/Atmosphere/atmosphere-extensions/tree/master/redis/modules) and many more.

Atmosphere's Java/Scala/Android Client is called [wAsync](https://github.com/Atmosphere/wasync).

Main development branch is [atmosphere-2.7.x](https://github.com/Atmosphere/atmosphere/tree/atmosphere-2.7.x) Only pull request for that branch will be accepted.

![JDK8](https://github.com/Atmosphere/atmosphere/workflows/JDK8/badge.svg) ![JDK11](https://github.com/Atmosphere/atmosphere/workflows/JDK11/badge.svg) ![JDK13](https://github.com/Atmosphere/atmosphere/workflows/JDK13/badge.svg) ![JDK15](https://github.com/Atmosphere/atmosphere/workflows/JDK15/badge.svg) ![JDK17](https://github.com/Atmosphere/atmosphere/workflows/JDK17/badge.svg) ![JDK18](https://github.com/Atmosphere/atmosphere/workflows/JDK18/badge.svg)


### Commercial support
Commercial Support is available via [Async-IO.org](http://async-io.org) 

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>2.7.9</version> // MUST BE USED with atmosphere-javascript 3.1+
      </dependency>
```
Support for Jakarta EE (`jakarta.*`) is available with Atmosphere 3.0.0
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-runtime</artifactId>
         <version>3.0.3</version> 
      </dependency>
```
     
atmosphere-module can be: runtime (main module), jersey, spring, kafka, guice, redis, hazelcast, jms, rabbitmq, jgroups etc. Our official releases are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

### Tutorial
Get started using this step by step [tutorial](http://async-io.org/tutorial.html).

### Official Documentation
Easiest way to learn Atmosphere is by trying a [sample](https://github.com/Atmosphere/atmosphere-samples/). 

Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.io/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/atmosphere.js-API) for Client Components.

### Quick Start

The Atmosphere Framework ships with many examples describing how to implement WebSockets, Server-Sent Events, Long-Polling, HTTP Streaming and JSONP client applications. Take a look at [this page](https://github.com/Atmosphere/atmosphere-samples/) to pick the best sample to start with.

#### Jump directly inside the code: WebSockets, Server-Sent Events (SSE), Long-Polling, JSONP and HTTP Streaming!

Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/webapp/index.html#L34)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/java/org/atmosphere/samples/pubsub/JerseyPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/webapp/javascript/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/Chat.java#L32) to realize how simple Atmosphere is!
                                                                                          Z
#### Atmosphere and JDK Versions

Atmosphere 2.5.x requires JDK 8 or 11. Atmosphere 2.4.x requires JDK 1.7 or newer

#### Versions

3.0.x releases: [3.0.1](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aclosed+label%3A3.0.1) [3.0.0](Jakarta Support)

2.7.x releases: [2.7.9](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.9+is%3Aclosed) [2.7.8](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.8+is%3Aclosed) [2.7.7](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.7+is%3Aclosed) [2.7.6](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.6+is%3Aclosed) [2.7.5](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+is%3Aclosed+label%3A2.7.5) [2.7.4](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+is%3Aclosed+label%3A2.7.4) [2.7.3](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.3+is%3Aclosed) [2.7.1](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+is%3Aclosed+label%3A2.7.1) [2.7.0](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.7.0+is%3Aclosed)

#### End Of Life Versions (go to [http://async-io.org](https://www.async-io.org/) for commercial support)
2.6.x releases: [2.6.4](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.6.4+is%3Aclosed) [2.6.1](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.6.1+is%3Aclosed) [2.6.0](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.6.0+is%3Aclosed)

2.5.x releases: [2.5.14](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.5.14+is%3Aclosed) [2.5.9](https://github.com/Atmosphere/atmosphere/milestone/23?closed=1) [2.5.5](https://github.com/Atmosphere/atmosphere/milestone/24?closed=1) [2.5.3](https://github.com/Atmosphere/atmosphere/milestone/22?closed=1) [2.5.2](https://github.com/Atmosphere/atmosphere/milestone/21?closed=1) [2.5.0](https://github.com/Atmosphere/atmosphere/milestone/19)
 
2.4.x releases: [2.4.32](https://github.com/Atmosphere/atmosphere/milestone/21?closed=1) [2.4.30](https://github.com/Atmosphere/atmosphere/issues/2349)[2.4.29](https://github.com/Atmosphere/atmosphere/milestone/18?closed=1) [2.4.27](https://github.com/Atmosphere/atmosphere/milestone/17?closed=1) [2.4.26](https://github.com/Atmosphere/atmosphere/milestone/16?closed=1) [2.4.24](https://github.com/Atmosphere/atmosphere/milestone/15?closed=1) [2.4.23](https://github.com/Atmosphere/atmosphere/milestones?state=closed) [2.4.22](https://github.com/Atmosphere/atmosphere/milestone/14?closed=1) [2.4.19](https://github.com/Atmosphere/atmosphere/milestone/9?closed=1) [2.4.18](https://github.com/Atmosphere/atmosphere/milestone/8?closed=1) [2.4.17](https://github.com/Atmosphere/atmosphere/milestone/7?closed=1) [2.4.16](https://github.com/Atmosphere/atmosphere/milestone/6?closed=1) [2.4.13](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+is%3Aclosed+label%3A2.4.13) [2.4.12](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.4.12+is%3Aclosed) [2.4.11](https://github.com/Atmosphere/atmosphere/issues?utf8=%E2%9C%93&q=label%3A2.4.11%20) [2.4.9](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.4.9+is%3Aclosed) [2.4.8](https://github.com/Atmosphere/atmosphere/issues?utf8=%E2%9C%93&q=is%3Aclosed%20label%3A2.4.8%20) [2.4.7](https://github.com/Atmosphere/atmosphere/issues?utf8=%E2%9C%93&q=label%3A2.4.7) [2.4.6](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aclosed+label%3A2.4.6) [2.4.5](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aclosed+label%3A2.4.5) [2.4.4](https://goo.gl/3CZ1qV) [2.4.3](https://goo.gl/n5s5GL) [2.4.2](https://goo.gl/TulSUl) [2.4.1](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.4.1+is%3Aclosed) [2.4.0](https://goo.gl/GpB1B1)

@Copyright 2008-2023 [Async-IO.org](http://async-io.org)


