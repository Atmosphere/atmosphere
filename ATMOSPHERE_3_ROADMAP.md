# ROADMAP Atmosphere 3, aka Nitrogen

## Release Date: September 2015

### Transparent HTTP/2 Support
Portable Layer on top of current Servlet/Netty HTTP/2 implementation. Transparent fallback to WebSocket, HTTP/1

### JDK 8
Baseline Java version will be JDK 8

### Servlet 3.1
Baseline Servlet version will be 3.1

### Reactive Manifesto
Add support and comply to [Reactive Manifesto](http://www.reactivemanifesto.org/)

### Support for Atmosphere 2.x, minimal migration path.
* Broadcaster API supported
* Annotations API supported
* AtmosphereInterceptor API supported
* Injection API supported
* OSGI compliant
* Nettosphere API supported
* All atmosphere-extensions supported
* Servlet Based Framework like Jersey, PrimeFaces, Vaadin, etc should still be supported.

### Core development
1. Atmosphere Runtime: New AtmosphereEmbedded API [Issue]()
2. Atmosphere Runtime: Refactor AtmosphereFramework.java, remove all get/set [Issue]()
3. Atmosphere Runtime: Trim AtmosphereRequest and AtmosphereResponse, exposes native Request/Response object [Issue]()
4. Atmosphere Runtime: Remove Servlet API dependency [Issue]()
5. Atmosphere Runtime: New JDK Lambda Friendly API, replacing AtmosphereHandler/AtmosphereResourceEvent. Based on AtmosphereResourceEventListener callback [Issue]()
6. Atmosphere Runtime: EventBus for event message based [Issue]()
7. Atmosphere Runtime: Server Side Javascript Support via Nashborn. Allow 100% Javascript Server side support like Node.js [Issue](
8. Atmosphere.js: Improved Atmosphere Protocol using JSON body instead of Query String [Issue]()
9. Atmosphere.js: Support for event based messages like Socket.IO [Issue]()

### Dropped API
* Drop support for atmosphere-jersey API (Jersey 1.x) [Issue]()
* Drop support for Meteor API  [Issue]()
* Drop support for AtmosphereHandler/AtmosphereResourceEvent  [Issue]()
