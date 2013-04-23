# Commercial support now available!
Thanks to the growing popularity of the framework, Commercial Support is now available via [Async-IO.org](http://async-io.org) Want the project to stay alive? Please donate by visiting the [Async-IO.org](http://async-io.org) and click on donate button!

## Welcome to Atmosphere: The Asynchronous WebSocket/Comet Framework
The Atmosphere Framework contains client and server side components for building Asynchronous Web Application. The majority of [popular frameworks](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions) are either supporting Atmosphere or supported natively by the framework. The Atmosphere Framework supports all majors [Browsers and Servers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers)

<center>Follow us on [Twitter](http://www.twitter.com/atmo_framework) or get the latest news [here](http://jfarcand.wordpress.com)</center>

Atmosphere transparently supports WebSockets, Server Side Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

Don't want to use a JavaEE server? Take a look at [Atmosphere's Netty Framework implementation](https://github.com/Atmosphere/nettosphere)

Looking for a Java Client for working with Atmosphere, Andoid or Node.js? Look at [wAsync](https://github.com/Atmosphere/wasync)

Using the Play Framework? Try our [Atmosphere's module](https://github.com/Atmosphere/atmosphere-play)

### Tutorial

Get started using this step by step [tutorial](http://async-io.org/tutorial.html)

### Official Documentation
Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started as well as [FAQ](https://github.com/Atmosphere/atmosphere/wiki/Frequently-Asked-Questions). You can also browse the framework's [Javadoc](http://atmosphere.github.com/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-API) for Client Components

### Quick Start

The Atmosphere's Framework ships with many examples describing how to implements WebSockets, Server Side Events, Long Polling, Http Streaming and JSONP client's application. Take a look at [this page](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-the-samples) to pick the best sample to starts with.

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>1.0.12</version>
      </dependency>
```
      
Where atmosphere-module can be: jersey, runtime (main module), guice, jquery, redis, hazelcast, jms, jgroups or gwt. Our official release are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

[IMPORTANT: Migrating 0.x to the new 1.0 API](https://github.com/Atmosphere/atmosphere/wiki/Migrating-your-Atmosphere-0.x-to-0.9-new-API)

#### Jump directly inside the code: WebSockets, Server Side Events (SSE), Long-Polling, JSONP and Http Streaming!

Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/webapp/index.html#L7)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/java/org/atmosphere/samples/pubsub/JQueryPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/webapp/jquery/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/Chat.java#L32) to realize how simple Atmosphere is!

[Top](#Top)

### Help this community by testing our next release
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>1.1.0.RC1</version>
      </dependency>
```
[Top](#Top)

#### RoadMap

Atmosphere 1.0.12 is our official release, and our work in progress version is 1.1, targeted for end of End of April 2013

If you are interested, subscribe to our [mailing list](http://groups.google.com/group/atmosphere-framework) for more info!  We are on irc.freenode.net under #atmosphere-comet

[Top](#Top)

#### Changes logs

1.0 release: [1.0.11](http://goo.gl/TUzk2) [1.0.10](http://goo.gl/teWkz) [1.0.8](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.8&page=1&state=open) [1.0.6](http://goo.gl/Grd2F) [1.0.5](http://goo.gl/nVRyu) [1.0.4](http://goo.gl/r24xA) [1.0.3](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.3&page=1&state=closed) [1.0.2](http://goo.gl/RqaS9) [1.0.1](http://goo.gl/UILd3 ) [1.0](https://github.com/Atmosphere/atmosphere/issues?labels=1.0.0&page=1&state=closed)

0.9 release: [0.9.7](http://is.gd/ETHPFH) [0.9.5](https://github.com/Atmosphere/atmosphere/issues?labels=0.9.5&page=1&sort=updated&state=closed) [0.9.4](http://is.gd/hZtv2a) [0.9.2/0.9.3](http://goo.gl/rAKQh ) [0.9.1](http://is.gd/LEgGJ7) [0.9.0](https://github.com/Atmosphere/atmosphere/issues?sort=created&labels=0.9.0&direction=desc&state=closed)

0.8 release: [0.8.6](http://is.gd/Pi4ZPo) [0.8.5](http://is.gd/yVgcaj) [0.8.4](http://is.gd/Pi4ZPo) [0.8.3](http://is.gd/znZBKZ) [0.8.2](http://is.gd/9BesxI) [0.8.0](https://github.com/Atmosphere/atmosphere/blob/master/CHANGELOGS.txt#L1)

[Top](#Top)

## Build Status
Powered by [Cloudbees](https://jfarcabd.ci.cloudbees.com/)

## License
YourKit is kindly supporting open source projects with its full-featured Java Profiler.
YourKit, LLC is the creator of innovative and intelligent tools for profiling
Java and .NET applications. Take a look at YourKit's leading software products:
[YourKit Java Profiler](http://www.yourkit.com/java/profiler/index.jsp) and [YourKit .NET Profiler](http://www.yourkit.com/java/profiler/index.jsp)

