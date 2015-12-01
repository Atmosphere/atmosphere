<p align="center">
<img src="http://atmosphere.github.io/atmosphere/images/atmosphere.png" alt="LOGO"/>
</p>
### Welcome to Atmosphere: The Asynchronous WebSocket/Comet Framework
The Atmosphere Framework contains client and server side components for building Asynchronous Web Applications. The majority of [popular frameworks](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions) are either supporting Atmosphere or supported natively by the framework. The Atmosphere Framework supports all major [Browsers and Servers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers).

<p align="center">Follow us on <a href="http://www.twitter.com/atmo_framework">Twitter</a></p>
Atmosphere transparently supports WebSockets, Server Sent Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

The Atmosphere Framework Stack consists of: 

<p align="center">
  <img src="http://atmosphere.github.io/atmosphere/images/stack.png"Atmosphere Framework Stack" alt="Atmosphere Stack"/>
</p>
The Atmosphere Framework Stack works on all Servlet based servers including Tomcat, JBoss Jetty, Resin, GlassFish, Undertow, WebSphere, WebLogic etc. Not running a Servlet Container? [Netty](http://atmosphere.github.io/nettosphere/), [Play! Framework](http://atmosphere.github.io/atmosphere-play/) or [Vert.x](https://github.com/Atmosphere/atmosphere-vertx). We support a variety of extensions like [STOMP](https://github.com/Atmosphere/atmosphere-stomp/), [Redis](https://github.com/Atmosphere/atmosphere-extensions/tree/master/redis), [Hazelcast](https://github.com/Atmosphere/atmosphere-extensions/tree/master/hazelcast), [JMS](https://github.com/Atmosphere/atmosphere-extensions/tree/master/jms), [JGroups](https://github.com/Atmosphere/atmosphere-extensions/tree/master/jgroups) and [many more](https://github.com/Atmosphere/atmosphere-extensions/). Support for [Socket.IO](https://github.com/Atmosphere/atmosphere-extensions/tree/master/socketio), [SockJS](https://github.com/Atmosphere/atmosphere-extensions/tree/master/sockjs) and [Cometd](https://github.com/Atmosphere/atmosphere-extensions/tree/master/cometd) are also available.

Using another framework? Look at the list of supported [extensions](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions). Easiest way to learn Atmosphere is by trying a [sample](https://github.com/Atmosphere/atmosphere-samples/). 

Atmosphere's Java/Scala/Android Client is called [wAsync](https://github.com/Atmosphere/wasync).

### Atmosphere.Next 
Active Development of Atmosphere 3 started. [Join the community to help](https://github.com/Atmosphere/atmosphere/issues/1911)

### Commercial support
Commercial Support is available via [Async-IO.org](http://async-io.org) 

### Tutorial

Get started using this step by step [tutorial](http://async-io.org/tutorial.html).

### Official Documentation
Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.com/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-atmosphere.js-API) for Client Components.

### Quick Start

The Atmosphere Framework ships with many examples describing how to implement WebSockets, Server-Sent Events, Long-Polling, HTTP Streaming and JSONP client applications. Take a look at [this page](https://github.com/Atmosphere/atmosphere-samples/) to pick the best sample to start with.

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>2.4.1</version>
      </dependency>
```
      
Where atmosphere-module can be: sockjs, kafka, jersey, runtime (main module), spring, guice, redis, hazelcast, jms, rabbitmq, jgroups or gwt. Our official releases are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

#### Jump directly inside the code: WebSockets, Server-Sent Events (SSE), Long-Polling, JSONP and HTTP Streaming!

Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/webapp/index.html#L34)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/java/org/atmosphere/samples/pubsub/JerseyPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/webapp/javascript/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/Chat.java#L32) to realize how simple Atmosphere is!

[Top](#Top)

If you are interested, subscribe to our [mailing list](http://groups.google.com/group/atmosphere-framework) for more info!.

[Top](#Top)

#### Versions

[Change Log](./CHANGELOG.md)

2.4.x: [2.4.1](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.4.1+is%3Aclosed) [2.4.0](https://goo.gl/GpB1B1)

2.3.x: release: [2.3.6](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+is%3Aclosed+label%3A2.3.6) [2.3.5](https://goo.gl/BVr1PS) [2.3.4](https://goo.gl/5eiQXb) [2.3.3](https://goo.gl/6Yfr0p ) [2.3.2](https://goo.gl/PQ60X0 ) [2.3.1](https://goo.gl/6o9gjc) [2.3.0](https://github.com/Atmosphere/atmosphere/issues?q=label%3A2.3.0+is%3Aclosed)

2.2.x release: [2.2.9](https://goo.gl/DkOD2l) [2.2.8](https://goo.gl/WoPC3N) [2.2.7](https://goo.gl/biW2Co) [2.2.6](http://goo.gl/kqZSb0) [2.2.5](http://goo.gl/2lNzg2) [2.2.4](http://goo.gl/bOLCW2) [2.2.3](http://goo.gl/1DXKP3) [2.2.2](http://goo.gl/i3W2v5) [2.2.1](http://goo.gl/glEj7L) [2.2.0](http://goo.gl/3hrlZH)

#### End Of Life Versions (go to [http://async-io.org](http://async-io.org) for commercial support)

2.1.x release: [2.1.12](https://goo.gl/r829Vr) [2.1.11](https://goo.gl/E9xH2y) [2.1.10](http://goo.gl/2zuMql) [2.1.9](http://goo.gl/3HyZCK) [2.1.8](http://goo.gl/YxX1m9) [2.1.7](http://goo.gl/p41cCc) [2.1.6](http://goo.gl/UYvBxA) [2.1.5](http://goo.gl/jx5pdc ) [2.1.4](http://goo.gl/5HiZM7) [2.1.2](http://goo.gl/0BSpfj) [2.1.1](http://goo.gl/F9fr45) [2.1.0](https://github.com/Atmosphere/atmosphere/issues?labels=2.1&page=1&state=closed)

2.0.x release: [2.0.10](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+label%3A2.0.10+is%3Aclosed) [2.0.9](https://github.com/Atmosphere/atmosphere/issues?q=is%3Aissue+label%3A2.0.9+is%3Aclosed) [2.0.8](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.8&page=1&state=closed) [2.0.7](http://goo.gl/nefkn7) [2.0.6](http://goo.gl/MvFSR1) [2.0.5](http://goo.gl/jFLDZc) [2.0.4](http://goo.gl/zTbcgC) [2.0.3](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.3&page=1&state=closed) [2.0.2] (http://goo.gl/44qnsU) [2.0.1](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.1&page=1&state=closed)

1.0 release: 1.0.19 [1.0.17](http://goo.gl/y2QImv) [1.0.16](http://goo.gl/aWhhtS) [1.0.14](http://goo.gl/Ni3e5l) [1.0.13](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.13&milestone=&page=1&state=closed) [1.0.11](http://goo.gl/TUzk2) [1.0.10](http://goo.gl/teWkz) [1.0.8](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.8&page=1&state=open) [1.0.6](http://goo.gl/Grd2F) [1.0.5](http://goo.gl/nVRyu) [1.0.4](http://goo.gl/r24xA) [1.0.3](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.3&page=1&state=closed) [1.0.2](http://goo.gl/RqaS9) [1.0.1](http://goo.gl/UILd3 ) [1.0](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.0&page=1&state=closed)

[Top](#Top)

## Build Status
[![Build Status](https://travis-ci.org/Atmosphere/atmosphere.svg?branch=master)](https://travis-ci.org/Atmosphere/atmosphere)

![Analytics](https://ga-beacon.appspot.com/UA-31990725-2/Atmosphere/atmosphere)

@Copyright 2015 [Async-IO.org](http://async-io.org)


