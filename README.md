<p align="center">
  <img src="http://atmosphere.github.io/atmosphere/images/atmosphere.png" alt="LOGO"/>
</p>
### Welcome to Atmosphere: The Asynchronous WebSocket/Comet Framework
The Atmosphere Framework contains client and server side components for building Asynchronous Web Applications. The majority of [popular frameworks](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions) are either supporting Atmosphere or supported natively by the framework. The Atmosphere Framework supports all major [Browsers and Servers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers).

<center>Follow us on [Twitter](http://www.twitter.com/atmo_framework)</center>.

Atmosphere transparently supports WebSockets, Server Side Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

The Atmosphere Framework Stack consists of: 

<p align="center">
  <img src="http://atmosphere.github.io/atmosphere/images/stack.png"Atmosphere Framework Stack" alt="Atmosphere Stack"/>
</p>
The Atmosphere Framework Stack works on all Servlet based servers including Tomcat, JBoss Jetty, Resin, GlassFish, Undertow, WebSphere, WebLogic etc. Not running a Servlet Container? [Netty](http://atmosphere.github.io/nettosphere/), [Play! Framework](http://atmosphere.github.io/atmosphere-play/)  or [Vert.x](https://github.com/Atmosphere/atmosphere-vertx).

Atmosphere's Java/Scala Client is called [wAsync](https://github.com/Atmosphere/wasync).

Using another framework? Look at the list of supported [extensions](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions). Easiest way to learn Atmosphere is by trying a [sample](https://github.com/Atmosphere/atmosphere-samples/).

### Commercial support now available!
Commercial Support is now available via [Async-IO.org](http://async-io.org) Want the project to stay alive? Please donate by visiting the [Async-IO.org](http://async-io.org) and click on the donate button!

### Tutorial

Get started using this step by step [tutorial](http://async-io.org/tutorial.html).

### Official Documentation
Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.com/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-atmosphere.js-API) for Client Components.

### Quick Start

The Atmosphere's Framework ships with many examples describing how to implement WebSockets, Server Side Events, Long Polling, HTTP Streaming and JSONP client applications. Take a look at [this page](https://github.com/Atmosphere/atmosphere-samples/) to pick the best sample to start with.

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>2.1.3</version>
      </dependency>
```
      
Where atmosphere-module can be: jersey, runtime (main module), guice, redis, hazelcast, jms, jgroups or gwt. Our official releases are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

#### Jump directly inside the code: WebSockets, Server Side Events (SSE), Long-Polling, JSONP and HTTP Streaming!

Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/webapp/index.html#L34)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/jersey-pubsub/src/main/java/org/atmosphere/samples/pubsub/JerseyPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/webapp/javascript/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere-samples/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/Chat.java#L32) to realize how simple Atmosphere is!

[Top](#Top)

If you are interested, subscribe to our [mailing list](http://groups.google.com/group/atmosphere-framework) for more info! We also have IRC channel on irc.freenode.net under #atmosphere-comet.

[Top](#Top)

#### Changelogs

2.1 release: [2.1.2](http://goo.gl/0BSpfj) [2.1.1](http://goo.gl/F9fr45) [2.1.0](https://github.com/Atmosphere/atmosphere/issues?labels=2.1&page=1&state=closed)

2.0 release: [2.0.8](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.8&page=1&state=closed) [2.0.7](http://goo.gl/nefkn7) [2.0.6](http://goo.gl/MvFSR1) [2.0.5](http://goo.gl/jFLDZc) [2.0.4](http://goo.gl/zTbcgC) [2.0.3](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.3&page=1&state=closed) [2.0.2] (http://goo.gl/44qnsU) [2.0.1](https://github.com/Atmosphere/atmosphere/issues?labels=2.0.1&page=1&state=closed)

1.0 release: [1.0.17](http://goo.gl/y2QImv) [1.0.16](http://goo.gl/aWhhtS) [1.0.14](http://goo.gl/Ni3e5l) [1.0.13](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.13&milestone=&page=1&state=closed) [1.0.11](http://goo.gl/TUzk2) [1.0.10](http://goo.gl/teWkz) [1.0.8](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.8&page=1&state=open) [1.0.6](http://goo.gl/Grd2F) [1.0.5](http://goo.gl/nVRyu) [1.0.4](http://goo.gl/r24xA) [1.0.3](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.3&page=1&state=closed) [1.0.2](http://goo.gl/RqaS9) [1.0.1](http://goo.gl/UILd3 ) [1.0](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.0&page=1&state=closed)

[Top](#Top)

## Build Status
[![Build Status](https://buildhive.cloudbees.com/job/Atmosphere/job/atmosphere/badge/icon)](https://buildhive.cloudbees.com/job/Atmosphere/job/atmosphere/)

[![githalytics.com alpha](https://cruel-carlota.pagodabox.com/451c0e9dd7bfaa28ea12114ceb11695b "githalytics.com")](http://githalytics.com/Atmosphere/atmosphere)


