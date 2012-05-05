## Welcome to Atmosphere: The Asynchronous WebSocket/Comet Framework
The Atmosphere Framework contains client and server side components. The majority of [popular frameworks](https://github.com/Atmosphere/atmosphere/wiki/Atmosphere-PlugIns-and-Extensions) are either supporting Atmosphere or supported natively by the framework. The Atmosphere Framework supports all majors [Browsers and Servers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers)

   Follow us on [Twitter](http://www.twitter.com/atmo_framework) or get the latest news [here](http://jfarcand.wordpress.com)

Atmosphere transparently supports WebSockets, Server Side Events (SSE), Long-Polling, HTTP Streaming (Forever frame) and JSONP.

### Official Documentation
Our Wiki contains [several tutorials](https://github.com/Atmosphere/atmosphere/wiki) for getting started. You can also browse the framework's [Javadoc](http://atmosphere.github.com/atmosphere/apidocs/) for Server Components, and [atmosphere.js](https://github.com/Atmosphere/atmosphere/wiki/jQuery.atmosphere.js-API) for Client Components


### To use Atmosphere, add the following dependency:
```xml
     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>0.9.2</version>
      </dependency>
```
      
Where atmosphere-module can be: jersey, runtime (main module), guice, jquery, redis, hazelcast, jms, jgroups or gwt. Our official release are available from Maven Central [download](http://search.maven.org/#search|ga|1|atmosphere). Atmosphere supports the majority of [Servers and Browsers](https://github.com/Atmosphere/atmosphere/wiki/Supported-WebServers-and-Browsers)

[IMPORTANT: Migrating 0.x to the new 0.9 API](https://github.com/Atmosphere/atmosphere/wiki/Migrating-your-Atmosphere-0.x-to-0.9-new-API)

#### Jump directly inside the code: WebSocket, Server Side Events (SSE) Long-Polling, JSONP and Http Streaming!
Take a look at the PubSub [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/webapp/index.html#L7)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/java/org/atmosphere/samples/pubsub/JQueryPubSub.java#L36) or the infamous Chat [Client](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/webapp/jquery/application.js#L1)-[Server](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat/src/main/java/org/atmosphere/samples/chat/ChatAtmosphereHandler.java#L32) to realize how simple Atmosphere is!

#### Must read

   [Writing a REST over WebSocket/Comet apps using JQuery](http://jfarcand.wordpress.com/2010/06/15/using-atmospheres-jquery-plug-in-to-build-applicationsupporting-both-websocket-and-comet/)

   [Which Atmosphere API should I use for my project?](http://jfarcand.wordpress.com/2011/11/07/hitchiker-guide-to-the-atmosphere-framework-using-websocket-long-polling-and-http-streaming/)

#### Latest Publications

[Introducing SwaggerSocket](http://jfarcand.wordpress.com/2012/04/26/transparently-adding-websockets-to-your-application-using-swaggersocket/)

[What's new in Atmosphere 0.9](http://jfarcand.wordpress.com/2012/04/12/atmosphere-9-9-9-9-released-tomcatglassfish-websocket-netty-framework-hazelcast-fluid-api-jquery-optimization/)

[Comet/WebSocket? Introducing the Atmosphere framework](http://www.ncolomer.net/2012/03/comewebsocket-introducing-the-atmosphere-framework/)

[Latest Presentation - Writing highly scalable WebSocket using the Atmosphere Framework](http://www.slideshare.net/jfarcand/writing-highly-scalable-websocket-using-the-atmosphere-framework)

#### Quick Start

To quickly see what Atmosphere can do with WebSocket and Comet, and If you want to play with Redis, Hazelcast, ActiveMQ(JMS) or XMPP(Gmail), [download](https://oss.sonatype.org/content/repositories/releases/org/atmosphere/samples/atmosphere-jquery-pubsub/0.9.2/atmosphere-jquery-pubsub-0.9.2.war) the [JQueryPubSub](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/java/org/atmosphere/samples/pubsub/JQueryPubSub.java#L51) sample,  uncomments the appropriate technology in the [pom.xml](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/pom.xml#L2), re-war the file or manually drop the atmosphere-{technology-name}.jar under your WEB-INF/lib to enabled it automatically

For SNAPSHOT, you'll have to add the Sonatype repo to your settings in order to be able to access the snapshot builds [Browse the artifact](https://oss.sonatype.org/content/repositories/releases/org/atmosphere/)

Several Samples are available [Download the sample, rename the file without the maven version](https://oss.sonatype.org/content/repositories/snapshots/org/atmosphere/samples/)

#### RoadMap

Atmosphere 0.9 is our official release, and our work in progress version is 1.0, targeted for end of End of May 2012

If you are interested, subscribe to our [mailing list](http://groups.google.com/group/atmosphere-framework) for more info!  We are on irc.freenode.net under #atmosphere-comet

#### Browse sample's code

[Guice](https://github.com/Atmosphere/atmosphere/blob/master/samples/chat-guice/src/main/java/org/atmosphere/samples/guice/GuiceChatConfig.java#L58)

[Spring](https://github.com/Atmosphere/atmosphere/blob/master/samples/spring-websocket/src/main/java/org/atmosphere/samples/pubsub/services/ChatService.java#L34)

[PubSub](https://github.com/Atmosphere/atmosphere/blob/master/samples/pubsub/src/main/java/org/atmosphere/samples/pubsub/PubSub.java#L76)

[JQueryPubSub](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/src/main/java/org/atmosphere/samples/pubsub/JQueryPubSub.java#L30)

[Twitter Search](https://github.com/Atmosphere/atmosphere/blob/master/samples/twitter-live-feed/src/main/java/org/atmosphere/samples/twitter/TwitterFeed.java#L41)

[JavaScript Multi Request](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-multirequest/src/main/webapp/js/main.js#L5)

[EJB](https://github.com/Atmosphere/atmosphere/blob/master/samples/atmosphere-ee6/src/main/java/org/jersey/devoxx/samples/ee6/atmosphere/TimerResource.java#L76)

[AtmosphereHandler](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-atmospherehandler-pubsub/src/main/java/org/atmosphere/samples/pubsub/AtmosphereHandlerPubSub.java#L30)

[Meteor](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-meteor-pubsub/src/main/java/org/atmosphere/samples/pubsub/MeteorPubSub.java#L30)

[JAXRS 2.0 Async API](https://github.com/Atmosphere/atmosphere/blob/master/samples/jaxrs2-chat/src/main/java/org/atmosphere/samples/chat/jersey/Jaxrs2Chat.java#L34)

#### Changes logs

0.9 release: [0.9.2](http://goo.gl/rAKQh ) [0.9.1](http://is.gd/LEgGJ7) [0.9.0](https://github.com/Atmosphere/atmosphere/issues?sort=created&labels=0.9.0&direction=desc&state=closed)

0.8 release: [0.8.6](http://is.gd/Pi4ZPo) [0.8.5](http://is.gd/yVgcaj) [0.8.4](http://is.gd/Pi4ZPo) [0.8.3](http://is.gd/znZBKZ) [0.8.2](http://is.gd/9BesxI) [0.8.0](https://github.com/Atmosphere/atmosphere/blob/master/CHANGELOGS.txt#L1)
