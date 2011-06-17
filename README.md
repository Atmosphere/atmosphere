Atmosphere is a POJO based framework using Inversion of Control (IoC) to bring push/Comet and Websocket to the masses! Finally a framework which can run on any Java based Web Server, including Tomcat, Jetty, GlassFish, Weblogic, Grizzly, JBossWeb and JBoss, Resin, etc. without having to learn how Comet or WebSocket support has been differently implemented by all those Containers. The Atmosphere Framework has both client (JQuery PlugIn) and server components.

Servlet 3.0 is supported along with framework like Jersey (natively), GWT (natively), Wicket, Guice, Spring etc. and programming language like JRuby, Gr oovy and Scala. We also support massive scalability with our Cluster plugin architecture (JGroups, JMS/ActiveMQ, Redis, XMPP,i etc.)
Get started using the Atmosphere Whitepaper PDF and the simple JQuery Quick Start

Read about why you should use Atmosphere instead of Servlet 3.0 Async API. JavaOne 2010 session is live here

Browse our super easy Rest based PubSub sample and read on what is Atmosphere exactly.

Atmosphere ship with a JQuery Plug In that can be used with any Comet or WebSocket Framework:

    http://is.gd/bJXhH

Download Atmosphere Whitepaper

    https://atmosphere.dev.java.net/atmosphere_whitepaper.pdf

If you are using Maven, just add the following dependency:

    <dependency>
         <groupId>org.atmosphere</groupId>
         <artifactId>atmosphere-{atmosphere-module]</artifactId>
         <version>0.7.2</version>
     </dependency>

Where atmosphere-module can be: jersey, runtime, guice, bayeux, cluster or spade-server. Our official release are available from Maven Central. For SNAPSHOT, you'll have to add the Sonatype repo to your settings in order to be able to access the snapshot builds:

    http://oss.sonatype.org/service/local/repositories/snapshots/content

Atmosphere 0.7.2 is our official release, and our work in progress version is 0.8, targeted for end of August 2011

If you are interested, subscribe to our mailing lists (user@atmosphere.dev.java.net or dev@atmosphere.dev.java.net) for more info!  We are on irc.freenode.net under #atmosphere-comet
