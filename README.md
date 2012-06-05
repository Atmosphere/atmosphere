## Welcome to Atmosphere: The Asynchronous WebSocket/Comet Framework
The Atmosphere Framework contains client and server side components for building Asynchronous Web Application. The majority of [popular frameworks](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions) are either supporting Atmosphere or supported natively by the framework. The Atmosphere Framework supports all majors [Browsers and Servers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers)

<center>Follow us on [Twitter](http://www.twitter.com/atmo_framework) or get the latest news [here](http://jfarcand.wordpress.com)</center>

Atmosphere transparently supports WebSockets, Server Side Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

### Official Documentation
Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started. You can also browse the framework's [Javadoc](http://atmosphere.github.com/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-API) for Client Components

### Quick Start

The Atmosphere's Framework ships with many examples describing how to implements WebSockets, Server Side Events, Long Polling, Http Streaming and JSONP client's application. Take a look at [this page](https://github.com/Atmosphere/atmosphere/wiki/Getting-Started-with-the-samples) to pick the best sample to starts with.

### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>0.9.5</version>
      </dependency>
```
      
Where atmosphere-module can be: jersey, runtime (main module), guice, jquery, redis, hazelcast, jms, jgroups or gwt. Our official release are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere).

[IMPORTANT: Migrating 0.x to the new 0.9 API](https://github.com/Atmosphere/atmosphere/wiki/Migrating-your-Atmosphere-0.x-to-0.9-new-API)

#### Jump directly inside the code: WebSocket, Server Side Events (SSE), Long-Polling, JSONP and Http Streaming!

Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/webapp/index.html#L7)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/java/org/atmosphere/samples/pubsub/JQueryPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/webapp/jquery/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/ChatAtmosphereHandler.java#L32) to realize how simple Atmosphere is!


#### Latest Publications

[Using Socket.IO with Atmosphere](http://jfarcand.wordpress.com/2012/06/04/writing-socket-io-application-that-runs-on-the-jvm/)

[Grails Events Bus and sample](http://groovyflow.com/posts/489#.T8TxDpl1DvU)

[Introducing SwaggerSocket](http://jfarcand.wordpress.com/2012/04/26/transparently-adding-websockets-to-your-application-using-swaggersocket/)

[Writing HTML5 Server Side Events using Atmosphere](http://jfarcand.wordpress.com/2012/05/14/writing-portable-html5-server-side-evens-application-using-the-atmosphere-framework/)

[Comet/WebSocket? Introducing the Atmosphere framework](http://www.ncolomer.net/2012/03/comewebsocket-introducing-the-atmosphere-framework/)

[Latest Presentation - Writing highly scalable WebSocket using the Atmosphere Framework](http://www.slideshare.net/jfarcand/writing-highly-scalable-websocket-using-the-atmosphere-framework)

#### RoadMap

Atmosphere 0.9 is our official release, and our work in progress version is 1.0, targeted for end of End of June 2012

If you are interested, subscribe to our [mailing list](http://groups.google.com/group/atmosphere-framework) for more info!  We are on irc.freenode.net under #atmosphere-comet

#### Changes logs

0.9 release: [0.9.5](https://github.com/Atmosphere/atmosphere/issues?labels=0.9.5&page=1&sort=updated&state=closed) [0.9.4](http://is.gd/hZtv2a) [0.9.2/0.9.3](http://goo.gl/rAKQh ) [0.9.1](http://is.gd/LEgGJ7) [0.9.0](https://github.com/Atmosphere/atmosphere/issues?sort=created&labels=0.9.0&direction=desc&state=closed)

0.8 release: [0.8.6](http://is.gd/Pi4ZPo) [0.8.5](http://is.gd/yVgcaj) [0.8.4](http://is.gd/Pi4ZPo) [0.8.3](http://is.gd/znZBKZ) [0.8.2](http://is.gd/9BesxI) [0.8.0](https://github.com/Atmosphere/atmosphere/blob/master/CHANGELOGS.txt#L1)

## Build Status
[![Build Status](https://buildhive.cloudbees.com/job/Atmosphere/job/atmosphere/badge/icon)](https://buildhive.cloudbees.com/job/Atmosphere/job/atmosphere/)
