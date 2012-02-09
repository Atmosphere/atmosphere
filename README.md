Atmosphere is a POJO based framework using Inversion of Control (IoC) to bring Push/Comet and Websocket to the masses! Finally a framework which can run on any Java based Web Server, including Tomcat, Jetty, GlassFish, Weblogic, Grizzly, JBossWeb and JBoss, Resin, etc. without having to learn how Comet or WebSocket support has been differently implemented by all those Containers. The Atmosphere Framework has both client (Javascript, JQuery, GWT) and server components.

Framework like Jersey, GWT, Wicket, Vaadin, Guice, Spring, JSF, Scalatra, Grails etc are supported. You can use Scala, JRuby, Groovy and Java to write Atmosphere application. Massive scalability with our Cluster plugin architecture (JGroups, JMS/ActiveMQ, Redis, XMPP, etc.)

Follow us on [Twitter](http://www.twitter.com/atmo_framework)  or get the latest news [here](http://jfarcand.wordpress.com)

The Atmosphere Framework ships with a JQuery Plug In that can be used with any Comet or WebSocket Framework:

[Getting started](http://jfarcand.wordpress.com/2010/06/15/using-atmospheres-jquery-plug-in-to-build-applicationsupporting-both-websocket-and-comet/)

[Devoxx 2011 - Writing highly scalable WebSocket using the Atmosphere Framework](http://www.slideshare.net/jfarcand/writing-highly-scalable-websocket-using-the-atmosphere-framework)

Latest Publications

[AtmosphereHandler, Meteor, REST Resource or Native WebSocket](http://jfarcand.wordpress.com/2011/11/07/hitchiker-guide-to-the-atmosphere-framework-using-websocket-long-polling-and-http-streaming/)

Download Atmosphere Whitepaper

[White Paper](https://github.com/Atmosphere/atmosphere/blob/master/docs/atmosphere_whitepaper.pdf)

Browse JavaDoc

[Javadoc](http://atmosphere.github.com/atmosphere/apidocs/)

If you want to play with Redis, Hazelcast, ActiveMQ(JMS) or XMPP(Gmail), uncomments the appropriate technology and rebuild, or drop the atmosphere-{name}.jar under your WEB-INF/lib to enabled it automatically

[pom.xml](https://github.com/Atmosphere/atmosphere/blob/master/samples/jquery-pubsub/pom.xml#L2)

If you are using Maven, just add the following dependency:

     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>0.8.5</version>
      </dependency>

Where atmosphere-module can be: jersey, runtime, guice, jquery, redis, jms, jgroups or gwt,. Our official release are available from Maven Central. For SNAPSHOT, you'll have to add the Sonatype repo to your settings in order to be able to access the snapshot builds:

[Browse the artifact](https://oss.sonatype.org/content/repositories/releases/org/atmosphere/)

Download samples

[Download the sample, rename the file without the maven version](https://oss.sonatype.org/content/repositories/snapshots/org/atmosphere/samples/)

Atmosphere 0.8.5 is our official release [see what's changed since 0.8.4](http://is.gd/yVgcaj), and our work in progress version is 0.9, targeted for end of Mid March 2012

If you are interested, subscribe to our mailing list (http://groups.google.com/group/atmosphere-framework) for more info!  We are on irc.freenode.net under #atmosphere-comet
