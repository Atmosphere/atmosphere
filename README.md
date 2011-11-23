Atmosphere is a POJO based framework using Inversion of Control (IoC) to bring Push/Comet and Websocket to the masses! Finally a framework which can run on any Java based Web Server, including Tomcat, Jetty, GlassFish, Weblogic, Grizzly, JBossWeb and JBoss, Resin, etc. without having to learn how Comet or WebSocket support has been differently implemented by all those Containers. The Atmosphere Framework has both client (Javascript, JQuery, GWT) and server components.

Servlet 3.0 is supported along with framework like Jersey (natively), GWT (natively), Wicket, Guice, Spring etc. and programming language like JRuby, Groovy and Scala. We also support massive scalability with our Cluster plugin architecture (JGroups, JMS/ActiveMQ, Redis, XMPP, etc.)

Atmosphere ships with a JQuery Plug In that can be used with any Comet or WebSocket Framework:

[Getting started](http://jfarcand.wordpress.com/2010/06/15/using-atmospheres-jquery-plug-in-to-build-applicationsupporting-both-websocket-and-comet/)

Latest Publication

[AtmosphereHandler, Meteor, REST Resource or NativeWebSocket](http://jfarcand.wordpress.com/2011/11/07/hitchiker-guide-to-the-atmosphere-framework-using-websocket-long-polling-and-http-streaming/)
[Devoxx 2011 - Writing highly scalable WebSocket using the Atmosphere Framework](http://www.slideshare.net/jfarcand/writing-highly-scalable-websocket-using-the-atmosphere-framework)

Download Atmosphere Whitepaper

[White Paper](https://github.com/Atmosphere/atmosphere/blob/master/docs/atmosphere_whitepaper.pdf)

[Javadoc](http://atmosphere.github.com/atmosphere/apidocs/)

If you are using Maven, just add the following dependency:

     <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module}</artifactId>
         <version>0.8.0-RC4-SNAPSHOT</version>
      </dependency>

Where atmosphere-module can be: jersey, runtime, guice, jquery, cluster or gwt,. Our official release are available from Maven Central. For SNAPSHOT, you'll have to add the Sonatype repo to your settings in order to be able to access the snapshot builds:

[Browse the artifact](https://oss.sonatype.org/content/repositories/releases/org/atmosphere/)

Download samples

[Download the sample, rename the file without the maven version](https://oss.sonatype.org/content/repositories/snapshots/org/atmosphere/samples/)

Atmosphere 0.8.0-RC3 is our official release, and our work in progress version is 0.8, targeted for end of November 2011

If you are interested, subscribe to our mailing list (http://groups.google.com/group/atmosphere-framework) for more info!  We are on irc.freenode.net under #atmosphere-comet
