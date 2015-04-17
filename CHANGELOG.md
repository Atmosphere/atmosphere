# Change Log

## [Unreleased](https://github.com/Atmosphere/atmosphere/tree/HEAD)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC6...HEAD)

**Closed issues:**

- \[Tyrus\] Tyrus/GlassFish 4.1 fails to initialize jsr356 [\#1946](https://github.com/Atmosphere/atmosphere/issues/1946)

- JSR356Endpoint discards header values [\#1944](https://github.com/Atmosphere/atmosphere/issues/1944)

- \[Jetty\] Internal Jetty NPE of flushBuffer\(\) [\#1943](https://github.com/Atmosphere/atmosphere/issues/1943)

- Log the exception if DefaultAsyncSupport.newCometSupport fails [\#1942](https://github.com/Atmosphere/atmosphere/issues/1942)

- TimeOut exception long polling [\#1941](https://github.com/Atmosphere/atmosphere/issues/1941)

- \[Legacy\] Allow registration of connections with X-Atmosphere-Transport [\#1939](https://github.com/Atmosphere/atmosphere/issues/1939)

- Firefox fails to reconnect websocket connection after server side timeout [\#1938](https://github.com/Atmosphere/atmosphere/issues/1938)

- Multiple threads call AtmosphereResource.write\(\), websocket stops working [\#1937](https://github.com/Atmosphere/atmosphere/issues/1937)

- OnDisconnectInterceptor transport check failure when using JS client. [\#1934](https://github.com/Atmosphere/atmosphere/issues/1934)

- Glassfish 4 websocket support is broken if using multiple endpoints [\#1933](https://github.com/Atmosphere/atmosphere/issues/1933)

- Possible Thread Race with AsyncToken in DefaultBroadcaster [\#1932](https://github.com/Atmosphere/atmosphere/issues/1932)

- Trailing slashes in JSR 356 path not handled correctly for Jetty 9.2 [\#1910](https://github.com/Atmosphere/atmosphere/issues/1910)

- Client misbehaves when server has TrackMessageSizeInterceptor and trackMessageLength=false [\#1848](https://github.com/Atmosphere/atmosphere/issues/1848)

**Merged pull requests:**

- Fix twitter link [\#1936](https://github.com/Atmosphere/atmosphere/pull/1936) ([petejohanson](https://github.com/petejohanson))

- Fix some merge issues w/ state recovery interceptor. [\#1935](https://github.com/Atmosphere/atmosphere/pull/1935) ([petejohanson](https://github.com/petejohanson))

## [atmosphere-project-2.3.0-RC6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC6) (2015-03-31)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC5...atmosphere-project-2.3.0-RC6)

**Closed issues:**

- \[Performance\] \[WebSocket\] Avoid coyping parent attributes [\#1931](https://github.com/Atmosphere/atmosphere/issues/1931)

- ConcurrrentHashMap Leaking. [\#1929](https://github.com/Atmosphere/atmosphere/issues/1929)

- Not able to receive messages on client side when the war is bundeled inside ear [\#1928](https://github.com/Atmosphere/atmosphere/issues/1928)

- osgi [\#1927](https://github.com/Atmosphere/atmosphere/issues/1927)

- Prevent Duplicate AtmosphereInterceptor to be installed [\#1905](https://github.com/Atmosphere/atmosphere/issues/1905)

## [atmosphere-project-2.3.0-RC5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC5) (2015-03-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC4...atmosphere-project-2.3.0-RC5)

**Closed issues:**

- Long-Polling interrupted on initial connect [\#1906](https://github.com/Atmosphere/atmosphere/issues/1906)

- Improve AtmosphereInterceptor Lifecycle by exposing a destroy\(\) method [\#1904](https://github.com/Atmosphere/atmosphere/issues/1904)

- \[Performance\]\[IldeResourceInterceptor\] Excludes POLLING AtmosphereResource [\#1903](https://github.com/Atmosphere/atmosphere/issues/1903)

- \[websocket\] Wrong closing code when jquery.atmosphere.js is used [\#1902](https://github.com/Atmosphere/atmosphere/issues/1902)

- Atmosphere generates nonstandard HTTP headers [\#1900](https://github.com/Atmosphere/atmosphere/issues/1900)

- Can't use Atmosphere Framework with Jersey in Apache Karaf [\#1899](https://github.com/Atmosphere/atmosphere/issues/1899)

- getInitParameter also from ServetContext? [\#1897](https://github.com/Atmosphere/atmosphere/issues/1897)

- Unable to load atmosphere-runtime and atmosphere-annotations into OSGi \(Apache Karaf\) [\#1896](https://github.com/Atmosphere/atmosphere/issues/1896)

- Guice extensions does not work with 2.3.0 [\#1895](https://github.com/Atmosphere/atmosphere/issues/1895)

- long-polling cuases IllegalStateException: "STREAM" [\#1894](https://github.com/Atmosphere/atmosphere/issues/1894)

- NullPointerException in MeteorServlet [\#1893](https://github.com/Atmosphere/atmosphere/issues/1893)

- \[Jersey-Spring\] Client can not reconnect to atmosphere after disconnect [\#1892](https://github.com/Atmosphere/atmosphere/issues/1892)

- No AtmosphereHandler maps request for [\#1891](https://github.com/Atmosphere/atmosphere/issues/1891)

- \[ManagedAtmosphereHandler\] Messages not delivered after broadcast from @Ready method [\#1890](https://github.com/Atmosphere/atmosphere/issues/1890)

- AtmosphereResponse.getHeaderNames\(\) may throw Exception [\#1888](https://github.com/Atmosphere/atmosphere/issues/1888)

- \[ManagedAtmosphereHandler\] Second consequent message not delivered  [\#1887](https://github.com/Atmosphere/atmosphere/issues/1887)

- \[Long-Polling\] Possible Thread Race when message is retrieved from the cache [\#1886](https://github.com/Atmosphere/atmosphere/issues/1886)

- \[Performance\] Avoid looping and removing an AtmosphereResource from all available Broadcasters [\#1885](https://github.com/Atmosphere/atmosphere/issues/1885)

- Bump to Jersey 1.19 [\#1884](https://github.com/Atmosphere/atmosphere/issues/1884)

- Add xxxFactoryListener to complement BroadcastListener [\#1883](https://github.com/Atmosphere/atmosphere/issues/1883)

- net::ERR\_INCOMPLETE\_CHUNKED\_ENCODING in chrome [\#1882](https://github.com/Atmosphere/atmosphere/issues/1882)

- V2.3.0-RC3 ManagedAtmosphereHandler:message discard the second request on the same method [\#1880](https://github.com/Atmosphere/atmosphere/issues/1880)

- Multiple handler's interceptors will be combined and intercepts all the services' message [\#1845](https://github.com/Atmosphere/atmosphere/issues/1845)

- \[SpringBoot\] Try to workaround broken SpringBoot and bad JHipster integration [\#1836](https://github.com/Atmosphere/atmosphere/issues/1836)

- \[Tomcat 7.x only\] Reloading the servlet context \(tomcat 7\) does not close all active web sockets [\#1827](https://github.com/Atmosphere/atmosphere/issues/1827)

- HeartbeatInterceptor executed before IdleResourceInterceptor [\#1760](https://github.com/Atmosphere/atmosphere/issues/1760)

- \[Tomcat\] NIO error when the websocket is closed [\#1646](https://github.com/Atmosphere/atmosphere/issues/1646)

- MeteorServlet using Java config [\#1587](https://github.com/Atmosphere/atmosphere/issues/1587)

- AtmosphereResponse.getWriter\(\).print\(int\) doesn't work with non-servlet frameworks [\#1193](https://github.com/Atmosphere/atmosphere/issues/1193)

**Merged pull requests:**

- Generate standard HTTP header [\#1901](https://github.com/Atmosphere/atmosphere/pull/1901) ([thanhphu](https://github.com/thanhphu))

- getInitParameter also from ServletContext [\#1898](https://github.com/Atmosphere/atmosphere/pull/1898) ([bertung](https://github.com/bertung))

- Arrange the interceptor in each handler instead of the framework list [\#1877](https://github.com/Atmosphere/atmosphere/pull/1877) ([dreamershl](https://github.com/dreamershl))

## [atmosphere-project-2.3.0-RC4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC4) (2015-03-03)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.6...atmosphere-project-2.3.0-RC4)

**Closed issues:**

- ManagedServiceProcessor always load three default interceptors  [\#1857](https://github.com/Atmosphere/atmosphere/issues/1857)

## [atmosphere-project-2.2.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.6) (2015-03-02)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC3...atmosphere-project-2.2.6)

**Closed issues:**

- V2.3.0-RC3 AtmosphereFramework::doCometSupport, java.lang.NullPointerException [\#1881](https://github.com/Atmosphere/atmosphere/issues/1881)

- \[Performance\] Add a Poolable BroadcasterFactory [\#1879](https://github.com/Atmosphere/atmosphere/issues/1879)

- \[Performance\] Reduce contention on DefaultBroadcasterFactory internal store [\#1878](https://github.com/Atmosphere/atmosphere/issues/1878)

- Improve AtmosphereFrameworkInitializer [\#1875](https://github.com/Atmosphere/atmosphere/issues/1875)

- Incorrect use of Arrays.copyOfRange in org.atmosphere.util.IOUtils [\#1873](https://github.com/Atmosphere/atmosphere/issues/1873)

- \[JBossWeb\] Remove JBossWebSocket Container [\#1872](https://github.com/Atmosphere/atmosphere/issues/1872)

- \[Performance\] Share BroadcasterListener's Map, Reduce the number of BroadcasterLifecyclePolicyHandler created. [\#1870](https://github.com/Atmosphere/atmosphere/issues/1870)

- Atmosphere in Dropwizard: Unable to use Dropwizard-Testing [\#1868](https://github.com/Atmosphere/atmosphere/issues/1868)

- Allow discarting message on flush\(\) and flushBuffer\(\) I/O Exception  [\#1867](https://github.com/Atmosphere/atmosphere/issues/1867)

- \[DefaultBroadcaster\] \[Tomcat\] Improve error handling [\#1866](https://github.com/Atmosphere/atmosphere/issues/1866)

- \[Netty\] Possible deadlock  [\#1865](https://github.com/Atmosphere/atmosphere/issues/1865)

- \[Native\] AtmosphereServlet must behave exactly the same way as org.atmosphere.cpr.AtmosphereServlet [\#1864](https://github.com/Atmosphere/atmosphere/issues/1864)

- Is synchronous XHR really needed by Atmosphere? [\#1863](https://github.com/Atmosphere/atmosphere/issues/1863)

- Allow onTransportFailure handler to abort reconnect attempt [\#1862](https://github.com/Atmosphere/atmosphere/issues/1862)

- Network issues should not cause permanent downgrade to long polling [\#1861](https://github.com/Atmosphere/atmosphere/issues/1861)

- Reconnect not reliable with Chrome + Jetty 9.2 [\#1860](https://github.com/Atmosphere/atmosphere/issues/1860)

- AtmosphereRequest is not configured properly when using Weblogic 12c with websockets [\#1854](https://github.com/Atmosphere/atmosphere/issues/1854)

- Client side 'connectTimeout' parameter doesn't work when WebSocket is opened but messages are blocked [\#1844](https://github.com/Atmosphere/atmosphere/issues/1844)

- \[JBossWeb\] Read Operation Block When APR is enabled [\#1843](https://github.com/Atmosphere/atmosphere/issues/1843)

- Atmosphere + Tomcat + Long-Polling: Possible race condition resulting in corrupt server response [\#1791](https://github.com/Atmosphere/atmosphere/issues/1791)

- \[optimization\]\[runtime\] Inline method [\#1595](https://github.com/Atmosphere/atmosphere/issues/1595)

- \[optimization\] \[Jersey\] inlining method  [\#1594](https://github.com/Atmosphere/atmosphere/issues/1594)

**Merged pull requests:**

- \#1857 populate excludedInterceptors in doInitParams [\#1876](https://github.com/Atmosphere/atmosphere/pull/1876) ([allenxiang](https://github.com/allenxiang))

- \#1873 Fix binary message [\#1874](https://github.com/Atmosphere/atmosphere/pull/1874) ([allenxiang](https://github.com/allenxiang))

- \#1857 Filter out disabled interceptors from default interceptor list in AnnotationUtil [\#1858](https://github.com/Atmosphere/atmosphere/pull/1858) ([allenxiang](https://github.com/allenxiang))

- \#1857 make sure system-wide interceptors are configured before auto configuring service. [\#1859](https://github.com/Atmosphere/atmosphere/pull/1859) ([allenxiang](https://github.com/allenxiang))

## [atmosphere-project-2.3.0-RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC3) (2015-02-13)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.5...atmosphere-project-2.3.0-RC3)

**Closed issues:**

- \[WebLogic\] jsr356 support broken [\#1856](https://github.com/Atmosphere/atmosphere/issues/1856)

## [atmosphere-project-2.2.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.5) (2015-02-06)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.10...atmosphere-project-2.2.5)

**Closed issues:**

- unsubscribe for a specific request when multiple subscriptions [\#1855](https://github.com/Atmosphere/atmosphere/issues/1855)

- jquery.atmosphere-min.js contains lot of security bridges. [\#1853](https://github.com/Atmosphere/atmosphere/issues/1853)

- \[Tomcat\] Improve logging for IOException: java.net.SocketException: Broken pipe exceptions [\#1852](https://github.com/Atmosphere/atmosphere/issues/1852)

- DefaultWebSocketProcessor must fail if no AtmosphereHandler or WebSocketHandler installed. [\#1851](https://github.com/Atmosphere/atmosphere/issues/1851)

- AsynchronousProcessor should cancel the processing when the request is closed mid-way [\#1850](https://github.com/Atmosphere/atmosphere/issues/1850)

- NPE When using long-polling with tomcat7  -  reproduce with gwt-20-managed-rpc [\#1849](https://github.com/Atmosphere/atmosphere/issues/1849)

- atmosphere WebSocket not using the right Response? [\#1846](https://github.com/Atmosphere/atmosphere/issues/1846)

- Not closing the WebSocket when closing ServletOutputStream returned by AtmosphereResponse.getOutputStream\(\) [\#1842](https://github.com/Atmosphere/atmosphere/issues/1842)

- AtmosphereResourceImpl doesn't override hashcode\(\) which will fail the hash collection  [\#1841](https://github.com/Atmosphere/atmosphere/issues/1841)

- Logging every message to info in AutoBeanClientSerializer.deserialize [\#1840](https://github.com/Atmosphere/atmosphere/issues/1840)

- Tomcat 8.x + Jersey \( 1.18.1 \) + atmosphere-runtime 2.2.x cannot correctly route WebSocket requests due to an incorrect requestURI implementation in org.atmosphere.container.JSR356Endpoint.onOpen [\#1839](https://github.com/Atmosphere/atmosphere/issues/1839)

- \[jsr356\] Wrong path calculation for servletContext == /\* [\#1838](https://github.com/Atmosphere/atmosphere/issues/1838)

- Cordova/PhoneGap integration [\#1837](https://github.com/Atmosphere/atmosphere/issues/1837)

- \[Meteor\]\[Jersey\]\[JSONP\] doPost is executed before doGet when data is pushed from subSocket.onOpen\(\) [\#1832](https://github.com/Atmosphere/atmosphere/issues/1832)

**Merged pull requests:**

- \#1846 atmosphere's WebSocket not using the right Response? [\#1847](https://github.com/Atmosphere/atmosphere/pull/1847) ([elakito](https://github.com/elakito))

## [atmosphere-project-2.1.10](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.10) (2015-01-14)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC2...atmosphere-project-2.1.10)

**Closed issues:**

- \[Meteor\] SSE doesn't work if TrackMessageSizeInterceptor is used [\#1835](https://github.com/Atmosphere/atmosphere/issues/1835)

- \[Tomcat7only\] Badly implemented WebSocket client makes Tomcat/Atmosphere to create sessions in an endless loop [\#1834](https://github.com/Atmosphere/atmosphere/issues/1834)

- DefaultBroadcaster's messages queue is not processed [\#1822](https://github.com/Atmosphere/atmosphere/issues/1822)

- Documentation: Failed to get Atmosphere 2.3 to run on WebSphere 7 \(Java EE 5/Servlet 2.5\) [\#1751](https://github.com/Atmosphere/atmosphere/issues/1751)

- UUIDBroadcastCache buffer messages on timeout/severed connections [\#1674](https://github.com/Atmosphere/atmosphere/issues/1674)

- \[Clarification\] Action.SKIP\_ATMOSPHEREHANDLER behavior [\#1664](https://github.com/Atmosphere/atmosphere/issues/1664)

- AtmosphereResourceSessionListener [\#1630](https://github.com/Atmosphere/atmosphere/issues/1630)

- \[OSGi\] MeteorServlet.init\(\) - two calls to delegate servlet init\(\) method  [\#1571](https://github.com/Atmosphere/atmosphere/issues/1571)

- Possible ClassCastException when resolving AtmosphereResource [\#1561](https://github.com/Atmosphere/atmosphere/issues/1561)

- long-polling protocol \(at least\) doesn't encode messages as UTF-8 like websocket [\#1424](https://github.com/Atmosphere/atmosphere/issues/1424)

- BroadcasterCache apply to all deployed @Service [\#1299](https://github.com/Atmosphere/atmosphere/issues/1299)

- Add support, in Atmosphere Protocol, messaging when the server is about to close the connection [\#953](https://github.com/Atmosphere/atmosphere/issues/953)

- Make Message UUID generator pluggable [\#626](https://github.com/Atmosphere/atmosphere/issues/626)

## [atmosphere-project-2.3.0-RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC2) (2015-01-07)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.3.0-RC1...atmosphere-project-2.3.0-RC2)

**Closed issues:**

- \[AtmosphereResourceStateRecover\] Must not re-add filtered messages. [\#1833](https://github.com/Atmosphere/atmosphere/issues/1833)

- \[AtmosphereResourceStateRecover\] onSuspend must be called independently of the transport [\#1831](https://github.com/Atmosphere/atmosphere/issues/1831)

- \[Firefox\] \[NettoSphere\] ManagedService Disconnect never called [\#1830](https://github.com/Atmosphere/atmosphere/issues/1830)

- CorsInterceptor: Wrong DROP\_ACCESS\_CONTROL\_ALLOW\_ORIGIN\_HEADER value [\#1829](https://github.com/Atmosphere/atmosphere/issues/1829)

- The AtmosphereResponse only return the header from its own "headers" map [\#1828](https://github.com/Atmosphere/atmosphere/issues/1828)

- Reloading a context throws following exception [\#1826](https://github.com/Atmosphere/atmosphere/issues/1826)

- node-webkit chrome based browser throws exceptions \(internal DoS\) [\#1825](https://github.com/Atmosphere/atmosphere/issues/1825)

- Add support for AtmosphereResourceListener [\#1824](https://github.com/Atmosphere/atmosphere/issues/1824)

- Long Pooling with 2.2.4 version not working [\#1821](https://github.com/Atmosphere/atmosphere/issues/1821)

- Message lost when long polling disconnects \(2.2.4 + JS 2.2.6\) [\#1820](https://github.com/Atmosphere/atmosphere/issues/1820)

- Default Serializer configuration isn't used by Meteor [\#1817](https://github.com/Atmosphere/atmosphere/issues/1817)

- ClassFileIterator can stop AnnotationDetector [\#1816](https://github.com/Atmosphere/atmosphere/issues/1816)

- \[jersey\] JSONP is broken with rest-chat sample [\#1815](https://github.com/Atmosphere/atmosphere/issues/1815)

- v2.2.4: Can't open websocket in Android browser to Jetty 9.2.5.v20141112 [\#1814](https://github.com/Atmosphere/atmosphere/issues/1814)

- \[Native\] META-SERVICE missing [\#1813](https://github.com/Atmosphere/atmosphere/issues/1813)

- Howto: server\(combined with data generator\) --\>\(push new data\)--\> all subscriped clients [\#1812](https://github.com/Atmosphere/atmosphere/issues/1812)

- Upgrading to Atmosphere 2.2.0 makes new Spring ContextRefreshedEvent events [\#1722](https://github.com/Atmosphere/atmosphere/issues/1722)

- RFE: Create EventBus interface [\#1638](https://github.com/Atmosphere/atmosphere/issues/1638)

- AtmosphereResource.isSuspended\(\) doesn't always work as expected [\#1320](https://github.com/Atmosphere/atmosphere/issues/1320)

**Merged pull requests:**

- Fix problem with Chrome 33 and empty headers [\#1823](https://github.com/Atmosphere/atmosphere/pull/1823) ([Artur-](https://github.com/Artur-))

- Added X-Requested-With [\#1819](https://github.com/Atmosphere/atmosphere/pull/1819) ([mejmo](https://github.com/mejmo))

- added check the file exists in ClassFileIterator [\#1818](https://github.com/Atmosphere/atmosphere/pull/1818) ([palkopatel](https://github.com/palkopatel))

## [atmosphere-project-2.3.0-RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.3.0-RC1) (2014-12-09)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.4...atmosphere-project-2.3.0-RC1)

**Closed issues:**

- Document how implement acknowledgement support by creating an AtmosphereInterceptor [\#1034](https://github.com/Atmosphere/atmosphere/issues/1034)

## [atmosphere-project-2.2.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.4) (2014-12-08)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.3...atmosphere-project-2.2.4)

**Closed issues:**

- Reduce AtmosphereRequest's attributes size [\#1810](https://github.com/Atmosphere/atmosphere/issues/1810)

- \[Spring\] Add void annotation processor [\#1809](https://github.com/Atmosphere/atmosphere/issues/1809)

- BroadcastFilterLifecycle.destroy called twice on shutdown [\#1807](https://github.com/Atmosphere/atmosphere/issues/1807)

- \[Tomcat\] Possible NPE on setAttribute [\#1806](https://github.com/Atmosphere/atmosphere/issues/1806)

- \[Tomcat8\] Workaround Tomcat Internal NullPointerException [\#1804](https://github.com/Atmosphere/atmosphere/issues/1804)

- \[Tomcat8\] Prevent exception from breaking the request flow [\#1803](https://github.com/Atmosphere/atmosphere/issues/1803)

- \[JSR356/AsyncContext\] org.atmosphere.useNative must never be honored [\#1801](https://github.com/Atmosphere/atmosphere/issues/1801)

- \[memory\] Remove BroadcastFuture from the Request's attributes [\#1800](https://github.com/Atmosphere/atmosphere/issues/1800)

- Improve annotation scanning. [\#1798](https://github.com/Atmosphere/atmosphere/issues/1798)

- A lot of logs "Invalid request state. Websocket protocol not supported " [\#1797](https://github.com/Atmosphere/atmosphere/issues/1797)

- Add support for AtmosphereFramework lifecycle  [\#1796](https://github.com/Atmosphere/atmosphere/issues/1796)

- the param of custom package doesn't work, custom package found same class anotation twice [\#1795](https://github.com/Atmosphere/atmosphere/issues/1795)

- BroadcasterListener\#onComplete invoked multiple times [\#1793](https://github.com/Atmosphere/atmosphere/issues/1793)

- DefaultBroadcaster broadcast future is not released when PerRequestBroadcastFilter returns ABORT [\#1792](https://github.com/Atmosphere/atmosphere/issues/1792)

- NPE in TrackMessageSizeInterceptor [\#1789](https://github.com/Atmosphere/atmosphere/issues/1789)

- \[jsr356\] A lot of attempts to write to the closed socket [\#1788](https://github.com/Atmosphere/atmosphere/issues/1788)

- Deadlock in JSR356 Tomcat implementation [\#1786](https://github.com/Atmosphere/atmosphere/issues/1786)

- Allow DI to instanciate xxxFactory  [\#1785](https://github.com/Atmosphere/atmosphere/issues/1785)

- Running Atmosphere within Resin 4 [\#1783](https://github.com/Atmosphere/atmosphere/issues/1783)

- \[Tomcat CometEvent/HttpEvent\] Possible deadlock on disconnect [\#1782](https://github.com/Atmosphere/atmosphere/issues/1782)

- Reconnect behaviour improvement [\#1779](https://github.com/Atmosphere/atmosphere/issues/1779)

- AnnotationDetector ignores subpackage on wildfly8 [\#1778](https://github.com/Atmosphere/atmosphere/issues/1778)

- AnnotationDetector should log with slf4j [\#1777](https://github.com/Atmosphere/atmosphere/issues/1777)

- AtmosphereServlet initialized twice [\#1775](https://github.com/Atmosphere/atmosphere/issues/1775)

- Atmosphere-Shared-AsyncOp Thread is blocked on JSR356WebSocket:65 [\#1773](https://github.com/Atmosphere/atmosphere/issues/1773)

- Use constant expression for ApplicationConfig's constants [\#1772](https://github.com/Atmosphere/atmosphere/issues/1772)

- \[jsr356\] AtmosphereResource\#transport representing WebSocket message returns undefined when enableProtocol is false [\#1771](https://github.com/Atmosphere/atmosphere/issues/1771)

- WebSocket session closed on tomcat 8 [\#1770](https://github.com/Atmosphere/atmosphere/issues/1770)

- atmosphere Broadcast  [\#1769](https://github.com/Atmosphere/atmosphere/issues/1769)

- Allow Configuring Keep-Alive Thread value [\#1768](https://github.com/Atmosphere/atmosphere/issues/1768)

- Add a Universe for hacking factory [\#1767](https://github.com/Atmosphere/atmosphere/issues/1767)

- NullPointerException in AtmosphereResourceEventImpl.isResuming [\#1766](https://github.com/Atmosphere/atmosphere/issues/1766)

- \[Tomcat\] onTimeout\(\) failed for listener of type \[org.apache.catalina.core.AsyncListenerWrapper\] java.lang.IllegalStateException: Calling \[asyncComplete\(\)\] is not valid for a request with Async state \[MUST\_COMPLETE\] [\#1765](https://github.com/Atmosphere/atmosphere/issues/1765)

- Atmosphere broadcaster read only? prevent push [\#1764](https://github.com/Atmosphere/atmosphere/issues/1764)

- Improve Custom Injection API [\#1763](https://github.com/Atmosphere/atmosphere/issues/1763)

- Completely remove BroadcasterFactory.getDefault [\#1762](https://github.com/Atmosphere/atmosphere/issues/1762)

- Allow an application to reset the thread pools [\#1759](https://github.com/Atmosphere/atmosphere/issues/1759)

- Prevent Tomcat/jsr356 from crashing on Error [\#1758](https://github.com/Atmosphere/atmosphere/issues/1758)

- Allow configuring the number of Scheduler Threads [\#1757](https://github.com/Atmosphere/atmosphere/issues/1757)

- Prevent Client to reconnect and leaks thread when Atmosphere is undeployed [\#1756](https://github.com/Atmosphere/atmosphere/issues/1756)

- Problem with Chrome DNS cache [\#1755](https://github.com/Atmosphere/atmosphere/issues/1755)

- Atmosphere on Heroku with Play Framework 2, Scaling issue [\#1753](https://github.com/Atmosphere/atmosphere/issues/1753)

- JSR356: Ability to retrieve remote client intels [\#1752](https://github.com/Atmosphere/atmosphere/issues/1752)

- Use long  polling broadcast the first message the AtmosphereResource will remove from broadcast. [\#1749](https://github.com/Atmosphere/atmosphere/issues/1749)

- Primepush and JavaEE websockets [\#1747](https://github.com/Atmosphere/atmosphere/issues/1747)

- Missing Origin header causes Atmosphere to add requestURI twice to the generated Origin header.  [\#1745](https://github.com/Atmosphere/atmosphere/issues/1745)

- AtmosphereResourceImpl implements equals but not hashCode [\#1743](https://github.com/Atmosphere/atmosphere/issues/1743)

- Memory leak, thread named \[Atmosphere-Shared-AsyncOp-\*\*\] [\#1740](https://github.com/Atmosphere/atmosphere/issues/1740)

- Can't get AtmosphereFramework from servlet context on JBoss EAP 6.1 [\#1716](https://github.com/Atmosphere/atmosphere/issues/1716)

- Interceptor ordering behavior leads to missconfiguration and uncontrollable behavior [\#1698](https://github.com/Atmosphere/atmosphere/issues/1698)

- \[Jersey\] NPE with websocket and sendError [\#1547](https://github.com/Atmosphere/atmosphere/issues/1547)

**Merged pull requests:**

- Log stacktraces with DEBUG level [\#1808](https://github.com/Atmosphere/atmosphere/pull/1808) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Don't warn if META-INF/services/ is not found [\#1805](https://github.com/Atmosphere/atmosphere/pull/1805) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Log message about invalid request with DEBUG level [\#1802](https://github.com/Atmosphere/atmosphere/pull/1802) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Future should throw TimeoutException if get\(\) timeout reached [\#1787](https://github.com/Atmosphere/atmosphere/pull/1787) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Semaphore [\#1784](https://github.com/Atmosphere/atmosphere/pull/1784) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Log unexpected errors with WARN level instead of TRACE or DEBUG [\#1780](https://github.com/Atmosphere/atmosphere/pull/1780) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Use separate property for JSR356 send timeout [\#1776](https://github.com/Atmosphere/atmosphere/pull/1776) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- JSR356WebSocket: always release semaphore  [\#1774](https://github.com/Atmosphere/atmosphere/pull/1774) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Fix crashes in init for Equinox 3.8.1. The URLConnection returned by  [\#1761](https://github.com/Atmosphere/atmosphere/pull/1761) ([unverbraucht](https://github.com/unverbraucht))

- Adding a filter to AtmosphereInterceptorWriter at the specified position [\#1750](https://github.com/Atmosphere/atmosphere/pull/1750) ([elakito](https://github.com/elakito))

- Decrease per request memory pressure substantially by lazy voidReader creation. [\#1748](https://github.com/Atmosphere/atmosphere/pull/1748) ([thabach](https://github.com/thabach))

- Fix for regression caused by 337226348c18a5e5359b3e305affb8d51aa687de [\#1746](https://github.com/Atmosphere/atmosphere/pull/1746) ([elusive-code](https://github.com/elusive-code))

- fix for equals/hashCode in AtmosphereResourceImpl [\#1744](https://github.com/Atmosphere/atmosphere/pull/1744) ([elusive-code](https://github.com/elusive-code))

- Try JSR356\_MAPPING\_PATH param before guessing servlet path [\#1742](https://github.com/Atmosphere/atmosphere/pull/1742) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- Support versions of type 2.2.3.qualifier [\#1741](https://github.com/Atmosphere/atmosphere/pull/1741) ([Artur-](https://github.com/Artur-))

- Atmosphere 2.1.x [\#1811](https://github.com/Atmosphere/atmosphere/pull/1811) ([evernym](https://github.com/evernym))

- Ensure JSR356 session is open before writing to it [\#1790](https://github.com/Atmosphere/atmosphere/pull/1790) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- \#1773: JSR356WebSocket - acquire semaphore with timeout [\#1781](https://github.com/Atmosphere/atmosphere/pull/1781) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

- \#1752 JSR356: Ability to retrieve remote client intels [\#1754](https://github.com/Atmosphere/atmosphere/pull/1754) ([reda-alaoui](https://github.com/reda-alaoui))

## [atmosphere-project-2.2.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.3) (2014-10-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.10...atmosphere-project-2.2.3)

**Closed issues:**

- Sometimes use broadcast  will push two times.\(safari and IE9\) [\#1739](https://github.com/Atmosphere/atmosphere/issues/1739)

- FD for the Atmosphere socket not released after closing the socket [\#1738](https://github.com/Atmosphere/atmosphere/issues/1738)

- \[JBossWeb\] Prevent java.lang.IllegalStateException: Request object no longer valid when completing the request [\#1737](https://github.com/Atmosphere/atmosphere/issues/1737)

- \[Heartbeat\]\[enableXDR\]\[IE\] Allow changing padding bytes, in 2.3, change whitespace to 'X' [\#1736](https://github.com/Atmosphere/atmosphere/issues/1736)

- \[WebSocket\] onOpen called with wrong UUID on reconnect [\#1735](https://github.com/Atmosphere/atmosphere/issues/1735)

- Deadlock issue related to DefaultBroadcaster and OnDisconnectInterceptor [\#1734](https://github.com/Atmosphere/atmosphere/issues/1734)

- \[JBossWeb\] Messages are lost in long-polling when Heartbeat occurs at the same time a message arrives [\#1733](https://github.com/Atmosphere/atmosphere/issues/1733)

- \[WebSphere\] NPE on HttpServletRequest.getAttribute [\#1732](https://github.com/Atmosphere/atmosphere/issues/1732)

- \[ManagedService\] Allow Broadcaster\#broadcast to execute inside a mapped method [\#1620](https://github.com/Atmosphere/atmosphere/issues/1620)

- receipt asked by client and server aknowledgement [\#1576](https://github.com/Atmosphere/atmosphere/issues/1576)

**Merged pull requests:**

- Just added the 2.0.9 version release notes to the index. [\#1731](https://github.com/Atmosphere/atmosphere/pull/1731) ([jdmartinho](https://github.com/jdmartinho))

## [atmosphere-project-2.0.10](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.10) (2014-10-02)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.2...atmosphere-project-2.0.10)

**Closed issues:**

- Problem with WeblogicWebSocketHandler [\#1729](https://github.com/Atmosphere/atmosphere/issues/1729)

- Add support for @Inject annotation of Atmosphere Factory [\#1560](https://github.com/Atmosphere/atmosphere/issues/1560)

## [atmosphere-project-2.2.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.2) (2014-09-29)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.9...atmosphere-project-2.2.2)

**Closed issues:**

- \[Wildfly 8.0.0.Final\] Blocking request failed HttpServerExchange{ GET /primepush/message/qla/robert}: java.lang.NullPointerException [\#1728](https://github.com/Atmosphere/atmosphere/issues/1728)

- BroadcastMessage not properly implemented, Javadoc wrong. [\#1726](https://github.com/Atmosphere/atmosphere/issues/1726)

- ClassCastException during AtmosphereFramework initialization when using Spring's ExecutorServiceAdapter [\#1725](https://github.com/Atmosphere/atmosphere/issues/1725)

**Merged pull requests:**

- Update to fix issue when using ExecutorService instances inside of the B... [\#1727](https://github.com/Atmosphere/atmosphere/pull/1727) ([mnolanjr98](https://github.com/mnolanjr98))

## [atmosphere-project-2.1.9](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.9) (2014-09-23)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.1...atmosphere-project-2.1.9)

**Closed issues:**

- Receiving error: Backlog is present in JBoss 7.2.0 [\#1724](https://github.com/Atmosphere/atmosphere/issues/1724)

- Upgrading to Atmosphere 2.2.0 creates empty @ManagedService messages [\#1723](https://github.com/Atmosphere/atmosphere/issues/1723)

- Communication error with Push and long enough string of multibyte characters [\#1720](https://github.com/Atmosphere/atmosphere/issues/1720)

- Anti-virus software blocking atmosphere server-push messages [\#1719](https://github.com/Atmosphere/atmosphere/issues/1719)

- \[AtmosphereResourceLifecycleInterceptor\] Performance improvements, Prevent POLLING to be added [\#1718](https://github.com/Atmosphere/atmosphere/issues/1718)

- Possible NPE in Utils.websocketResource [\#1717](https://github.com/Atmosphere/atmosphere/issues/1717)

- atmosphere-jersey depends on an ancient version of Jersey [\#1715](https://github.com/Atmosphere/atmosphere/issues/1715)

- Not easy to customize DefaultWebSocketProcessor [\#1714](https://github.com/Atmosphere/atmosphere/issues/1714)

- JSR356: Wrong pathInfo calculation in JSR356Endpoint when deploying on context root [\#1712](https://github.com/Atmosphere/atmosphere/issues/1712)

- NPE in PushEndpointHandlerProxy.invokeOpenOrClose\(\) [\#1711](https://github.com/Atmosphere/atmosphere/issues/1711)

- AtmosphereServletProcessor not always initialized [\#1710](https://github.com/Atmosphere/atmosphere/issues/1710)

- 2.1.2+ can't pass the Atmosphere Vibe platform test. [\#1623](https://github.com/Atmosphere/atmosphere/issues/1623)

**Merged pull requests:**

- Fix the size of ByteBuffer [\#1721](https://github.com/Atmosphere/atmosphere/pull/1721) ([cwayfinder](https://github.com/cwayfinder))

- \#1712 JSR356: Wrong pathInfo calculation in JSR356Endpoint when deploying on context root [\#1713](https://github.com/Atmosphere/atmosphere/pull/1713) ([reda-alaoui](https://github.com/reda-alaoui))

## [atmosphere-project-2.2.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.1) (2014-09-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.19...atmosphere-project-2.2.1)

## [atmosphere-project-1.0.19](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.19) (2014-09-09)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.8...atmosphere-project-1.0.19)

**Closed issues:**

- \[annotation\] ManagedService shound not override default interceptor list [\#1709](https://github.com/Atmosphere/atmosphere/issues/1709)

- MetaBroadcaster adding '/' not working to Jersey/WebSocket [\#1707](https://github.com/Atmosphere/atmosphere/issues/1707)

- Prevent AtmosphereInterceptor from being added more than once [\#1705](https://github.com/Atmosphere/atmosphere/issues/1705)

- Use the long-polling that can't continuous broadcast meaage. [\#1704](https://github.com/Atmosphere/atmosphere/issues/1704)

- maxReconnectOnClose error with heartbeat and long polling on Atmosphere 2.2.0 [\#1703](https://github.com/Atmosphere/atmosphere/issues/1703)

- AnnotationDetector doesn't understand all VFS protocols [\#1701](https://github.com/Atmosphere/atmosphere/issues/1701)

- \[OnDisconnectInterceptor\] Allow invoking listener's onDisconnect from the closing message [\#1700](https://github.com/Atmosphere/atmosphere/issues/1700)

- onClientTimeout [\#1699](https://github.com/Atmosphere/atmosphere/issues/1699)

- Create a switch to disable AtmosphereInitializer framework creation [\#1695](https://github.com/Atmosphere/atmosphere/issues/1695)

- Atmosphere Chat application not working in IE9 -- Long Polling [\#1694](https://github.com/Atmosphere/atmosphere/issues/1694)

- RFE: Configure default Serializer [\#1692](https://github.com/Atmosphere/atmosphere/issues/1692)

- SessionSupport error. Make sure you define org.atmosphere.cpr.SessionSupport as a listener in web.xml instead [\#1691](https://github.com/Atmosphere/atmosphere/issues/1691)

- Jetty9WebSocketHandler\#onWebSocketError causing NPE [\#1690](https://github.com/Atmosphere/atmosphere/issues/1690)

- ManagedAtmosphereHandler methods for resume and timeout [\#1682](https://github.com/Atmosphere/atmosphere/issues/1682)

- Broadcaster Lifecycle logic must be moved inside an BroadcasterListener [\#1667](https://github.com/Atmosphere/atmosphere/issues/1667)

- Embedded Tomcat 8 does not find matching path for ManagedService [\#1663](https://github.com/Atmosphere/atmosphere/issues/1663)

- Build fails using JDK 8 [\#1652](https://github.com/Atmosphere/atmosphere/issues/1652)

- @ManagedService with only @Ready method [\#1648](https://github.com/Atmosphere/atmosphere/issues/1648)

- spring security session invalidation in 2.2.0-RC2 [\#1634](https://github.com/Atmosphere/atmosphere/issues/1634)

- PrimePush \(Primefaces 5\) with TomEE 1.6 Error: can't find annotations [\#1624](https://github.com/Atmosphere/atmosphere/issues/1624)

- Document Firefox/Reload Phantom WebSocket connection [\#1606](https://github.com/Atmosphere/atmosphere/issues/1606)

- Problem setting an interceptor with priority FIRST\_BEFORE\_DEFAULT [\#1603](https://github.com/Atmosphere/atmosphere/issues/1603)

- Jersey2-Chat sample with Embedded Jetty results in 404 [\#1586](https://github.com/Atmosphere/atmosphere/issues/1586)

- Totally remove X-Cache-Date feature [\#1584](https://github.com/Atmosphere/atmosphere/issues/1584)

- Broadcasting List<Object\> does not send the list [\#1555](https://github.com/Atmosphere/atmosphere/issues/1555)

- \[OSGI\] Annotation Scanning Broken [\#1539](https://github.com/Atmosphere/atmosphere/issues/1539)

- TrackMessageLength does not actually work for long messages and websocket [\#1398](https://github.com/Atmosphere/atmosphere/issues/1398)

- DefaultAsyncSupportResolver.resolve disregards the useServlet30Async parameter [\#1386](https://github.com/Atmosphere/atmosphere/issues/1386)

- Add support for Google App Engine [\#1371](https://github.com/Atmosphere/atmosphere/issues/1371)

- onDisconnect not called with long-polling a if a message is send during the onClose [\#1295](https://github.com/Atmosphere/atmosphere/issues/1295)

**Merged pull requests:**

- AtmosphereFilter - not use .toString but a Serializer [\#1708](https://github.com/Atmosphere/atmosphere/pull/1708) ([ricardojlrufino](https://github.com/ricardojlrufino))

- Handle different VFS protocols in AnnotationDetector \(\#1701\) [\#1702](https://github.com/Atmosphere/atmosphere/pull/1702) ([jdahlstrom](https://github.com/jdahlstrom))

- compile fix, enhancement \#1695 and fix for \#1652 [\#1697](https://github.com/Atmosphere/atmosphere/pull/1697) ([bzim](https://github.com/bzim))

- fix for Issue \#1695 and \#1652 [\#1696](https://github.com/Atmosphere/atmosphere/pull/1696) ([bzim](https://github.com/bzim))

## [atmosphere-project-2.1.8](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.8) (2014-08-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.0...atmosphere-project-2.1.8)

**Closed issues:**

- Atmosphere does not work well with log4j2 in Wildfly [\#1688](https://github.com/Atmosphere/atmosphere/issues/1688)

- Websockets gives 501 error in 'some' networks in NGINX but works via Tomcat [\#1687](https://github.com/Atmosphere/atmosphere/issues/1687)

- Broadcaster Concatenating two messages and sending in single response   [\#1686](https://github.com/Atmosphere/atmosphere/issues/1686)

- Atmosphere - Multiple Close requests within the same second [\#1685](https://github.com/Atmosphere/atmosphere/issues/1685)

- HeartbeatInterceptor should use out of scope Request/Response [\#1683](https://github.com/Atmosphere/atmosphere/issues/1683)

- Backward compatibility after \#1666 changes [\#1681](https://github.com/Atmosphere/atmosphere/issues/1681)

- DefaultBroadcaster doesn't cleanup cache on broadcaster destroy. [\#1673](https://github.com/Atmosphere/atmosphere/issues/1673)

**Merged pull requests:**

- Patch for https://github.com/Atmosphere/atmosphere/issues/1682 [\#1693](https://github.com/Atmosphere/atmosphere/pull/1693) ([agherardi](https://github.com/agherardi))

- Remove slf4j usage in ServletContainerInitializer; This avoids classload... [\#1689](https://github.com/Atmosphere/atmosphere/pull/1689) ([praveen12bnitt](https://github.com/praveen12bnitt))

- Reverting changes from fix \#1670 [\#1684](https://github.com/Atmosphere/atmosphere/pull/1684) ([mvsmasiero](https://github.com/mvsmasiero))

- remove unused atmosphere-version property [\#1680](https://github.com/Atmosphere/atmosphere/pull/1680) ([elakito](https://github.com/elakito))

- remove unused atmosphere-version property [\#1679](https://github.com/Atmosphere/atmosphere/pull/1679) ([elakito](https://github.com/elakito))

- remove unused atmosphere-version property [\#1678](https://github.com/Atmosphere/atmosphere/pull/1678) ([elakito](https://github.com/elakito))

- Implementation of "Async Support" for WebSocket transport and Long-Polling fallback to use on JBoss EAP 6.2 with JBoss Native Connector [\#1677](https://github.com/Atmosphere/atmosphere/pull/1677) ([mvsmasiero](https://github.com/mvsmasiero))

- Update from original [\#1676](https://github.com/Atmosphere/atmosphere/pull/1676) ([mvsmasiero](https://github.com/mvsmasiero))

- Update from original [\#1675](https://github.com/Atmosphere/atmosphere/pull/1675) ([mvsmasiero](https://github.com/mvsmasiero))

## [atmosphere-project-2.2.0](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.0) (2014-07-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.9...atmosphere-project-2.2.0)

**Closed issues:**

- AtmosphereRequest wrapper loses custom HTTP attributes created before the upgrade [\#1672](https://github.com/Atmosphere/atmosphere/issues/1672)

- Long-polling doesn't work on JBoss EAP 6.1 with APR \(native support\) [\#1670](https://github.com/Atmosphere/atmosphere/issues/1670)

- Force Resume on undetected disconnect for AtmosphereResource caused by broken clients [\#1669](https://github.com/Atmosphere/atmosphere/issues/1669)

- Javascript \(2.2.2\) close\(\) submission with different session ID [\#1665](https://github.com/Atmosphere/atmosphere/issues/1665)

- \[curl issue\] UUIDBroadcastCache and timeout/severed connections with Jersey [\#1655](https://github.com/Atmosphere/atmosphere/issues/1655)

**Merged pull requests:**

- HttpSession support warning message [\#1671](https://github.com/Atmosphere/atmosphere/pull/1671) ([ceefour](https://github.com/ceefour))

## [atmosphere-project-2.0.9](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.9) (2014-07-16)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.7...atmosphere-project-2.0.9)

**Closed issues:**

- \[heartbeat\] Allow configuring if long-polling resume or not [\#1668](https://github.com/Atmosphere/atmosphere/issues/1668)

- @DeliverTo annotation for methods annotated with @Ready and @Message [\#1666](https://github.com/Atmosphere/atmosphere/issues/1666)

- Using the long-polling can't broadcast more messsage [\#1662](https://github.com/Atmosphere/atmosphere/issues/1662)

- Not possible to unset a BroadcasterCacheClass with empty string [\#1661](https://github.com/Atmosphere/atmosphere/issues/1661)

## [atmosphere-project-2.1.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.7) (2014-07-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.4.1...atmosphere-project-2.1.7)

**Closed issues:**

- Atmosphere 2.1.6 failed to use Tomcat7 APR support for Comet, logs confusing message [\#1659](https://github.com/Atmosphere/atmosphere/issues/1659)

- Disconnect Handler is called twice with JSR 356 in Tomcat 7 [\#1658](https://github.com/Atmosphere/atmosphere/issues/1658)

- GWT Atmosphere number of Threads [\#1657](https://github.com/Atmosphere/atmosphere/issues/1657)

- Atmosphere Broadcasters' Version [\#1656](https://github.com/Atmosphere/atmosphere/issues/1656)

- Infinite session timeout on Tomcat crashes AtmosphereResourceLifecycleInterceptor [\#1654](https://github.com/Atmosphere/atmosphere/issues/1654)

- Refresh the page request. OnOPen didn't happen when set the request uuid not equal zero. [\#1653](https://github.com/Atmosphere/atmosphere/issues/1653)

- Failed using comet support with runtime-native [\#1651](https://github.com/Atmosphere/atmosphere/issues/1651)

- Broader character support in author field. [\#1650](https://github.com/Atmosphere/atmosphere/issues/1650)

- Tomcat 8: X-Atmosphere-tracking-id stays equal 0   [\#1649](https://github.com/Atmosphere/atmosphere/issues/1649)

**Merged pull requests:**

- AtmosphereRequest.toString\(\) was broken and always returned resource\(\).uuid\(\). [\#1660](https://github.com/Atmosphere/atmosphere/pull/1660) ([CodingFabian](https://github.com/CodingFabian))

## [atmosphere-project-2.1.4.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.4.1) (2014-06-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.0-RC3...atmosphere-project-2.1.4.1)

## [atmosphere-project-2.2.0-RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.0-RC3) (2014-06-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.6...atmosphere-project-2.2.0-RC3)

**Closed issues:**

- SSE and CORS interceptor  [\#1647](https://github.com/Atmosphere/atmosphere/issues/1647)

- \[Tomcat\] Tomcat call onClose several time when it fail to detect the connection is down [\#1645](https://github.com/Atmosphere/atmosphere/issues/1645)

- \[Tomcat8\] Jersey2 sample broken [\#1644](https://github.com/Atmosphere/atmosphere/issues/1644)

- \[Tomcat\] Possible NPE on close [\#1643](https://github.com/Atmosphere/atmosphere/issues/1643)

- \[Jetty\] X-Atmosphere-WebSocket-Proxy=true breaks Jetty 9.2.x [\#1642](https://github.com/Atmosphere/atmosphere/issues/1642)

- Error in BroadcasterConfig\#getExecutorServer\(\) javadoc [\#1640](https://github.com/Atmosphere/atmosphere/issues/1640)

- IE 9/8 does not fall back to long-polling when X-Atmosphere-WebSocket-Proxy is added [\#1631](https://github.com/Atmosphere/atmosphere/issues/1631)

**Merged pull requests:**

- BroadcasterConfig javadoc fix - it should reference to ExecutorsFactory ... [\#1641](https://github.com/Atmosphere/atmosphere/pull/1641) ([dmitry-treskunov](https://github.com/dmitry-treskunov))

## [atmosphere-project-2.1.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.6) (2014-06-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.0-RC2...atmosphere-project-2.1.6)

**Closed issues:**

- \[Heartbeat\] write operation must be synchronized [\#1639](https://github.com/Atmosphere/atmosphere/issues/1639)

- \[Tomcat7\] request.isAsyncEnabled\(\) broken [\#1637](https://github.com/Atmosphere/atmosphere/issues/1637)

- \[WebSocket\]\[Jetty\] onDisconnect isn't invoked on event listener when a connection is closed when hixie-76/draft-00 is used [\#1636](https://github.com/Atmosphere/atmosphere/issues/1636)

- AtmosphereService wildfly + restEasy getting 400 [\#1635](https://github.com/Atmosphere/atmosphere/issues/1635)

- Never trust underlying websocket implementation when closing [\#1633](https://github.com/Atmosphere/atmosphere/issues/1633)

- Maven Parent [\#1632](https://github.com/Atmosphere/atmosphere/issues/1632)

- SessionSupport error. Make sure you define org.atmosphere.cpr.SessionSupport as a listener in web.xml instead [\#1629](https://github.com/Atmosphere/atmosphere/issues/1629)

- \[websocket\] When broadcasting binary data it throws ClassCastException [\#1628](https://github.com/Atmosphere/atmosphere/issues/1628)

- GWT polling transport not supported [\#1627](https://github.com/Atmosphere/atmosphere/issues/1627)

- onReady doesn't get called anymore when SuspendTrackerInterceptor is installed. [\#1626](https://github.com/Atmosphere/atmosphere/issues/1626)

- SPRING\_INJECTOR in 2.1.5 [\#1625](https://github.com/Atmosphere/atmosphere/issues/1625)

## [atmosphere-project-2.2.0-RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.0-RC2) (2014-06-06)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.5...atmosphere-project-2.2.0-RC2)

**Closed issues:**

- \[EQUINOX\] Throw NPE when calling ServletContext/Registration at startup [\#1622](https://github.com/Atmosphere/atmosphere/issues/1622)

- \[Jetty9.2\] http://dev.vaadin.com/ticket/13877 [\#1621](https://github.com/Atmosphere/atmosphere/issues/1621)

- \[SuspendTrackerInterceptor\] Regression for long-polling [\#1619](https://github.com/Atmosphere/atmosphere/issues/1619)

- Memory leak: 2.2.0-RC1 + MeteorServlet + Jetty [\#1618](https://github.com/Atmosphere/atmosphere/issues/1618)

- \[Undertow\] Undertow consider Headers are case sensitive causing NPE [\#1617](https://github.com/Atmosphere/atmosphere/issues/1617)

- Jetty 9 Websocket support [\#1616](https://github.com/Atmosphere/atmosphere/issues/1616)

- \[BroadcasterLIfeCyclePolicy\] Timeout fires after twice the time [\#1615](https://github.com/Atmosphere/atmosphere/issues/1615)

## [atmosphere-project-2.1.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.5) (2014-05-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.4...atmosphere-project-2.1.5)

**Closed issues:**

- \[Firefox\] Wrong closing logic in DefaultWebSocketProcessor [\#1614](https://github.com/Atmosphere/atmosphere/issues/1614)

- \[jsr356\] Workaround https://java.net/jira/browse/WEBSOCKET\_SPEC-228 [\#1612](https://github.com/Atmosphere/atmosphere/issues/1612)

- \[Glassfish\] Workaround Issue GRIZZLY-1676 [\#1611](https://github.com/Atmosphere/atmosphere/issues/1611)

- \[WebSocketHandler\] Interceptor not called for websocket messages [\#1609](https://github.com/Atmosphere/atmosphere/issues/1609)

- \[SuspendTrackerInterceptor\] Possible leak is AtmosphereResource.resume\(\) is called [\#1608](https://github.com/Atmosphere/atmosphere/issues/1608)

- Uncaught message length [\#1605](https://github.com/Atmosphere/atmosphere/issues/1605)

- \[annotation\] Scan all classes when getRealPath return null [\#1604](https://github.com/Atmosphere/atmosphere/issues/1604)

- Atmosphere Servlet not starting in Weblogic 10.3.0 [\#1602](https://github.com/Atmosphere/atmosphere/issues/1602)

- \[Webstart\] Annotation Scanning Broken [\#1599](https://github.com/Atmosphere/atmosphere/issues/1599)

- \[Regression\] Interceptor fail to install when detected from external dependecies [\#1598](https://github.com/Atmosphere/atmosphere/issues/1598)

- \[JBoss EAP 6\] JSR 356 not properly implemented, need a workaround [\#1597](https://github.com/Atmosphere/atmosphere/issues/1597)

- \[Regression\] AbstractBroadcasterProxy throw a Runtime exception [\#1596](https://github.com/Atmosphere/atmosphere/issues/1596)

- NullPointerException with JMSBroadcaster [\#1592](https://github.com/Atmosphere/atmosphere/issues/1592)

- Exception when war is not unpacked [\#1591](https://github.com/Atmosphere/atmosphere/issues/1591)

- @Disconnect is not called when WebSocket client terminates [\#1590](https://github.com/Atmosphere/atmosphere/issues/1590)

- \[websocket\] Tomcat goes in limbo when an evil load balancer close the connection [\#1589](https://github.com/Atmosphere/atmosphere/issues/1589)

- \[Servlet30\] Force BlockingIO if async-support missing in web.xml [\#1588](https://github.com/Atmosphere/atmosphere/issues/1588)

- Client tries to reconnect even on clean close by server [\#1585](https://github.com/Atmosphere/atmosphere/issues/1585)

- Enhance HeartbeatInterceptor [\#1581](https://github.com/Atmosphere/atmosphere/issues/1581)

- WebSocket disabled if webapp deployed as default-web-module in GlassFish [\#1580](https://github.com/Atmosphere/atmosphere/issues/1580)

- Add @Heartbeat Annotation for Tracking Heartbeat Events [\#1549](https://github.com/Atmosphere/atmosphere/issues/1549)

- Possible static issue with BroadcasterFactory [\#1543](https://github.com/Atmosphere/atmosphere/issues/1543)

- Deprecate XFactory.getDefault\(\) methods and move to AtmosphereConfig [\#1451](https://github.com/Atmosphere/atmosphere/issues/1451)

**Merged pull requests:**

- New Fix por issue \#1612 [\#1613](https://github.com/Atmosphere/atmosphere/pull/1613) ([mvsmasiero](https://github.com/mvsmasiero))

- \#1549 [\#1607](https://github.com/Atmosphere/atmosphere/pull/1607) ([gdrouet](https://github.com/gdrouet))

- \#1599 \[Webstart\] Annotation Scanning Broken [\#1600](https://github.com/Atmosphere/atmosphere/pull/1600) ([reda-alaoui](https://github.com/reda-alaoui))

- avoid unnecessary wasteful allocation of 8K per each AtmosphereRequest [\#1593](https://github.com/Atmosphere/atmosphere/pull/1593) ([zach-m](https://github.com/zach-m))

- \#1580 - default-web-module support [\#1583](https://github.com/Atmosphere/atmosphere/pull/1583) ([codeturner](https://github.com/codeturner))

- \#1599 \[Webstart\] Annotation Scanning Broken [\#1601](https://github.com/Atmosphere/atmosphere/pull/1601) ([reda-alaoui](https://github.com/reda-alaoui))

## [atmosphere-project-2.1.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.4) (2014-05-05)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.2.0-RC1...atmosphere-project-2.1.4)

**Closed issues:**

- Allow UUIDBroadcasterCache configuration of clientIdleTime and invalidateCacheInterval [\#1582](https://github.com/Atmosphere/atmosphere/issues/1582)

- noOpsResource field in DefaultBroadcaster [\#1579](https://github.com/Atmosphere/atmosphere/issues/1579)

- \[jersey\] runtime error about org.atmosphere.util.AbstractBroadcasterProxy's constructor method [\#1578](https://github.com/Atmosphere/atmosphere/issues/1578)

- Finer priority management for interceptors [\#1574](https://github.com/Atmosphere/atmosphere/issues/1574)

## [atmosphere-project-2.2.0-RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.2.0-RC1) (2014-04-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.3...atmosphere-project-2.2.0-RC1)

**Closed issues:**

- Add META/services/org.atmosphere.cpr.AtmosphereFramework support [\#1573](https://github.com/Atmosphere/atmosphere/issues/1573)

- \[websocket \] SuspendTrackerInterceptor leaks UUID [\#1572](https://github.com/Atmosphere/atmosphere/issues/1572)

- Websockets not working on ubuntu production server [\#1570](https://github.com/Atmosphere/atmosphere/issues/1570)

- Failed to start on weblogci 12.1.2.0.0 [\#1569](https://github.com/Atmosphere/atmosphere/issues/1569)

- Add AtmosphereFramework.reload\(\) support [\#1568](https://github.com/Atmosphere/atmosphere/issues/1568)

- Messages ids not deleted from UUIDBroadcasterCache [\#1566](https://github.com/Atmosphere/atmosphere/issues/1566)

- Atmosphere 2.0.4 in embedded Jetty 9.1.0 [\#1565](https://github.com/Atmosphere/atmosphere/issues/1565)

- TrackMessageSizeInterceptor called multiple time [\#1564](https://github.com/Atmosphere/atmosphere/issues/1564)

- IndexOutOfBounds - buffer truncated in Websocket.write\(...\) [\#1563](https://github.com/Atmosphere/atmosphere/issues/1563)

- NPE in AsynchronousProcessor\#getContainerName when atmosphere is configured using Embedding AtmosphereFramework [\#1562](https://github.com/Atmosphere/atmosphere/issues/1562)

- CDI not working with TomEE 1.6.0 when deployed inside EAR [\#1559](https://github.com/Atmosphere/atmosphere/issues/1559)

- Messages not sent with current long poll request for concurrent message send and receive requests [\#1558](https://github.com/Atmosphere/atmosphere/issues/1558)

- Firefox "no element found" error on unsubscribe [\#1557](https://github.com/Atmosphere/atmosphere/issues/1557)

- HeartbeatInterceptor only fires after the first message [\#1553](https://github.com/Atmosphere/atmosphere/issues/1553)

**Merged pull requests:**

- Remove message ID from clientQueue when clearing the message from the cache [\#1567](https://github.com/Atmosphere/atmosphere/pull/1567) ([ghostdogpr](https://github.com/ghostdogpr))

## [atmosphere-project-2.1.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.3) (2014-04-14)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.2...atmosphere-project-2.1.3)

**Closed issues:**

- getInputStream/Reader confused when an AtmosphereResquest.Body is used [\#1556](https://github.com/Atmosphere/atmosphere/issues/1556)

- wildfly 8: ERROR o.a.container.JSR356Endpoint -  java.nio.channels.ClosedChannelException: null  [\#1550](https://github.com/Atmosphere/atmosphere/issues/1550)

- \[websocket\] \[jsr356\] Issue with /\* mapping [\#1548](https://github.com/Atmosphere/atmosphere/issues/1548)

- Issue in enabling CORS in atmosphere client [\#1546](https://github.com/Atmosphere/atmosphere/issues/1546)

- wasync reconnection not works [\#1545](https://github.com/Atmosphere/atmosphere/issues/1545)

- wrong version range of catalina in atmosphere-runtime bundle [\#1541](https://github.com/Atmosphere/atmosphere/issues/1541)

- @Disconnect not called for @ManagedService [\#1535](https://github.com/Atmosphere/atmosphere/issues/1535)

- Allow Detection of Empty Broadcaster in BroadcastFilter [\#914](https://github.com/Atmosphere/atmosphere/issues/914)

- Atmosphere Jersey api and WebRTC not work well together [\#905](https://github.com/Atmosphere/atmosphere/issues/905)

**Merged pull requests:**

- Fixed bug with resizing buffers [\#1554](https://github.com/Atmosphere/atmosphere/pull/1554) ([claymore-minds](https://github.com/claymore-minds))

- Add support for @PathVariable annotation in ManagedService [\#1552](https://github.com/Atmosphere/atmosphere/pull/1552) ([m4ci3k2](https://github.com/m4ci3k2))

- \#1541: wrong version range of catalina in atmosphere-runtime bundle [\#1551](https://github.com/Atmosphere/atmosphere/pull/1551) ([elakito](https://github.com/elakito))

- \#1541: wrong version range of catalina in atmosphere-runtime bundle [\#1544](https://github.com/Atmosphere/atmosphere/pull/1544) ([elakito](https://github.com/elakito))

## [atmosphere-project-2.1.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.2) (2014-04-01)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.8...atmosphere-project-2.1.2)

**Closed issues:**

- \[websocket\] AtmosphereResourceEventListener\#onDisconnect must be invoked after physical connection is closed [\#1542](https://github.com/Atmosphere/atmosphere/issues/1542)

- \[wasync\]"application/octet-stream" should enable binary support automatically. [\#1540](https://github.com/Atmosphere/atmosphere/issues/1540)

- \[wifi\] Allow delaying the processing of AtmosphereResource's disconnection [\#1538](https://github.com/Atmosphere/atmosphere/issues/1538)

- @Message-annotated method not called for @ManagedService if there is only one annotated method [\#1537](https://github.com/Atmosphere/atmosphere/issues/1537)

- \[Firefox\] Unpredictable behavior of OnDisconnectInterceptor [\#1536](https://github.com/Atmosphere/atmosphere/issues/1536)

- \[websocket\] Improve Close Code 1001 support [\#1534](https://github.com/Atmosphere/atmosphere/issues/1534)

- Atmosphere resource is null [\#1533](https://github.com/Atmosphere/atmosphere/issues/1533)

- \[BroadcasterFactory\] Clashes when more than one AtmosphereServlet is defined [\#1531](https://github.com/Atmosphere/atmosphere/issues/1531)

- ServletContainerInitializer issue with multiple Servlet [\#1530](https://github.com/Atmosphere/atmosphere/issues/1530)

- When sending binary data throws java.lang.IllegalArgumentException: argument type mismatch [\#1529](https://github.com/Atmosphere/atmosphere/issues/1529)

- @ManagedService and BroadcastFilter issue [\#1528](https://github.com/Atmosphere/atmosphere/issues/1528)

- Invalid broadcaster in ManagedService [\#1522](https://github.com/Atmosphere/atmosphere/issues/1522)

- No API for retrieve broadcaster in PerRequest filter [\#1435](https://github.com/Atmosphere/atmosphere/issues/1435)

- Stomp protocol support [\#1339](https://github.com/Atmosphere/atmosphere/issues/1339)

## [atmosphere-project-2.0.8](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.8) (2014-03-21)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.1...atmosphere-project-2.0.8)

**Closed issues:**

- \[jsr356\] Wrong pathInfo computed. [\#1527](https://github.com/Atmosphere/atmosphere/issues/1527)

- \[performance\] AtmosphereResourceFactory.find is slow [\#1526](https://github.com/Atmosphere/atmosphere/issues/1526)

- TrackMessageSizeInterceptor must execute before everything else [\#1525](https://github.com/Atmosphere/atmosphere/issues/1525)

- \[Embedded\] AtmosphereFramework.interceptor\(..\) called before started must be reconfigured [\#1523](https://github.com/Atmosphere/atmosphere/issues/1523)

- Broadcaster can't send message when go to another page [\#1521](https://github.com/Atmosphere/atmosphere/issues/1521)

- \[BroadcasterListener\] Make sure already created Broadcaster are passed to onPostConstruct [\#1520](https://github.com/Atmosphere/atmosphere/issues/1520)

- \[BroadcasterCache\] Allow external components to add messages to cache [\#1519](https://github.com/Atmosphere/atmosphere/issues/1519)

- \[BroadcasterCache\] Add BroadcasterCacheListener support [\#1518](https://github.com/Atmosphere/atmosphere/issues/1518)

- WebsocketHandlerAdapter onclose method [\#1517](https://github.com/Atmosphere/atmosphere/issues/1517)

- rboristerie [\#1516](https://github.com/Atmosphere/atmosphere/issues/1516)

- Broadcast still send message after onDisconnect - browser close [\#1513](https://github.com/Atmosphere/atmosphere/issues/1513)

- Bug on org.atmosphere.util.IOUtils class at String guestServletPath\(AtmosphereFramework, String\) [\#1512](https://github.com/Atmosphere/atmosphere/issues/1512)

- AtmosphereSession class broken [\#1511](https://github.com/Atmosphere/atmosphere/issues/1511)

- \[BroadcasterCache\] Expose API to register AtmosphereResource for cache [\#1510](https://github.com/Atmosphere/atmosphere/issues/1510)

- \[BroadcastListener\] Allow listener to manipulate messages, resources etc. [\#1509](https://github.com/Atmosphere/atmosphere/issues/1509)

- Servlet with /\* mapping and websockets throws exception in Tomcat 8 [\#1508](https://github.com/Atmosphere/atmosphere/issues/1508)

- \[websocket\] Possible NPE when client close the connection during the handshake [\#1507](https://github.com/Atmosphere/atmosphere/issues/1507)

- \[JavascriptProtocol\] Improve robusness when a connection is closed before execution [\#1506](https://github.com/Atmosphere/atmosphere/issues/1506)

- \[jersey\] Missing Injection Point [\#1505](https://github.com/Atmosphere/atmosphere/issues/1505)

- Connection not correctly closed ? [\#1504](https://github.com/Atmosphere/atmosphere/issues/1504)

- Long-polling takes long time to connect on BlackBerry 7 browser [\#1498](https://github.com/Atmosphere/atmosphere/issues/1498)

**Merged pull requests:**

- Issue \#1511 - [\#1515](https://github.com/Atmosphere/atmosphere/pull/1515) ([bprato](https://github.com/bprato))

- Method correction: String guestServletPath\(AtmosphereFramework, String\) [\#1514](https://github.com/Atmosphere/atmosphere/pull/1514) ([rdigiorgio](https://github.com/rdigiorgio))

## [atmosphere-project-2.1.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.1) (2014-03-12)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.0...atmosphere-project-2.1.1)

**Closed issues:**

- \[websphere 8.5.5\] NPE when removing an attribute [\#1503](https://github.com/Atmosphere/atmosphere/issues/1503)

- Allow configuring SessionTimeoutRestorer default HttpSession.setMaxInactiveInterval [\#1502](https://github.com/Atmosphere/atmosphere/issues/1502)

- \[jsonp\] TrackMessageSizeInterceptor must compute the original String, not the escaped. [\#1501](https://github.com/Atmosphere/atmosphere/issues/1501)

- Atmosphere chat example with XDR enabled fails on Android [\#1499](https://github.com/Atmosphere/atmosphere/issues/1499)

- Websockets does not work on BlackBerry 7 browser [\#1497](https://github.com/Atmosphere/atmosphere/issues/1497)

- Bottleneck? Finding a AtmosphereResource. [\#1496](https://github.com/Atmosphere/atmosphere/issues/1496)

- \[WebSocketHandlerService\] BroadcasterCache defined not installed [\#1495](https://github.com/Atmosphere/atmosphere/issues/1495)

- \[DefaultWebSocketProtocol\] Must suspend the AtmosphereResource before calling onOpen [\#1494](https://github.com/Atmosphere/atmosphere/issues/1494)

- \[Gwt1\] \[1.0.18\] Response over websocket is broken [\#1493](https://github.com/Atmosphere/atmosphere/issues/1493)

- endless loop while calling DefaultBroadcaster.removeAtmosphereResource\(\) [\#1492](https://github.com/Atmosphere/atmosphere/issues/1492)

- \[Jetty 9.1\] java.lang.IllegalStateException: Blocking message pending 10000 for BLOCKING [\#1491](https://github.com/Atmosphere/atmosphere/issues/1491)

- Broadcaster created by ManagedServiceProcessor [\#1489](https://github.com/Atmosphere/atmosphere/issues/1489)

- NPE if AtmosphereResource is closed inside @Ready annotated method [\#1488](https://github.com/Atmosphere/atmosphere/issues/1488)

- Encoders are populated only with the encoders specified with @Message  [\#1487](https://github.com/Atmosphere/atmosphere/issues/1487)

- \[Proxy\] Force Fallback when a Proxy confuse a websocket connection [\#1486](https://github.com/Atmosphere/atmosphere/issues/1486)

- Specify the JDK and other dependencies in the Document or Wiki page [\#1485](https://github.com/Atmosphere/atmosphere/issues/1485)

- \[Annotation\] AtmosphereInterceprorProcessor must add the interceptor after the framework started [\#1484](https://github.com/Atmosphere/atmosphere/issues/1484)

- \[ManagedService Annotation\] Simultaneous write to AtmosphereResponse [\#1483](https://github.com/Atmosphere/atmosphere/issues/1483)

- Intermittent NullPointerException with long polling in Atmosphere 2.1 [\#1482](https://github.com/Atmosphere/atmosphere/issues/1482)

- Hardcoded GWT SerializationPolicy [\#1481](https://github.com/Atmosphere/atmosphere/issues/1481)

- \[EndpointMapper\] Possible mapping exception [\#1480](https://github.com/Atmosphere/atmosphere/issues/1480)

- Sending a pipe \('|'\) with streaming causes a javascript exception [\#1479](https://github.com/Atmosphere/atmosphere/issues/1479)

- AtmosphereRequest.getLocale throws exception in Tomcat 8 [\#1478](https://github.com/Atmosphere/atmosphere/issues/1478)

- Ready.DELIVER\_TO.RESOURCE with SSE/WS/HttpStreaming [\#1477](https://github.com/Atmosphere/atmosphere/issues/1477)

- \[jsr356\] Two Atmosphere Servlet's may clash on jsr356 [\#1476](https://github.com/Atmosphere/atmosphere/issues/1476)

- Atmosphere triggers fatal error killing the JVM [\#1475](https://github.com/Atmosphere/atmosphere/issues/1475)

- Adding the SessionSupport listener fails on Jetty 8 [\#1474](https://github.com/Atmosphere/atmosphere/issues/1474)

- \[JBoss 7.1.1\] java.lang.IllegalArgumentException: URI scheme is not "file" [\#1473](https://github.com/Atmosphere/atmosphere/issues/1473)

- Logs are spammed if async support is not enabled [\#1472](https://github.com/Atmosphere/atmosphere/issues/1472)

- Events sequence for AtmosphereResourceEventListener and Transport.Websocket [\#1471](https://github.com/Atmosphere/atmosphere/issues/1471)

- Forcing jsr356 WebSocket support doesn't work., [\#1470](https://github.com/Atmosphere/atmosphere/issues/1470)

- Crash with java.lang.IllegalStateException: The Atmosphere Framework is not installed properly and unexpected result may occurs [\#1469](https://github.com/Atmosphere/atmosphere/issues/1469)

- NullReferenceException thrown when calling BroadcasterFactory.lookup [\#1468](https://github.com/Atmosphere/atmosphere/issues/1468)

- NullReferenceException thrown when calling BroadcasterFactory.lookup [\#1467](https://github.com/Atmosphere/atmosphere/issues/1467)

- AtmosphereResource must be removed from Broadcaster if the suspend operation fail. [\#1465](https://github.com/Atmosphere/atmosphere/issues/1465)

- Different Handler Registration in Atmosphere 2.1.0 compared to 2.1.0-RC2 [\#1464](https://github.com/Atmosphere/atmosphere/issues/1464)

- 2.1.0-RC2, AtmosphereFramework can't init the "webSocketProtocol" based on the configured WEBSOCKET\_PROTOCOL in web.xml [\#1463](https://github.com/Atmosphere/atmosphere/issues/1463)

**Merged pull requests:**

- better UUIDBroadcasterCache logging [\#1500](https://github.com/Atmosphere/atmosphere/pull/1500) ([backuitist](https://github.com/backuitist))

- Passing HttpServletRequest to the WebSocketProcessor\#handshake in Glassfish 3.x [\#1490](https://github.com/Atmosphere/atmosphere/pull/1490) ([aleksandarn](https://github.com/aleksandarn))

- Atmosphere 2.1.x [\#1466](https://github.com/Atmosphere/atmosphere/pull/1466) ([jfarcand](https://github.com/jfarcand))

## [atmosphere-project-2.1.0](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.0) (2014-02-04)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.7...atmosphere-project-2.1.0)

**Closed issues:**

- Better explanation of chat-multiroom example [\#1462](https://github.com/Atmosphere/atmosphere/issues/1462)

- HeartbeatInterceptor causes issues for undefined transport. [\#1461](https://github.com/Atmosphere/atmosphere/issues/1461)

- Websocket failed. Downgrading to Comet and resending using https/wss [\#1460](https://github.com/Atmosphere/atmosphere/issues/1460)

- \[Evil Proxy\] Messages may not not broadcasted when long-polling reconnects and proxy close the connection [\#1459](https://github.com/Atmosphere/atmosphere/issues/1459)

- \[Meteor\] AtmosphereResource.close\(\) must remove the associated Meteor [\#1457](https://github.com/Atmosphere/atmosphere/issues/1457)

- \[Meteor\] Reduce memory footprint by using the AtmosphereResource.uuid\(\) [\#1456](https://github.com/Atmosphere/atmosphere/issues/1456)

- IE 9 and Safari caused on Tomcat \(NIO\) to fallback to BIO [\#1455](https://github.com/Atmosphere/atmosphere/issues/1455)

- AtmosphereResource.close\(\) must properly resume the underlying connection [\#1453](https://github.com/Atmosphere/atmosphere/issues/1453)

- \[performance\] Reduce bytes copy by allowing direct access to Request's body [\#1452](https://github.com/Atmosphere/atmosphere/issues/1452)

- IE8 fails to access ajaxRequest.status [\#1450](https://github.com/Atmosphere/atmosphere/issues/1450)

- AtmosphereResource Session [\#1426](https://github.com/Atmosphere/atmosphere/issues/1426)

**Merged pull requests:**

- Session timeout removal allow/disallow parameter [\#1454](https://github.com/Atmosphere/atmosphere/pull/1454) ([antoine-galataud](https://github.com/antoine-galataud))

- Atmosphere 2.0.x [\#1458](https://github.com/Atmosphere/atmosphere/pull/1458) ([inah11](https://github.com/inah11))

## [atmosphere-project-2.0.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.7) (2014-01-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.6...atmosphere-project-2.0.7)

**Closed issues:**

- Duplicate Broadcasters Created [\#1449](https://github.com/Atmosphere/atmosphere/issues/1449)

- ERROR org.atmosphere.cache.SessionBroadcasterCache [\#1448](https://github.com/Atmosphere/atmosphere/issues/1448)

- atmosphere\_socketio\_chat Chinese messy code [\#1447](https://github.com/Atmosphere/atmosphere/issues/1447)

- \[jsr356\] Message can be lost if websocket closed [\#1446](https://github.com/Atmosphere/atmosphere/issues/1446)

- \[annotation\] BroadcasterCache defined too late [\#1445](https://github.com/Atmosphere/atmosphere/issues/1445)

- o.a.c.CometSupport.maxInactiveActivity not working OK in 2.0.6 with long-polling [\#1444](https://github.com/Atmosphere/atmosphere/issues/1444)

- Encoding of broadcasted messages does not work reliably with multiple @Message annotations [\#1443](https://github.com/Atmosphere/atmosphere/issues/1443)

- Add IdleResourceInterceptor by default to uniformize annotation usage [\#1442](https://github.com/Atmosphere/atmosphere/issues/1442)

- o.a.c.CometSupport.maxInactiveActivity -1 vs 5 minutes [\#1441](https://github.com/Atmosphere/atmosphere/issues/1441)

- AtmosphereInterceptorWriter.interceptor\(\) - reverse order of invocation [\#1439](https://github.com/Atmosphere/atmosphere/issues/1439)

- Incorrect javadoc for DISABLE\_ATMOSPHEREINTERCEPTORS [\#1438](https://github.com/Atmosphere/atmosphere/issues/1438)

- Javadoc for InvokationOrder  [\#1437](https://github.com/Atmosphere/atmosphere/issues/1437)

- AtmosphereInterceptorWriter.interceptor\(\) - will be invoked in the order it was added [\#1436](https://github.com/Atmosphere/atmosphere/issues/1436)

- \[regression\] Heartbeat interceptors creates too many inner classes [\#1434](https://github.com/Atmosphere/atmosphere/issues/1434)

- List of broadcasters per resource [\#1430](https://github.com/Atmosphere/atmosphere/issues/1430)

- .lookup\(\) synchronization on strings \(or .equal objects\) [\#1413](https://github.com/Atmosphere/atmosphere/issues/1413)

- DefaultWebSocketProcessor does not notify listeners of the HANDSHAKE [\#1390](https://github.com/Atmosphere/atmosphere/issues/1390)

**Merged pull requests:**

- AtmosphereResourceSession [\#1440](https://github.com/Atmosphere/atmosphere/pull/1440) ([uklance](https://github.com/uklance))

- - Fixed test to accurately test the problem and patched the code to make... [\#1432](https://github.com/Atmosphere/atmosphere/pull/1432) ([jcdang](https://github.com/jcdang))

## [atmosphere-project-2.0.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.6) (2014-01-14)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.0-RC2...atmosphere-project-2.0.6)

**Closed issues:**

- \[jetty 9.1.1\] ava.lang.NoClassDefFoundError: org/eclipse/jetty/websocket/api/io/WebSocketBlockingConnection [\#1433](https://github.com/Atmosphere/atmosphere/issues/1433)

- \[UUIDBroadcasterCache\] Possible minor leak when the cache is invalidated [\#1431](https://github.com/Atmosphere/atmosphere/issues/1431)

- Prevent Broadcaster from throwing java.lang.IllegalStateException: Request object no longer valid. This object has been cancelled [\#1429](https://github.com/Atmosphere/atmosphere/issues/1429)

- QueryString with value of '=' not parsed properly [\#1428](https://github.com/Atmosphere/atmosphere/issues/1428)

- PrivateMessag can‘t be sent in sample chat-multiroom with RedisBroadcaster [\#1427](https://github.com/Atmosphere/atmosphere/issues/1427)

- Allow disabling a subset of the default Interceptor instead of all [\#1425](https://github.com/Atmosphere/atmosphere/issues/1425)

- Build status on cloudbees [\#1422](https://github.com/Atmosphere/atmosphere/issues/1422)

- ERROR o.a.cpr.AsynchronousProcessor - No AtmosphereHandler found [\#1421](https://github.com/Atmosphere/atmosphere/issues/1421)

- Possible Concurrent Issue with AtmosphereStateRecovery Interceptor [\#1419](https://github.com/Atmosphere/atmosphere/issues/1419)

- Prevent AtmosphereInterceptor from crashing each others [\#1418](https://github.com/Atmosphere/atmosphere/issues/1418)

- Truth table for atmosphere jars [\#1417](https://github.com/Atmosphere/atmosphere/issues/1417)

- Spray support [\#1416](https://github.com/Atmosphere/atmosphere/issues/1416)

- License: Apache/CDDL/GPL2 with exception [\#1415](https://github.com/Atmosphere/atmosphere/issues/1415)

- ajaxRequest.onreadystatechange function called twice for each message response  [\#1414](https://github.com/Atmosphere/atmosphere/issues/1414)

- Broadcast to BroadcasterCache AtmosphereResource [\#1412](https://github.com/Atmosphere/atmosphere/issues/1412)

- possible finatra support? [\#1411](https://github.com/Atmosphere/atmosphere/issues/1411)

- Concrete classes are passed to AtmosphereObjectFactory [\#1410](https://github.com/Atmosphere/atmosphere/issues/1410)

- Documentation does not match ApplicationConfig.OBJECT\_FACTORY [\#1409](https://github.com/Atmosphere/atmosphere/issues/1409)

- Incomplete Jetty version for OSGi [\#1408](https://github.com/Atmosphere/atmosphere/issues/1408)

- \[regression\] in 2.1.0-RC2, wicket-atmosphere is unable to load the page [\#1407](https://github.com/Atmosphere/atmosphere/issues/1407)

**Merged pull requests:**

- Update README.md [\#1423](https://github.com/Atmosphere/atmosphere/pull/1423) ([adelarsq](https://github.com/adelarsq))

- Fit import-package version for jetty dependencies [\#1420](https://github.com/Atmosphere/atmosphere/pull/1420) ([efernandezleon](https://github.com/efernandezleon))

## [atmosphere-project-2.1.0-RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.0-RC2) (2013-12-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.5...atmosphere-project-2.1.0-RC2)

**Closed issues:**

- \[jsr356\] \[Tomcat\] Session is null [\#1406](https://github.com/Atmosphere/atmosphere/issues/1406)

- \[2.0.x\] Backport JSONP encoding fixe [\#1405](https://github.com/Atmosphere/atmosphere/issues/1405)

## [atmosphere-project-2.0.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.5) (2013-12-18)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.4...atmosphere-project-2.0.5)

**Closed issues:**

- \[regression\] ManagedService.broadcasterCache broken [\#1404](https://github.com/Atmosphere/atmosphere/issues/1404)

- WebSocket draft-00 / hixie-76 doesn't work when request headers are attached in the query string [\#1403](https://github.com/Atmosphere/atmosphere/issues/1403)

- \[websocket\] Make sure parent's UUID resource is always removed from Broadcaster [\#1402](https://github.com/Atmosphere/atmosphere/issues/1402)

- Remove CloseDetector, Add IdleResourceInterceptor for WIFI outage [\#1401](https://github.com/Atmosphere/atmosphere/issues/1401)

- Add support for websocketd [\#1400](https://github.com/Atmosphere/atmosphere/issues/1400)

- Wrong Broadcaster Detected when using @\_\_\_Service and classpath [\#1399](https://github.com/Atmosphere/atmosphere/issues/1399)

- IllegalStateException [\#1397](https://github.com/Atmosphere/atmosphere/issues/1397)

- Allow configuring which WebSocket versions Jetty 7/8 should accept [\#1396](https://github.com/Atmosphere/atmosphere/issues/1396)

- Broadcastable.broadcast\(\) does not have timeout [\#1395](https://github.com/Atmosphere/atmosphere/issues/1395)

- \[websocket\] AtmosphereHandler not invoked when WebSocket is closed and enableProtocol == false [\#1394](https://github.com/Atmosphere/atmosphere/issues/1394)

- Promote HeartbeatInterceptor to core, installed by default [\#1393](https://github.com/Atmosphere/atmosphere/issues/1393)

- Reduce Padding Size for Up To Date Browser [\#1392](https://github.com/Atmosphere/atmosphere/issues/1392)

- Browser Safari : server resumed the connection or down   [\#1391](https://github.com/Atmosphere/atmosphere/issues/1391)

- How can i transfer binary content \(files\) through atmosphere layer, and make them available for download on the client side? [\#1389](https://github.com/Atmosphere/atmosphere/issues/1389)

- \[annotation\] applicationConfig may not work for some features [\#1388](https://github.com/Atmosphere/atmosphere/issues/1388)

- Add a ServletContextFactory [\#1387](https://github.com/Atmosphere/atmosphere/issues/1387)

- Memory leak \(and perhaps infinite loop\) when used in Spring controller with patterned url [\#1385](https://github.com/Atmosphere/atmosphere/issues/1385)

- set import range for javax.servlet to include 3.0 [\#1383](https://github.com/Atmosphere/atmosphere/issues/1383)

- ConcurrentModificationException [\#1382](https://github.com/Atmosphere/atmosphere/issues/1382)

- Links to GWT samples are broken [\#1381](https://github.com/Atmosphere/atmosphere/issues/1381)

- \[@\_\_\_Service\] Allow Encoder/Decoder of type InputStream and Reader [\#1380](https://github.com/Atmosphere/atmosphere/issues/1380)

- Add support for AtmosphereSession [\#1359](https://github.com/Atmosphere/atmosphere/issues/1359)

- Setting priority AFTER\_DEFAULT for interceptor doesn't work [\#1311](https://github.com/Atmosphere/atmosphere/issues/1311)

- TrackMessageSizeInterceptor's AsyncIOInterceptorAdapter should be executed before eg. JSONPAtmosphereInterceptor [\#1310](https://github.com/Atmosphere/atmosphere/issues/1310)

- MetaBroadcaster lost messages when no match [\#1143](https://github.com/Atmosphere/atmosphere/issues/1143)

- Implement SockJs support  [\#630](https://github.com/Atmosphere/atmosphere/issues/630)

**Merged pull requests:**

- set import range for javax.servlet to include 3.0 [\#1384](https://github.com/Atmosphere/atmosphere/pull/1384) ([elakito](https://github.com/elakito))

## [atmosphere-project-2.0.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.4) (2013-11-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.0-RC1...atmosphere-project-2.0.4)

**Closed issues:**

- \[Servlet30\] AsyncContext.complete\(\) must be called in timeout [\#1379](https://github.com/Atmosphere/atmosphere/issues/1379)

- \[regression\] Broadcaster.broadcastOnResume\(OBJ\)  [\#1378](https://github.com/Atmosphere/atmosphere/issues/1378)

- \[AtmosphereJS\]\[2.0.3\] enableProtocol + trackMessageSize not reconnecting [\#1377](https://github.com/Atmosphere/atmosphere/issues/1377)

- \[Jboss EAP 5.1.0\] ClassCastException on deployment [\#1376](https://github.com/Atmosphere/atmosphere/issues/1376)

- BroadcasterListener on refresh page [\#1372](https://github.com/Atmosphere/atmosphere/issues/1372)

- AtmosphereResource not suspend, cannot resume it, on server shutdown [\#1344](https://github.com/Atmosphere/atmosphere/issues/1344)

- 2.0.0-SNAPSHOT should be newer than 2.0.0.RCx [\#1297](https://github.com/Atmosphere/atmosphere/issues/1297)

- Atmosphere cannot scan annotations on JBoss 5.1 [\#1204](https://github.com/Atmosphere/atmosphere/issues/1204)

- Add MemoryAware Interceptor for checking load on the server [\#1200](https://github.com/Atmosphere/atmosphere/issues/1200)

- \[doc\] Add architecture diagrams [\#422](https://github.com/Atmosphere/atmosphere/issues/422)

## [atmosphere-project-2.1.0-RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.0-RC1) (2013-11-18)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.1.0-beta1...atmosphere-project-2.1.0-RC1)

**Closed issues:**

- onReopen not called when Atmosphere protocol is enabled [\#1375](https://github.com/Atmosphere/atmosphere/issues/1375)

- UUIDBroadcasterCache leak [\#1374](https://github.com/Atmosphere/atmosphere/issues/1374)

- \[HeartbeatInterceptor\] Add a callback for detecting dead client [\#1373](https://github.com/Atmosphere/atmosphere/issues/1373)

- \[websocket\] OnDisconnectInterceptor broken [\#1370](https://github.com/Atmosphere/atmosphere/issues/1370)

- \[OnMessage\] OnMessageAtmosphereHandler lack of client/application close support [\#1369](https://github.com/Atmosphere/atmosphere/issues/1369)

- \[websocket\] Survice Proxy that Strip Out the Upgrade Header [\#1368](https://github.com/Atmosphere/atmosphere/issues/1368)

- Meteor+Struts2: X-Atmosphere-tracking-id - BigDecimal cannot be cast to java.lang.String [\#1367](https://github.com/Atmosphere/atmosphere/issues/1367)

- \[websocket\] \[annotation\] WebSocketHandler's AtmosphereResource default Broadcaster class may not be honored [\#1366](https://github.com/Atmosphere/atmosphere/issues/1366)

- \[annotation\] Honor specified Broadcaster  [\#1365](https://github.com/Atmosphere/atmosphere/issues/1365)

- \[websocket\] Avoid calling webSocket.close when a remote close happens [\#1364](https://github.com/Atmosphere/atmosphere/issues/1364)

- \[Tomcat\] \[websocket\] Tomcat send wrong opcode when an exception happens inside onOpen [\#1363](https://github.com/Atmosphere/atmosphere/issues/1363)

- JS: Unsubscribe should clear uuid [\#1362](https://github.com/Atmosphere/atmosphere/issues/1362)

- Classes without a package won't deploy [\#1360](https://github.com/Atmosphere/atmosphere/issues/1360)

- Messages greater than 8KB are chunked [\#1358](https://github.com/Atmosphere/atmosphere/issues/1358)

- Make calls to analytics\(\) optional [\#1356](https://github.com/Atmosphere/atmosphere/issues/1356)

- \[Undertow issue\] Websocket Connection Fails On Wildfly 8.0.0.Beta1 [\#1348](https://github.com/Atmosphere/atmosphere/issues/1348)

- Client-driven option to enable websocket binary write [\#1338](https://github.com/Atmosphere/atmosphere/issues/1338)

- Add init/configure method to BroadcasterConfig [\#1294](https://github.com/Atmosphere/atmosphere/issues/1294)

**Merged pull requests:**

- Fixes a potential NullPointerException when session is destroyed [\#1361](https://github.com/Atmosphere/atmosphere/pull/1361) ([klieber](https://github.com/klieber))

- CORSInterceptor [\#1357](https://github.com/Atmosphere/atmosphere/pull/1357) ([adam-rightshift](https://github.com/adam-rightshift))

## [atmosphere-project-2.1.0-beta1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.1.0-beta1) (2013-10-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.3...atmosphere-project-2.1.0-beta1)

**Closed issues:**

- \[WebLogic\] Add support for native WebSocket [\#1355](https://github.com/Atmosphere/atmosphere/issues/1355)

- \[weblogic\] Annotation Scanning not working [\#1354](https://github.com/Atmosphere/atmosphere/issues/1354)

- JQuery trackMessageLength vs. trackMessageSize [\#1353](https://github.com/Atmosphere/atmosphere/issues/1353)

- \[Primefaces 4.0 Push + Atmosphere 2.0.3  + IE8\] java.lang.IllegalStateException: Not supported. [\#1352](https://github.com/Atmosphere/atmosphere/issues/1352)

- Long-polling connection issue with Firefox and IE [\#1351](https://github.com/Atmosphere/atmosphere/issues/1351)

- When an AtmosphereHandler is mapped, skip WebSocketHandler Mapping  [\#1350](https://github.com/Atmosphere/atmosphere/issues/1350)

- \[Atmosphere/TomEE\] WARNING: Unable to detect annotations. Application may fail to deploy. [\#1349](https://github.com/Atmosphere/atmosphere/issues/1349)

- \[Performance\] Preset Number of Threads instead of unlimited [\#1347](https://github.com/Atmosphere/atmosphere/issues/1347)

- Unable to configure more than one Broadcaster Class with annotation [\#1346](https://github.com/Atmosphere/atmosphere/issues/1346)

- \[websocket\] Possible thread race when wildcard mapping is used and fast broadcasting [\#1345](https://github.com/Atmosphere/atmosphere/issues/1345)

- Message is not received all the time at client side [\#1343](https://github.com/Atmosphere/atmosphere/issues/1343)

- \[java.io.IOException: Message Buffer too small\] Allow configuring the bytebuffer/charbuffer of WebSocketProcessor [\#1330](https://github.com/Atmosphere/atmosphere/issues/1330)

- \[Tomcat 7.0.27 and lower\] DefaultAsyncSupportResolver uses Jetty Continuation classes to detect Jetty [\#1290](https://github.com/Atmosphere/atmosphere/issues/1290)

- Add a new 5 minutes tutorial that use annotation only [\#1286](https://github.com/Atmosphere/atmosphere/issues/1286)

- Retrieving the original AtmosphereResource with WebSocket does not work in Jetty8 if recycleAtmosphereRequestResponse property is set to true [\#1081](https://github.com/Atmosphere/atmosphere/issues/1081)

- Add tutorial for WebSocket's streaming API [\#811](https://github.com/Atmosphere/atmosphere/issues/811)

## [atmosphere-project-2.0.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.3) (2013-10-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.2...atmosphere-project-2.0.3)

**Closed issues:**

- \[AtmosphereResourceStateRecovery\] BroadcastTracker lost state when timeout exprires [\#1342](https://github.com/Atmosphere/atmosphere/issues/1342)

- Jersy and atmosphere 2.0.2 [\#1341](https://github.com/Atmosphere/atmosphere/issues/1341)

- Performance regression with WebSocket and 2.0.2 [\#1340](https://github.com/Atmosphere/atmosphere/issues/1340)

- \[runtime\] Allow Listeners to handle a resumed connection BEFORE the connection is resumed [\#1337](https://github.com/Atmosphere/atmosphere/issues/1337)

- @ManagedService - auto broadcast executed unless @Message defined  [\#1336](https://github.com/Atmosphere/atmosphere/issues/1336)

- \[regression\] \[jsr356\] Jersey broken with latest jsr356 implementation [\#1335](https://github.com/Atmosphere/atmosphere/issues/1335)

- \[AtmosphereResourceLifecyleInterceptor\] Add timeout support [\#1334](https://github.com/Atmosphere/atmosphere/issues/1334)

- \[Undertow/GlassFish4\] JSR-356 Support is broken since 2.0.1 [\#1333](https://github.com/Atmosphere/atmosphere/issues/1333)

- \[AtmosphereResourceStateRecovery\] java.util.ConcurrentModificationException [\#1332](https://github.com/Atmosphere/atmosphere/issues/1332)

- Add support for WebSocketStreamingProtocol implementation [\#1331](https://github.com/Atmosphere/atmosphere/issues/1331)

- HTTP Requests get suspended when sent during atmosphere connect [\#1323](https://github.com/Atmosphere/atmosphere/issues/1323)

- Wasync client hangs when connecting to atmosphere-runtime-native 2.0.0-SNAPSHOT [\#1301](https://github.com/Atmosphere/atmosphere/issues/1301)

## [atmosphere-project-2.0.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.2) (2013-10-04)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.1...atmosphere-project-2.0.2)

**Closed issues:**

- Adding Programmatically AtmosphereInterceptor doesn't call the configure method  [\#1329](https://github.com/Atmosphere/atmosphere/issues/1329)

- NPE with HeaderBroadcasterCache [\#1328](https://github.com/Atmosphere/atmosphere/issues/1328)

- \[Graddle\] Fallback to manual annotation scanning  [\#1327](https://github.com/Atmosphere/atmosphere/issues/1327)

- @ManagedService object confusion [\#1326](https://github.com/Atmosphere/atmosphere/issues/1326)

- \[websocket\] If server times out the websocket, padding must be sent back [\#1325](https://github.com/Atmosphere/atmosphere/issues/1325)

- \[dropWizard\] Enable all classes scanning causes null pointer exception in org.atmosphere.cpr.AtmosphereFramework.setDefaultBroadcasterClassName\(\) [\#1324](https://github.com/Atmosphere/atmosphere/issues/1324)

- AtmosphereResponse.addCookie\(\) issue. [\#1322](https://github.com/Atmosphere/atmosphere/issues/1322)

- AtmosphereFramework.configureQueryStringAsRequest fails parsing queryString "=&X-atmo-protocol=true" at async timeout [\#1321](https://github.com/Atmosphere/atmosphere/issues/1321)

- Message lost when broadcasting to an empty Set<AtmosphereResource\> [\#1318](https://github.com/Atmosphere/atmosphere/issues/1318)

- Unsubscribe event sends a synchronous http call for cross domains and no cookie and session information is present in this request [\#1315](https://github.com/Atmosphere/atmosphere/issues/1315)

- No Cookie and session information in the unsubscribe request [\#1313](https://github.com/Atmosphere/atmosphere/issues/1313)

- Duplicate atmosphere resources on a broadcaster [\#1305](https://github.com/Atmosphere/atmosphere/issues/1305)

- \[CDI, Spring, Guice and Injection\] Ability to pass dependencies to resources [\#1190](https://github.com/Atmosphere/atmosphere/issues/1190)

- FakeHttpSession in Wicket causes java.lang.IllegalStateException: getAttribute: Session already invalidated at org.apache.catalina.session.StandardSession.getAttribute [\#1164](https://github.com/Atmosphere/atmosphere/issues/1164)

**Merged pull requests:**

- Added the AtmosphereObjectFactory interface to make integration with dependency injection frameworks easier. [\#1304](https://github.com/Atmosphere/atmosphere/pull/1304) ([nfranke](https://github.com/nfranke))

## [atmosphere-project-2.0.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.1) (2013-09-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.18...atmosphere-project-2.0.1)

**Closed issues:**

- NPE in GrizzlyCometSupport.resume\(\) [\#1319](https://github.com/Atmosphere/atmosphere/issues/1319)

- Wrong media type reported to Jersey [\#1317](https://github.com/Atmosphere/atmosphere/issues/1317)

- \[jsr356\] Session, Principal and Headers aren't set. [\#1316](https://github.com/Atmosphere/atmosphere/issues/1316)

- Annotations does not work with JavaRebel [\#1314](https://github.com/Atmosphere/atmosphere/issues/1314)

- Turn off jsr356 by default [\#1312](https://github.com/Atmosphere/atmosphere/issues/1312)

- Applying filters to cached messages in AtmosphereResourceStateRecovery [\#1309](https://github.com/Atmosphere/atmosphere/issues/1309)

- Tomcat WebSockets issues and workaround, workarounds missing [\#1308](https://github.com/Atmosphere/atmosphere/issues/1308)

- Fix Tomcat 7.0.43 WebSocket runtime changes with JDK 6 [\#1307](https://github.com/Atmosphere/atmosphere/issues/1307)

- Use X-Forwarded-For in WebSocket IP logging ? [\#1306](https://github.com/Atmosphere/atmosphere/issues/1306)

## [atmosphere-project-1.0.18](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.18) (2013-09-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0...atmosphere-project-1.0.18)

## [atmosphere-project-2.0.0](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0) (2013-09-19)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0.RC5...atmosphere-project-2.0.0)

**Closed issues:**

- AtmosphereResource.write swallow exceptions, prevent BroadcasterCache from caching messages [\#1303](https://github.com/Atmosphere/atmosphere/issues/1303)

- \[runtime\] New value returned from PerRequestBroadcastFilter breaks the UUIDBroadcasterCache [\#1302](https://github.com/Atmosphere/atmosphere/issues/1302)

- \[jersey\] \[tomcat\] \[native\] Tomcat fails to detect CometEvent for AtmosphereServlet mapped to "/\*" [\#1300](https://github.com/Atmosphere/atmosphere/issues/1300)

- AtmosphereFramework.addAtmosphereHandler must configure AtmosphereInterceptor for Dependency Injection [\#1298](https://github.com/Atmosphere/atmosphere/issues/1298)

- Primefaces push with GlassFish 4.0 [\#1296](https://github.com/Atmosphere/atmosphere/issues/1296)

- ConcurrentModificationException in DefaultWebSocketProcessor when original AtmosphereResource used [\#1293](https://github.com/Atmosphere/atmosphere/issues/1293)

- \[APR/JBossWeb\] Native doesn't find annotation [\#1292](https://github.com/Atmosphere/atmosphere/issues/1292)

- Tutorial for AtmosphereHandler with Websockets and Long Polling contains an error [\#1291](https://github.com/Atmosphere/atmosphere/issues/1291)

- \[websocket\]\[tomcat\] DefaultWebSocketProcessor.dispatchReader\(\) exception [\#1289](https://github.com/Atmosphere/atmosphere/issues/1289)

- Too many files open [\#1288](https://github.com/Atmosphere/atmosphere/issues/1288)

- Fix sample broken link, update the page [\#1287](https://github.com/Atmosphere/atmosphere/issues/1287)

- Update documentation atmosphere.js AND jquery.atmosphere.js [\#1285](https://github.com/Atmosphere/atmosphere/issues/1285)

- Document new Javascrip function. [\#1284](https://github.com/Atmosphere/atmosphere/issues/1284)

- \[websockethandler\] Possible deadlock? [\#1283](https://github.com/Atmosphere/atmosphere/issues/1283)

- Invalid <issueManagement\> in the pom file [\#1281](https://github.com/Atmosphere/atmosphere/issues/1281)

- java.lang.IllegalStateException: Recycled [\#1280](https://github.com/Atmosphere/atmosphere/issues/1280)

- 1.0.16 compat bundles importing 2.0 version and exporting 0.0 of the corresponding package [\#1276](https://github.com/Atmosphere/atmosphere/issues/1276)

- Recent change to atmosphere-runtime-native 2.0.0-SNAPSHOT causes wasync Java client to hang when connecting. [\#1273](https://github.com/Atmosphere/atmosphere/issues/1273)

- Atmosphere invalidade Spring Security Session [\#1244](https://github.com/Atmosphere/atmosphere/issues/1244)

- CORS with IE8 and IE9 seems broken in v1.0.15 \(worked in v1.0.13\) [\#1236](https://github.com/Atmosphere/atmosphere/issues/1236)

- \[play\] NullPointerException in WebSocket.write\(\) [\#1196](https://github.com/Atmosphere/atmosphere/issues/1196)

- java.lang.NullPointerException: charsetName with socketio [\#1169](https://github.com/Atmosphere/atmosphere/issues/1169)

- RFE: Allow closing websockets from AtmosphereInterceptor [\#1162](https://github.com/Atmosphere/atmosphere/issues/1162)

- Auto-detection of atmosphere handlers fails in specific deployments [\#1157](https://github.com/Atmosphere/atmosphere/issues/1157)

- MetaBroadcaster not compatible with Jersey Path annotation [\#1154](https://github.com/Atmosphere/atmosphere/issues/1154)

- Firefox closes websocket/streaming/long-polling request when window.location is changed [\#1128](https://github.com/Atmosphere/atmosphere/issues/1128)

- Chrome cancels streaming/long-polling request when maybe navigating away [\#1126](https://github.com/Atmosphere/atmosphere/issues/1126)

- WebSocket doesn't work with Glassfish 3.1.2 when Guice is used [\#1112](https://github.com/Atmosphere/atmosphere/issues/1112)

- Setting request shared attribute to true causes JavaScript errors in Firefox, IE, and Safari [\#1102](https://github.com/Atmosphere/atmosphere/issues/1102)

- Switching WebSocket Write Mode at Runtime [\#857](https://github.com/Atmosphere/atmosphere/issues/857)

- JMS, XMPP, ... \(via AbstractBroadcasterProxy\) : Broadcast to a specific resource [\#799](https://github.com/Atmosphere/atmosphere/issues/799)

- \[FeatureRequest\] MetaBroadcaster should work in Cloud based environments [\#729](https://github.com/Atmosphere/atmosphere/issues/729)

- RFE: callback for a message [\#507](https://github.com/Atmosphere/atmosphere/issues/507)

- @Singleton -\> ClassCastException on broadcaster.addAtmosphereResource\(\) w/ Jersey resource [\#444](https://github.com/Atmosphere/atmosphere/issues/444)

- Add documentation for channel-based annotation @Susbscribe/@Publish [\#410](https://github.com/Atmosphere/atmosphere/issues/410)

- Add better Filter Mapping Algorithm [\#157](https://github.com/Atmosphere/atmosphere/issues/157)

**Merged pull requests:**

- Additional patch for \#1279 [\#1282](https://github.com/Atmosphere/atmosphere/pull/1282) ([flowersinthesand](https://github.com/flowersinthesand))

- removing webapp overlay for jquery [\#1077](https://github.com/Atmosphere/atmosphere/pull/1077) ([mkleine](https://github.com/mkleine))

- added the functionality of sending message timestamp along with message [\#900](https://github.com/Atmosphere/atmosphere/pull/900) ([vinodsral](https://github.com/vinodsral))

- Update modules/jquery/src/main/webapp/jquery/jquery.atmosphere.js [\#834](https://github.com/Atmosphere/atmosphere/pull/834) ([bennyn](https://github.com/bennyn))

## [atmosphere-project-2.0.0.RC5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0.RC5) (2013-09-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.17...atmosphere-project-2.0.0.RC5)

**Closed issues:**

- JSONPAtmosphereInterceptor fails to encode JSON properly [\#1279](https://github.com/Atmosphere/atmosphere/issues/1279)

- \[Regression 2.0.0.RC4\] BytecodeBasedAnnotationProcessor fail to find classes [\#1278](https://github.com/Atmosphere/atmosphere/issues/1278)

- Document InvokationOrder interface for AtmosphereInterceptor [\#1275](https://github.com/Atmosphere/atmosphere/issues/1275)

- Add an option to bypass FakeHttpSession [\#1269](https://github.com/Atmosphere/atmosphere/issues/1269)

- \[migration \] Document before/afterFilter changes from 1.0.x to 2.0 [\#1251](https://github.com/Atmosphere/atmosphere/issues/1251)

- \[migration\] whitespace messages [\#1219](https://github.com/Atmosphere/atmosphere/issues/1219)

- AtmosphereResourceLifecycleInterceptor and event listeners Documentation [\#1180](https://github.com/Atmosphere/atmosphere/issues/1180)

- \[atmosphere.js\] JSONP not working [\#1090](https://github.com/Atmosphere/atmosphere/issues/1090)

- How to integrate Atmosphere with Spring/Grizzly without XML [\#990](https://github.com/Atmosphere/atmosphere/issues/990)

**Merged pull requests:**

- fix compat bundles's import and export [\#1277](https://github.com/Atmosphere/atmosphere/pull/1277) ([elakito](https://github.com/elakito))

## [atmosphere-project-1.0.17](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.17) (2013-09-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0.RC4...atmosphere-project-1.0.17)

**Closed issues:**

- Add a way to support AtmosphereInterceptor execution order [\#1274](https://github.com/Atmosphere/atmosphere/issues/1274)

- Document new Custom Annotation mechanism  [\#1270](https://github.com/Atmosphere/atmosphere/issues/1270)

- \[play\] Browser tabs freeze [\#1194](https://github.com/Atmosphere/atmosphere/issues/1194)

- Improve javadoc for BroadcasterCache [\#1188](https://github.com/Atmosphere/atmosphere/issues/1188)

- Document @AtmosphereService annotations [\#1173](https://github.com/Atmosphere/atmosphere/issues/1173)

- GWT: IE cannot read session cookie with Tomcat7, results in IllegalStateException: [\#1096](https://github.com/Atmosphere/atmosphere/issues/1096)

- \[DefaultBroadcaster\] Invoking BroadcasterFuture.cancel\(..\) must cancel the async write operation. [\#889](https://github.com/Atmosphere/atmosphere/issues/889)

- Document the new @ManagedService annotation [\#810](https://github.com/Atmosphere/atmosphere/issues/810)

- Update tutorial to Atmosphere 1.1 [\#809](https://github.com/Atmosphere/atmosphere/issues/809)

- Add full documentation of new annotation: @Get @Post @Delete @Message etc. [\#807](https://github.com/Atmosphere/atmosphere/issues/807)

## [atmosphere-project-2.0.0.RC4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0.RC4) (2013-09-08)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.16...atmosphere-project-2.0.0.RC4)

**Closed issues:**

- DefaultBroadcaster.removeAtmosphereResource\(\) doesn't remove [\#1272](https://github.com/Atmosphere/atmosphere/issues/1272)

- Atmosphere 2.0.0.RC3: NullPointerException at org.atmosphere.cpr.SessionSupport.sessionDestroyed\(SessionSupport.java:44\) [\#1268](https://github.com/Atmosphere/atmosphere/issues/1268)

- Tomcat/Spring should not create "phantom" HTTP request upon web app startup [\#1267](https://github.com/Atmosphere/atmosphere/issues/1267)

- Annotation scanning is broken in JBoss 6.1.x [\#1266](https://github.com/Atmosphere/atmosphere/issues/1266)

- Deadlock detected in Atmosphere Scheduler-2 [\#1265](https://github.com/Atmosphere/atmosphere/issues/1265)

- \[Tomcat/WebSocket\] Deadlock detected in Atmosphere Scheduler [\#1264](https://github.com/Atmosphere/atmosphere/issues/1264)

- Compatibility check for 'org.apache.catalina.websocket.WebSocketServlet' with jbossweb 7.2.2.Final yields a false positive [\#1262](https://github.com/Atmosphere/atmosphere/issues/1262)

- Misfire of the polling request [\#1261](https://github.com/Atmosphere/atmosphere/issues/1261)

- Atmosphere causing stuck threads on Weblogic 12 [\#1260](https://github.com/Atmosphere/atmosphere/issues/1260)

- rest-chat jersey demo app freezes [\#1259](https://github.com/Atmosphere/atmosphere/issues/1259)

- Long-polling chat in 2.0.0.RC3 [\#1258](https://github.com/Atmosphere/atmosphere/issues/1258)

- How About Jersey 2 support? [\#1257](https://github.com/Atmosphere/atmosphere/issues/1257)

- \[RestEasy\] @FormParam params  reset to null 2.0 RC3 . Works fine in 1.0.15 [\#1256](https://github.com/Atmosphere/atmosphere/issues/1256)

- \[Improvement\] make it easy to register a WebSocketHandler to AtmosphereFramework [\#1255](https://github.com/Atmosphere/atmosphere/issues/1255)

- \[investigate\] Path problem? [\#1254](https://github.com/Atmosphere/atmosphere/issues/1254)

- Remove charset from SSE Content-Type [\#1250](https://github.com/Atmosphere/atmosphere/issues/1250)

- Atmosphere's HttpServletRequest.getRequestURI\(\) must never return `null` in all circumstances [\#1246](https://github.com/Atmosphere/atmosphere/issues/1246)

- \[SSE\] First two messages parsed incorrectly when TrackMessageSizeInterceptor is used [\#1245](https://github.com/Atmosphere/atmosphere/issues/1245)

- Can't get websockets to work with JBOSS EAP6 [\#1243](https://github.com/Atmosphere/atmosphere/issues/1243)

- Feature request: An AtmosphereResourceEventListener method which is invoked when AtmosphereResource.close\(\) is used [\#1242](https://github.com/Atmosphere/atmosphere/issues/1242)

- AtmosphereResourceEventListener.onBroadcast isn't called when broadcasting to long-polling connected clients [\#1241](https://github.com/Atmosphere/atmosphere/issues/1241)

- Pushing data from client to server doesn't reset MAX\_INACTIVE attribute [\#1240](https://github.com/Atmosphere/atmosphere/issues/1240)

- \[jetty9\] native == true cause issues [\#1238](https://github.com/Atmosphere/atmosphere/issues/1238)

- query string for POST requests [\#1228](https://github.com/Atmosphere/atmosphere/issues/1228)

- Inability to detect closed connection using WebLogic/Jersey [\#1227](https://github.com/Atmosphere/atmosphere/issues/1227)

- Test Jetty 9.1 JSR 356 before releasing 2.0.0 [\#1222](https://github.com/Atmosphere/atmosphere/issues/1222)

- \[jetty 9.1\] Native WebSocket Support broken [\#1220](https://github.com/Atmosphere/atmosphere/issues/1220)

- Not possible to remove AtmosphereResource from Broadcaster under default scope [\#1213](https://github.com/Atmosphere/atmosphere/issues/1213)

- GlassFish 4 mapping issue [\#1209](https://github.com/Atmosphere/atmosphere/issues/1209)

- Encoding issue with 'streaming' transport [\#1208](https://github.com/Atmosphere/atmosphere/issues/1208)

- Provide an example showing atmospherehandler-pubsub working with JBoss AS 7 and WebSockets [\#1191](https://github.com/Atmosphere/atmosphere/issues/1191)

- \[GlassFish 4\] No AtmosphereHandler maps request for ... [\#1179](https://github.com/Atmosphere/atmosphere/issues/1179)

- Possible SessionSupport issues with GWT [\#1166](https://github.com/Atmosphere/atmosphere/issues/1166)

- \[jersey\] NPE with Websocket when unexpected Jersey error happens. [\#1163](https://github.com/Atmosphere/atmosphere/issues/1163)

- Auto-configuration via annotation processing fails in specific deployments [\#1159](https://github.com/Atmosphere/atmosphere/issues/1159)

- Server side timeout not working under certain conditions [\#1061](https://github.com/Atmosphere/atmosphere/issues/1061)

- \[atmosphere.js\] Memory can grow pretty heavily when local is used [\#1058](https://github.com/Atmosphere/atmosphere/issues/1058)

- Atmosphere incompatible with Grizzly 2.3.1 [\#1055](https://github.com/Atmosphere/atmosphere/issues/1055)

- Atmosphere Rest-Chat not deploying to jboss 5.1.0 [\#995](https://github.com/Atmosphere/atmosphere/issues/995)

- \[test\] re-enable tests before 1.1.0 release [\#980](https://github.com/Atmosphere/atmosphere/issues/980)

- \[Internet Explorer\] Tracking cookie not deleted with shared = true [\#968](https://github.com/Atmosphere/atmosphere/issues/968)

- org.atmosphere.cpr.defaultContextType [\#929](https://github.com/Atmosphere/atmosphere/issues/929)

**Merged pull requests:**

- Throw exceptions when session has been invalidated [\#1271](https://github.com/Atmosphere/atmosphere/pull/1271) ([wolfie](https://github.com/wolfie))

- Fixes \#1262 - changes the check class for Tomcat 7 Websocket support. [\#1263](https://github.com/Atmosphere/atmosphere/pull/1263) ([bleathem](https://github.com/bleathem))

- Allow disabling the analytics call during initialization. [\#1174](https://github.com/Atmosphere/atmosphere/pull/1174) ([strategicpause](https://github.com/strategicpause))

## [atmosphere-project-1.0.16](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.16) (2013-08-28)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0.RC3...atmosphere-project-1.0.16)

**Closed issues:**

- \[patch\] PrimeFaces Implementation loose messages [\#1253](https://github.com/Atmosphere/atmosphere/issues/1253)

- \[cache\] Filter abording or skipping messages must remove original message from the cache [\#1252](https://github.com/Atmosphere/atmosphere/issues/1252)

- Cyclic dependencies unnessary with BroadcasterFuture [\#1249](https://github.com/Atmosphere/atmosphere/issues/1249)

- \[leak\] Possible Memory Leak with BroadcasterCache [\#1248](https://github.com/Atmosphere/atmosphere/issues/1248)

- \[cache\] Let the specific server throws exception instead of babysitting a connection [\#1247](https://github.com/Atmosphere/atmosphere/issues/1247)

- Message received from cache is erroring in 1.0.15 version [\#1239](https://github.com/Atmosphere/atmosphere/issues/1239)

- Error: Request object no longer valid. This object has been cancelled [\#1235](https://github.com/Atmosphere/atmosphere/issues/1235)

- BlockingIO always selected even when org.atmosphere.useWebSocketAndServlet3 is true [\#1234](https://github.com/Atmosphere/atmosphere/issues/1234)

- Atmosphere OSGI bundle requires javax.websocket version < 1.0 [\#1233](https://github.com/Atmosphere/atmosphere/issues/1233)

- Recovering a destroyed Broadcaster Docu unclear [\#1232](https://github.com/Atmosphere/atmosphere/issues/1232)

- atmosphere-runtime-native 2.0.0.RC3 generates old dependency [\#1231](https://github.com/Atmosphere/atmosphere/issues/1231)

- onOpen is not called when connected using long polling [\#1230](https://github.com/Atmosphere/atmosphere/issues/1230)

- \[Weblogic 12c\] AnnotationDetector Not Able to Load and Scan Handler Classes In .war Deployment [\#1229](https://github.com/Atmosphere/atmosphere/issues/1229)

- Invalidating spring security session in tomcat 7 [\#1226](https://github.com/Atmosphere/atmosphere/issues/1226)

- Any URI with "\_" in first path segment will throw:  org.atmosphere.cpr.AtmosphereMappingException: No AtmosphereHandler maps request for /\_... [\#1225](https://github.com/Atmosphere/atmosphere/issues/1225)

- Websocket Error when using IE8 or IE9  [\#1224](https://github.com/Atmosphere/atmosphere/issues/1224)

- ERROR cpr.AtmosphereFramework  - AtmosphereFramework exception java.lang.IllegalStateException: Not supported. [\#1218](https://github.com/Atmosphere/atmosphere/issues/1218)

- Improve JavaDoc [\#1217](https://github.com/Atmosphere/atmosphere/issues/1217)

- Documentation - plugins [\#1216](https://github.com/Atmosphere/atmosphere/issues/1216)

- ERROR cpr.AtmosphereFramework  - AtmosphereFramework exception java.lang.IllegalStateException: Not supported. [\#1215](https://github.com/Atmosphere/atmosphere/issues/1215)

- ERROR cpr.AtmosphereFramework  - AtmosphereFramework exception java.lang.IllegalStateException: Not supported. [\#1214](https://github.com/Atmosphere/atmosphere/issues/1214)

- Need configuration option for disabling analytics. [\#1212](https://github.com/Atmosphere/atmosphere/issues/1212)

- IE9 Error: RROR cpr.AtmosphereFramework  - AtmosphereFramework exception Message: Not supported. [\#1211](https://github.com/Atmosphere/atmosphere/issues/1211)

- Test Tomcat 8 WebSocket API before final 2.0.0 release [\#1210](https://github.com/Atmosphere/atmosphere/issues/1210)

- \[https\] Atmosphere 1.0.x MessageLengthTracking broken when "chunking" happens in wrong place [\#1199](https://github.com/Atmosphere/atmosphere/issues/1199)

- flushPadding in 1.0.x AtmosphereResourceImpl incorrect? [\#1198](https://github.com/Atmosphere/atmosphere/issues/1198)

- Deadlock with Grizzly [\#1183](https://github.com/Atmosphere/atmosphere/issues/1183)

- Atmosphere ignores interceptors configured in atmosphere.xml [\#1161](https://github.com/Atmosphere/atmosphere/issues/1161)

- Websocket error on Atmosphere.js [\#1146](https://github.com/Atmosphere/atmosphere/issues/1146)

**Merged pull requests:**

- Fixes \#1234; defaultCometSupport expects a boolean indicating if blocking is preferred [\#1237](https://github.com/Atmosphere/atmosphere/pull/1237) ([klieber](https://github.com/klieber))

- fixes issue where BlockingIOCometSupport is always used in Servlet 3.0 container  [\#1223](https://github.com/Atmosphere/atmosphere/pull/1223) ([klieber](https://github.com/klieber))

- Allows injecting a reference to AtmosphereResource into a @Message methods [\#1221](https://github.com/Atmosphere/atmosphere/pull/1221) ([klieber](https://github.com/klieber))

## [atmosphere-project-2.0.0.RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0.RC3) (2013-07-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.15...atmosphere-project-2.0.0.RC3)

## [atmosphere-project-1.0.15](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.15) (2013-07-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.14...atmosphere-project-1.0.15)

**Closed issues:**

- Jersey rest-chat sample - writeEntity=false is ignored [\#1206](https://github.com/Atmosphere/atmosphere/issues/1206)

## [atmosphere-project-1.0.14](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.14) (2013-07-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0.RC2...atmosphere-project-1.0.14)

## [atmosphere-project-2.0.0.RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0.RC2) (2013-07-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-2.0.0.RC1...atmosphere-project-2.0.0.RC2)

**Closed issues:**

- ERROR cpr.AtmosphereFramework  - AtmosphereFramework exception java.lang.IllegalStateException: Not supported. [\#1205](https://github.com/Atmosphere/atmosphere/issues/1205)

- java.nio.charset.MalformedInputException when broadcasting to IE8   [\#1203](https://github.com/Atmosphere/atmosphere/issues/1203)

- ERROR handler.ReflectorServletProcessor  - onRequest\(\) javax.servlet.ServletException [\#1202](https://github.com/Atmosphere/atmosphere/issues/1202)

- Enabling org.atmosphere.cpr.sessionSupport throws java.io.NotSerializableException [\#1201](https://github.com/Atmosphere/atmosphere/issues/1201)

- get headers from server [\#1197](https://github.com/Atmosphere/atmosphere/issues/1197)

- Is there a way to share the application context with atmosphere servlet? [\#1195](https://github.com/Atmosphere/atmosphere/issues/1195)

- Atmosphere v2.0.0.RC1 -TrackMessageSizeFilter Issue [\#1192](https://github.com/Atmosphere/atmosphere/issues/1192)

- ClassCastException when multiple non-String objects returned from Cache [\#1189](https://github.com/Atmosphere/atmosphere/issues/1189)

- Race condition in DefaultBroadcaster.getAsyncWriteHandler [\#1187](https://github.com/Atmosphere/atmosphere/issues/1187)

- \[JBossWeb\] Handle ERROR events as END/EOF  [\#1186](https://github.com/Atmosphere/atmosphere/issues/1186)

- @Disconnect not getting called in @ManagedService [\#1181](https://github.com/Atmosphere/atmosphere/issues/1181)

- Annotation configuration issue [\#1177](https://github.com/Atmosphere/atmosphere/issues/1177)

- Websocket: AtmosphereResourceStateRecovery produces warning message [\#1167](https://github.com/Atmosphere/atmosphere/issues/1167)

- SSE: AtmosphereResource has wrong Broadcaster on disconnection [\#1158](https://github.com/Atmosphere/atmosphere/issues/1158)

- SSE + CORS restricted on the client side [\#1156](https://github.com/Atmosphere/atmosphere/issues/1156)

- UUIDBroadcasterCache: ClassCastException when using Jersey and broadcasting Object [\#1155](https://github.com/Atmosphere/atmosphere/issues/1155)

- Multiple Content-Type Headers are returned [\#1147](https://github.com/Atmosphere/atmosphere/issues/1147)

- JBoss websocket detection incorrectly done [\#1145](https://github.com/Atmosphere/atmosphere/issues/1145)

- ReflectorServletProcessor.servletClassName isn't set when Guice is used [\#1129](https://github.com/Atmosphere/atmosphere/issues/1129)

- AtmosphereNativeCometServlet can't be used if AtmosphereGuiceServlet is used in 1.1 [\#1125](https://github.com/Atmosphere/atmosphere/issues/1125)

- Add jquery-atmosphere to cdnjs [\#904](https://github.com/Atmosphere/atmosphere/issues/904)

- Shiro & null ServletContext [\#724](https://github.com/Atmosphere/atmosphere/issues/724)

**Merged pull requests:**

- make AnnotationDetector work on JBoss 5.1 whose VFS URL protocol is 'vfsfile' [\#1207](https://github.com/Atmosphere/atmosphere/pull/1207) ([kevensya](https://github.com/kevensya))

- Add SECURITY\_SUBJECT parameter to FrameworkConfig [\#1185](https://github.com/Atmosphere/atmosphere/pull/1185) ([sdnetwork](https://github.com/sdnetwork))

## [atmosphere-project-2.0.0.RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-2.0.0.RC1) (2013-07-08)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.RC4...atmosphere-project-2.0.0.RC1)

**Closed issues:**

- AtmosphereFramework exception java.lang.IllegalStateException: Not supported. [\#1184](https://github.com/Atmosphere/atmosphere/issues/1184)

- Wrong logic in UUIDBroadcasterCache may cause messages lost [\#1176](https://github.com/Atmosphere/atmosphere/issues/1176)

- Memory leak in PrimePush [\#1171](https://github.com/Atmosphere/atmosphere/issues/1171)

- @ManagedService does not work according to documentation [\#1170](https://github.com/Atmosphere/atmosphere/issues/1170)

- Delay in broadcasting data [\#1168](https://github.com/Atmosphere/atmosphere/issues/1168)

- atmosphere-jersey: It's possible to send data to a client before Atmosphere sends the UUID [\#1160](https://github.com/Atmosphere/atmosphere/issues/1160)

- \[optimization\] Flush cache in one big string instead of one by one. [\#1152](https://github.com/Atmosphere/atmosphere/issues/1152)

- WebSocketProcessorFactory sometimes returns wrong WebSocketProcessor with multiple AtmosphereFramework instances [\#1150](https://github.com/Atmosphere/atmosphere/issues/1150)

- resource.resume\(\) locks [\#1148](https://github.com/Atmosphere/atmosphere/issues/1148)

- \[For Framework\] Add @AtmosphereService  [\#1109](https://github.com/Atmosphere/atmosphere/issues/1109)

- \[Enhancement\] deprecate AtmosphereResouce.setBroadcaster\(\) [\#1091](https://github.com/Atmosphere/atmosphere/issues/1091)

- MetaBroadcaster broadcastTo not thread safe [\#1035](https://github.com/Atmosphere/atmosphere/issues/1035)

**Merged pull requests:**

- Add Shiro interceptor [\#1182](https://github.com/Atmosphere/atmosphere/pull/1182) ([sdnetwork](https://github.com/sdnetwork))

- Add parameter to throw exception on cloned request [\#1178](https://github.com/Atmosphere/atmosphere/pull/1178) ([sdnetwork](https://github.com/sdnetwork))

- Retrieve more informations from the initial request in clone request and throw exception in critical authentication/authorization 's method [\#1175](https://github.com/Atmosphere/atmosphere/pull/1175) ([sdnetwork](https://github.com/sdnetwork))

- Copy the Principal object of the httpservletrequest when the initial request is cloned [\#1172](https://github.com/Atmosphere/atmosphere/pull/1172) ([sdnetwork](https://github.com/sdnetwork))

- Add CORS interceptor [\#1165](https://github.com/Atmosphere/atmosphere/pull/1165) ([sobolewsk](https://github.com/sobolewsk))

- Fixes bug \(\#958\) where messages going through ExcludeSessionBroadcaster is never dispatched [\#1153](https://github.com/Atmosphere/atmosphere/pull/1153) ([erlendfh](https://github.com/erlendfh))

- Prevent calling onBroadcast called twice with SimpleBroadcaster [\#1149](https://github.com/Atmosphere/atmosphere/pull/1149) ([slovdahl](https://github.com/slovdahl))

- Fix WebSocketProcessorFactory support for multiple framework instances [\#1151](https://github.com/Atmosphere/atmosphere/pull/1151) ([jdahlstrom](https://github.com/jdahlstrom))

## [atmosphere-project-1.1.0.RC4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.RC4) (2013-06-14)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.RC3...atmosphere-project-1.1.0.RC4)

**Closed issues:**

- Atmosphere on Tomcat/7.0.39 not Working with Servlet 3.0 Async API correctly [\#1144](https://github.com/Atmosphere/atmosphere/issues/1144)

- jquery.atmosphere - trackMessageSize [\#1142](https://github.com/Atmosphere/atmosphere/issues/1142)

- \[long-polling\] Possible Message Duplication if retrieving from the cache fail [\#1141](https://github.com/Atmosphere/atmosphere/issues/1141)

- Streaming always aborts if connectTimeout is set [\#1138](https://github.com/Atmosphere/atmosphere/issues/1138)

- PerRequestBroadcastFilter called twice [\#1136](https://github.com/Atmosphere/atmosphere/issues/1136)

- Possible Message Lost [\#1135](https://github.com/Atmosphere/atmosphere/issues/1135)

- ERROR cpr.AtmosphereFramework  - AtmosphereFramework exception when using IE8 [\#1131](https://github.com/Atmosphere/atmosphere/issues/1131)

- jquery.atmosphere.js uses deprecated JQuery features [\#1130](https://github.com/Atmosphere/atmosphere/issues/1130)

- Client does not try to re-establish a closed streaming connection  [\#1127](https://github.com/Atmosphere/atmosphere/issues/1127)

- \[enableProtocol=false, Jetty 7.5\] Streaming transport allows writing to the response indefinitely after disconnect [\#1123](https://github.com/Atmosphere/atmosphere/issues/1123)

- Unsubscribing from a failed subscription pauses while the close AJAX request is being sent [\#1122](https://github.com/Atmosphere/atmosphere/issues/1122)

- onOpen callback not invoked after Websocket connection fails and is restored [\#1121](https://github.com/Atmosphere/atmosphere/issues/1121)

- \[CORS\] Streaming broken [\#1120](https://github.com/Atmosphere/atmosphere/issues/1120)

- \[CORS\] Issues when enableProtocol : true [\#1119](https://github.com/Atmosphere/atmosphere/issues/1119)

- Jquery.atmosphere.js issue in IE8 [\#1116](https://github.com/Atmosphere/atmosphere/issues/1116)

- \[jersey\] Deprecate outputComments API for 1.1 [\#1115](https://github.com/Atmosphere/atmosphere/issues/1115)

- \[jetty9\] Possible ClassCastException on Linux [\#1113](https://github.com/Atmosphere/atmosphere/issues/1113)

- \[sse\] Chrome reconnect forever on failure [\#1111](https://github.com/Atmosphere/atmosphere/issues/1111)

- IE8 error with latest jquery.atmosphere.js [\#1108](https://github.com/Atmosphere/atmosphere/issues/1108)

- Atmosphere cannot scan annotations on Wildfly/Jboss  [\#1107](https://github.com/Atmosphere/atmosphere/issues/1107)

- Use java.util.logging instead of slf4j [\#1106](https://github.com/Atmosphere/atmosphere/issues/1106)

- atmosphere.js give error related to atmosphere.debug\(\) method call [\#1105](https://github.com/Atmosphere/atmosphere/issues/1105)

- \[runtime\] - high cpu usage [\#1104](https://github.com/Atmosphere/atmosphere/issues/1104)

- Atmosphere project is not up to date on Ohloh web site [\#1101](https://github.com/Atmosphere/atmosphere/issues/1101)

- \[osgi\[ \[session\] Exception running 1.0.13 on jetty 8.1.10 [\#1100](https://github.com/Atmosphere/atmosphere/issues/1100)

- Snapshot artifacts contain wrong metadata [\#1098](https://github.com/Atmosphere/atmosphere/issues/1098)

- Two consecutive emtpy messages confuse message size tracking [\#1042](https://github.com/Atmosphere/atmosphere/issues/1042)

- \[jquery.atmosphere.js\]can not detect connection failure when unplug the network cable. [\#1004](https://github.com/Atmosphere/atmosphere/issues/1004)

- failed to create comet support class [\#912](https://github.com/Atmosphere/atmosphere/issues/912)

- Generic WebSocketEventListener interface needed [\#887](https://github.com/Atmosphere/atmosphere/issues/887)

- Deprecate BroadcasterFactory.getDefault\(\) [\#867](https://github.com/Atmosphere/atmosphere/issues/867)

- \[jQuery.atmosphere\] Add callbacks for successful push requests [\#808](https://github.com/Atmosphere/atmosphere/issues/808)

- Create an AMQPBroadcaster for use with RabbitMQ [\#703](https://github.com/Atmosphere/atmosphere/issues/703)

- \[sse\] Add support for event definition [\#434](https://github.com/Atmosphere/atmosphere/issues/434)

- \[atmosphere.js\] Add support for .on\("event",function"\) [\#402](https://github.com/Atmosphere/atmosphere/issues/402)

- \[runtime\] Add SPDY support [\#232](https://github.com/Atmosphere/atmosphere/issues/232)

**Merged pull requests:**

- clarify isNew\(\) in AtmosphereRequest\#getSession [\#1140](https://github.com/Atmosphere/atmosphere/pull/1140) ([dretzlaff](https://github.com/dretzlaff))

- don't return invalid session from AtmosphereRequest\#getSession [\#1139](https://github.com/Atmosphere/atmosphere/pull/1139) ([dretzlaff](https://github.com/dretzlaff))

- HeartbeatInterceptor: Force use of AsyncIOWriter. [\#1134](https://github.com/Atmosphere/atmosphere/pull/1134) ([slovdahl](https://github.com/slovdahl))

- ApplicationConfig: Made the javadoc more consistent. [\#1133](https://github.com/Atmosphere/atmosphere/pull/1133) ([slovdahl](https://github.com/slovdahl))

- ApplicationConfig: Made the javadoc more consistent. [\#1132](https://github.com/Atmosphere/atmosphere/pull/1132) ([slovdahl](https://github.com/slovdahl))

- Detects the RMIBroadcaster in classpath when Atmosphere starts [\#1124](https://github.com/Atmosphere/atmosphere/pull/1124) ([gdrouet](https://github.com/gdrouet))

- Atmosphere 1.0.x javascript.js module [\#1118](https://github.com/Atmosphere/atmosphere/pull/1118) ([slovdahl](https://github.com/slovdahl))

- Fixed NPE. [\#1117](https://github.com/Atmosphere/atmosphere/pull/1117) ([slovdahl](https://github.com/slovdahl))

- Clean up class loading problem with the ServletContainerInitializer [\#1114](https://github.com/Atmosphere/atmosphere/pull/1114) ([stuartwdouglas](https://github.com/stuartwdouglas))

- Add ServletContainerInitializer based annotation scanner \(\#1107\)  [\#1110](https://github.com/Atmosphere/atmosphere/pull/1110) ([stuartwdouglas](https://github.com/stuartwdouglas))

## [atmosphere-project-1.1.0.RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.RC3) (2013-05-24)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.13...atmosphere-project-1.1.0.RC3)

**Closed issues:**

- Atmosphere 1.1.0.RC2 doesn't support filterClassName [\#1103](https://github.com/Atmosphere/atmosphere/issues/1103)

- Atmosphere mapper exception [\#1099](https://github.com/Atmosphere/atmosphere/issues/1099)

- browser making get method call [\#1097](https://github.com/Atmosphere/atmosphere/issues/1097)

- Custom headers end up in URL parameters instead of HTTP headers section [\#1095](https://github.com/Atmosphere/atmosphere/issues/1095)

- \[firefox\]\[atmosphere.js\] SSE fail to detect disconnection [\#1094](https://github.com/Atmosphere/atmosphere/issues/1094)

- Atmosphere GWT20 RC2 on maven central? [\#1093](https://github.com/Atmosphere/atmosphere/issues/1093)

- X-Atmosphere-tracking-id - BigDecimal cannot be cast to java.lang.String [\#1092](https://github.com/Atmosphere/atmosphere/issues/1092)

- \[atmosphere.js\] Add Encoder/Decoder Support [\#1089](https://github.com/Atmosphere/atmosphere/issues/1089)

- \[websocket\] Possible race between OnDisconnectInterceptor and WebSocket.onClose [\#1087](https://github.com/Atmosphere/atmosphere/issues/1087)

- AtmosphereResource.getRequest\(\).getHeader\("User-Agent"\) returns null [\#1085](https://github.com/Atmosphere/atmosphere/issues/1085)

- Client timeout setting doesn't work properly [\#1084](https://github.com/Atmosphere/atmosphere/issues/1084)

- Jquery 1.0.12  X-Atmosphere-tracking-id is 0 [\#1083](https://github.com/Atmosphere/atmosphere/issues/1083)

- \[\#1078\] Interceptors should skip requests if transport is undefined [\#1082](https://github.com/Atmosphere/atmosphere/issues/1082)

- Atmosphere GWT20 RC2? [\#1080](https://github.com/Atmosphere/atmosphere/issues/1080)

- Documentation request for Example without jQuery [\#1030](https://github.com/Atmosphere/atmosphere/issues/1030)

- Performance issues with supportOutOfOrderBroadcast=true and many broadcasters [\#1029](https://github.com/Atmosphere/atmosphere/issues/1029)

- \[portal.js\] Streaming incorrectly combines messages if a part of the message starts with whitespace [\#1024](https://github.com/Atmosphere/atmosphere/issues/1024)

- \[portal.js\] Websocket transport never falls back to backup transport [\#997](https://github.com/Atmosphere/atmosphere/issues/997)

- \[atmosphere/portal.js\] Cached Messages sent twice upon browser reconnection [\#989](https://github.com/Atmosphere/atmosphere/issues/989)

- \[api\] Fix AtmosphereResourceEventListener & WebSocketEventListener onDisconnect  [\#818](https://github.com/Atmosphere/atmosphere/issues/818)

**Merged pull requests:**

- Fixed HeartbeatInterceptor compilation error [\#1088](https://github.com/Atmosphere/atmosphere/pull/1088) ([dyrkin](https://github.com/dyrkin))

- \#1082 [\#1086](https://github.com/Atmosphere/atmosphere/pull/1086) ([ajaychandran](https://github.com/ajaychandran))

## [atmosphere-project-1.0.13](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.13) (2013-05-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.RC2...atmosphere-project-1.0.13)

**Closed issues:**

- 1.1.0.RC2: Filter's does not work anymore [\#1079](https://github.com/Atmosphere/atmosphere/issues/1079)

- Interceptors should skip requests if transport is undefined [\#1078](https://github.com/Atmosphere/atmosphere/issues/1078)

- \[atmosphere.js\] Do not trim messages before trackMessageLength is executed [\#1076](https://github.com/Atmosphere/atmosphere/issues/1076)

- \[streaming\] Switch padding to whitespace [\#1075](https://github.com/Atmosphere/atmosphere/issues/1075)

- Session caching in Atmosphere resource fails session fixation protection [\#1074](https://github.com/Atmosphere/atmosphere/issues/1074)

- \[Serializer\] AbstractReflectorAtmosphereHandler fail to serialize cached messages [\#1073](https://github.com/Atmosphere/atmosphere/issues/1073)

- \[runtime\] Add MeteorNativeCometServlet and AtmosphereNativeCometServlet for Tomcat6/7/JBoss native CometSupport [\#1072](https://github.com/Atmosphere/atmosphere/issues/1072)

- GuiceManagedAtmosphereServlet forces JerseyBroadcaster to be used if jersey annotations is used for Atmosphere [\#1071](https://github.com/Atmosphere/atmosphere/issues/1071)

- \[DefaultAnnotationProcessor\] Possible memory leak/problem with 1.1.0.RC2 [\#1070](https://github.com/Atmosphere/atmosphere/issues/1070)

- \[@Subscribe\] Allow configuring the timeout [\#1069](https://github.com/Atmosphere/atmosphere/issues/1069)

- AtmosphereInterceptor must be able to skip AtmosphereHandler but not AtmosphereInterceptor's chain [\#1067](https://github.com/Atmosphere/atmosphere/issues/1067)

- Add a new AtmosphereServlet30 only for 3.x ServletContainer [\#1066](https://github.com/Atmosphere/atmosphere/issues/1066)

- "No AtmosphereHandler maps request for null" with Atmosphere 1.1.0-SNAPSHOT and portal-java [\#1056](https://github.com/Atmosphere/atmosphere/issues/1056)

- \[BroadcasterCache\] Add an API to exclude/include AtmosphereResource from getting the cache [\#1051](https://github.com/Atmosphere/atmosphere/issues/1051)

- IE8 fails to receive initial streaming data if data is small [\#1019](https://github.com/Atmosphere/atmosphere/issues/1019)

- Tomcat comet + session support fails to restore old session value on cancel/resume [\#950](https://github.com/Atmosphere/atmosphere/issues/950)

- Add support for Tomcat/Servlet 3 only. [\#718](https://github.com/Atmosphere/atmosphere/issues/718)

- \[jquery\] opera - websocket 2 min chars [\#549](https://github.com/Atmosphere/atmosphere/issues/549)

- \[jquery\] safari - websocket 8 min chars [\#548](https://github.com/Atmosphere/atmosphere/issues/548)

- Add support for GlassFish's WebSocket + Servlet 3.0 API [\#458](https://github.com/Atmosphere/atmosphere/issues/458)

**Merged pull requests:**

- change atmosphere-integration module name to same value as artefact name [\#1068](https://github.com/Atmosphere/atmosphere/pull/1068) ([gesellix](https://github.com/gesellix))

## [atmosphere-project-1.1.0.RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.RC2) (2013-05-03)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.RC1...atmosphere-project-1.1.0.RC2)

**Closed issues:**

- Add an interceptor for limiting onSuspend call to once for long-polling [\#1065](https://github.com/Atmosphere/atmosphere/issues/1065)

- \[runtime\] Add AtmosphereResourceEvent.isClosedByClient\(\) [\#1064](https://github.com/Atmosphere/atmosphere/issues/1064)

- \[websocket\] resource.getResponse\(\).getCharacterEncoding\(\)  return null [\#1063](https://github.com/Atmosphere/atmosphere/issues/1063)

- long polling issue while working with IE8 and IE9 [\#1062](https://github.com/Atmosphere/atmosphere/issues/1062)

- Atmosphere 1.0.13 \(and PrimeFaces Push\) is not CDI compliant [\#1060](https://github.com/Atmosphere/atmosphere/issues/1060)

- \[atmosphere.js\] onOpen must not get called before enableProtocol: true happended [\#1059](https://github.com/Atmosphere/atmosphere/issues/1059)

- Atmosphere 1.0.13 snapshot \(2013-04-26\) says, support Out Of Order Broadcast: false [\#1057](https://github.com/Atmosphere/atmosphere/issues/1057)

- org.atmosphere.gwt20.AtmosphereGwt20 not found on GWT client? [\#1054](https://github.com/Atmosphere/atmosphere/issues/1054)

- \[deploy/undeploy\] Atmosphere 1.1.RC1 Memory leak on Tomcat 6 [\#1053](https://github.com/Atmosphere/atmosphere/issues/1053)

- java.lang.IllegalStateException: Request object no longer valid [\#1052](https://github.com/Atmosphere/atmosphere/issues/1052)

- Add an AtmosphereInterceptor for re-adding AtmosphereResource to all previous broadcaster [\#1050](https://github.com/Atmosphere/atmosphere/issues/1050)

- \[atmosphere.js\] Add support for onReconnect -\> onReopen workflow [\#1049](https://github.com/Atmosphere/atmosphere/issues/1049)

- \[atmosphere.js\] Document onXXX life cycle [\#1048](https://github.com/Atmosphere/atmosphere/issues/1048)

- \[atmosphere.js\] Opera fail to detect server going down [\#1047](https://github.com/Atmosphere/atmosphere/issues/1047)

- Jetty 8 websockets are used on Jetty 7 [\#1046](https://github.com/Atmosphere/atmosphere/issues/1046)

- atmosphere-jquery.war with minimized \(compressed\) version of jquery.js [\#1045](https://github.com/Atmosphere/atmosphere/issues/1045)

- Streaming unreliable on Opera in 1.0.12 [\#1044](https://github.com/Atmosphere/atmosphere/issues/1044)

- IOException with PrintWriter must be propagated to the Broadcaster [\#1043](https://github.com/Atmosphere/atmosphere/issues/1043)

- AtmosphereResourceEventListener.onDisconnect\(\) isn't called when client closes WebSocket connection [\#1039](https://github.com/Atmosphere/atmosphere/issues/1039)

- atmosphere-gwt-jackson.jar should be updated with correct service package name [\#1038](https://github.com/Atmosphere/atmosphere/issues/1038)

- client unsubscribe causes browser not responding [\#1036](https://github.com/Atmosphere/atmosphere/issues/1036)

- How to listen for incoming data using atmosphere-jersey [\#1033](https://github.com/Atmosphere/atmosphere/issues/1033)

- What do I have to update when changing from 1.1.0.beta3 to 1.1.0.RC1? [\#1032](https://github.com/Atmosphere/atmosphere/issues/1032)

- `Neither TrackMessageSizeInterceptor or TrackMessageSizeFilter are installed` warning when using Wicket's `org.apache.wicket.atmosphere.TrackMessageSizeFilter` [\#1031](https://github.com/Atmosphere/atmosphere/issues/1031)

- \[Tomcat BIO\] Thread Count Explosion [\#1028](https://github.com/Atmosphere/atmosphere/issues/1028)

- Streaming + trackMessageSize broken \(1.0.12\) [\#1027](https://github.com/Atmosphere/atmosphere/issues/1027)

- Setting org.atmosphere.websocket.maxIdleTime property closes the websocket connection as soon as it is opened. [\#1026](https://github.com/Atmosphere/atmosphere/issues/1026)

- Websocket max message size params ignored on Tomcat \(maxTextMessageSize and maxBinaryMessageSize config params\) [\#1025](https://github.com/Atmosphere/atmosphere/issues/1025)

- X-Atmosphere-tracking-id header is not set in 1.1.0.RC1 [\#1023](https://github.com/Atmosphere/atmosphere/issues/1023)

- Long polling fails in IPAD version 6.0.1 [\#1021](https://github.com/Atmosphere/atmosphere/issues/1021)

- \[streaming\] Reconnect logic must send the CLOSE signal [\#1020](https://github.com/Atmosphere/atmosphere/issues/1020)

- Content type is not set for initial streaming request  [\#1018](https://github.com/Atmosphere/atmosphere/issues/1018)

- \[IE\] Streaming issue with trackMessageLength [\#1017](https://github.com/Atmosphere/atmosphere/issues/1017)

- Missing static attribute BroadcasterLifeCyclePolicy.IDLE\_RESUME in BroadcasterLifeCyclePolicy [\#1016](https://github.com/Atmosphere/atmosphere/issues/1016)

- Document new Function in 1.1 [\#1015](https://github.com/Atmosphere/atmosphere/issues/1015)

- \[atmosphere.js\] invoke \_onError when fallbackTransport fail to reconnect [\#1014](https://github.com/Atmosphere/atmosphere/issues/1014)

- Return an error when WebSocket only application are used [\#1013](https://github.com/Atmosphere/atmosphere/issues/1013)

- Incorrect X-Atmosphere-tracking-id param when using Padding and Long-Polling  [\#1012](https://github.com/Atmosphere/atmosphere/issues/1012)

- Investigate Atmosphere+WS support on GlassFish 4.   [\#1011](https://github.com/Atmosphere/atmosphere/issues/1011)

- \[jquery.atmosphere.js\] problem using streaming transport in IE6 with trackMessageLength [\#1010](https://github.com/Atmosphere/atmosphere/issues/1010)

- jquery client separate getStatus & getStatusText [\#1009](https://github.com/Atmosphere/atmosphere/issues/1009)

- can not send message that contains "|"\(messageDelimiter\). [\#1008](https://github.com/Atmosphere/atmosphere/issues/1008)

- 1.1.0.RC1 contains bugs which are not in 1.1.0.beta3 [\#1007](https://github.com/Atmosphere/atmosphere/issues/1007)

- Timing issue in DefaultBroadcaster causes cached message to be missed [\#1003](https://github.com/Atmosphere/atmosphere/issues/1003)

- java.lang.NullPointerException at org.apache.wicket.markup.html.form.Form.onComponentTag\(Form.java:1520\) during Atmosphere eventbus.post\(\) if using WebSockets [\#1002](https://github.com/Atmosphere/atmosphere/issues/1002)

- IllegalStateException thrown after Session was destroyed [\#1001](https://github.com/Atmosphere/atmosphere/issues/1001)

- Atmosphere should send X-Accel-Buffering: no [\#1000](https://github.com/Atmosphere/atmosphere/issues/1000)

- \[atmosphere.js\] enableProtocol = true, trackMessageSize = true and TrackMessageFilter issue [\#993](https://github.com/Atmosphere/atmosphere/issues/993)

- \[jquery.atmosphere.js\] long-polling does not work if set maxReconnectOnClose=0. [\#992](https://github.com/Atmosphere/atmosphere/issues/992)

- ClassCastException on CometEvent [\#991](https://github.com/Atmosphere/atmosphere/issues/991)

- Javadoc: ApplicationConfig.DISABLE\_ATMOSPHEREINTERCEPTOR  has an incorrect package [\#987](https://github.com/Atmosphere/atmosphere/issues/987)

- \[Filter not called when message are cached and retrieved\] Cast Exception when DefaultBroadcaster tries to send a cached message [\#986](https://github.com/Atmosphere/atmosphere/issues/986)

- Shield the OnDisconnect AtmosphereIntercetor from connection close [\#983](https://github.com/Atmosphere/atmosphere/issues/983)

- \[hazelcast\] HazelcastBroadcaster must invokes parent.destroy\(\) [\#982](https://github.com/Atmosphere/atmosphere/issues/982)

- Possible NPE with WebSocket encoding [\#981](https://github.com/Atmosphere/atmosphere/issues/981)

- Other clients \(then localhost\) are not receiving broadcasted messages before server shutdown [\#979](https://github.com/Atmosphere/atmosphere/issues/979)

- \[Jetty 9/mvn jetty-run issue\] Atmosphere 1.1.0.RC1 throws ClassNotFoundException: org.eclipse.jetty.continuation.ContinuationListener on Jetty 9.0.0.v20130308 [\#978](https://github.com/Atmosphere/atmosphere/issues/978)

- Error: WebSocket is closed before the connection is established. [\#977](https://github.com/Atmosphere/atmosphere/issues/977)

- \[atmosphere.js\] Better onXXX lifecycle implementation in 1.1 [\#976](https://github.com/Atmosphere/atmosphere/issues/976)

- \[logger\] Possible java.util.ConcurrentModificationException [\#975](https://github.com/Atmosphere/atmosphere/issues/975)

- if org.atmosphere.cpr.Broadcaster.supportOutOfOrderBroadcast set 'true', atmosphere don't broadcast. [\#974](https://github.com/Atmosphere/atmosphere/issues/974)

- \[websocket\] DefaultWebSocketProcessor must suspend the resource before invoking onOpen [\#973](https://github.com/Atmosphere/atmosphere/issues/973)

- High CPU usage in Firefox browser \(19.0.2\) running WebSocket \(1.0.11\) in jetty 8 server [\#964](https://github.com/Atmosphere/atmosphere/issues/964)

- Firefox Offline Mode Reconnect Issues [\#962](https://github.com/Atmosphere/atmosphere/issues/962)

- Backport Jetty 9 support in 1.0.x [\#952](https://github.com/Atmosphere/atmosphere/issues/952)

- onDisconnect must not be called when resuming or timing out [\#948](https://github.com/Atmosphere/atmosphere/issues/948)

- Add documentation for readResponseHeaders [\#941](https://github.com/Atmosphere/atmosphere/issues/941)

- HazelcastBroadcaster will broadcast two times when using long-polling. [\#934](https://github.com/Atmosphere/atmosphere/issues/934)

- \[HeaderBroadcasterCache\] multiple broadcast in a post request will lose message with long-polling. [\#923](https://github.com/Atmosphere/atmosphere/issues/923)

- \[atmosphere.js\] Reset failure counter when reconect was successfull [\#908](https://github.com/Atmosphere/atmosphere/issues/908)

- Redesign BroadcasterCache API [\#865](https://github.com/Atmosphere/atmosphere/issues/865)

- \[atmosphere/portal.js\] long-polling reconnect forever [\#771](https://github.com/Atmosphere/atmosphere/issues/771)

- Add Annotation to add Notification in BroadcasterCache when a Broadcaster is added are removed [\#752](https://github.com/Atmosphere/atmosphere/issues/752)

- \[runtime\] add option to configure default broadcaster suspendPolicy through xml [\#701](https://github.com/Atmosphere/atmosphere/issues/701)

- \[jquery\] when websocket is reconnected I have no event fired [\#529](https://github.com/Atmosphere/atmosphere/issues/529)

**Merged pull requests:**

-  ApplicationConfig: Made the javadoc more consistent. [\#1041](https://github.com/Atmosphere/atmosphere/pull/1041) ([slovdahl](https://github.com/slovdahl))

- Allow disabling the Commercial Support message [\#1040](https://github.com/Atmosphere/atmosphere/pull/1040) ([Legioth](https://github.com/Legioth))

- fix bug: atmosphere can not send message that contains message delimiter [\#1037](https://github.com/Atmosphere/atmosphere/pull/1037) ([freedom1989](https://github.com/freedom1989))

- fix bug: message body can not contains message delimiter [\#1022](https://github.com/Atmosphere/atmosphere/pull/1022) ([freedom1989](https://github.com/freedom1989))

- Typo fix for NginxInterceptor \#1000 :\) [\#1006](https://github.com/Atmosphere/atmosphere/pull/1006) ([ceefour](https://github.com/ceefour))

- Typo fix for NginxInterceptor :\) [\#1005](https://github.com/Atmosphere/atmosphere/pull/1005) ([ceefour](https://github.com/ceefour))

- Introduce explicit contentLength property on the AtmosphereRequest.Builder [\#999](https://github.com/Atmosphere/atmosphere/pull/999) ([thabach](https://github.com/thabach))

- Fix for websocket fallback issue \#997 [\#998](https://github.com/Atmosphere/atmosphere/pull/998) ([R2R](https://github.com/R2R))

- Typo in javadoc fixed [\#996](https://github.com/Atmosphere/atmosphere/pull/996) ([slovdahl](https://github.com/slovdahl))

- Broadcaster instantiated before annotations processed [\#994](https://github.com/Atmosphere/atmosphere/pull/994) ([joshuaali](https://github.com/joshuaali))

- Ensure resource aliveness before accessing request object [\#988](https://github.com/Atmosphere/atmosphere/pull/988) ([markathomas](https://github.com/markathomas))

- adjusted javadoc regeneration script to take new stylesheet into account [\#985](https://github.com/Atmosphere/atmosphere/pull/985) ([staabm](https://github.com/staabm))

- added adjusted javadoc stylesheet.css [\#984](https://github.com/Atmosphere/atmosphere/pull/984) ([staabm](https://github.com/staabm))

## [atmosphere-project-1.1.0.RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.RC1) (2013-03-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.12...atmosphere-project-1.1.0.RC1)

**Closed issues:**

- NPE when trying to looking a Broadcaster from WebSocketHandler\#onOpen [\#972](https://github.com/Atmosphere/atmosphere/issues/972)

- \[websocket\] webSocket.onClose must allow retrieval of request's attribute [\#971](https://github.com/Atmosphere/atmosphere/issues/971)

- Channels concept in Atmosphere [\#970](https://github.com/Atmosphere/atmosphere/issues/970)

- \[Internet Explorer\] trackMessageLength doesn't work with streaming [\#969](https://github.com/Atmosphere/atmosphere/issues/969)

- \[atmosphere.js\] OnDisconnect event must be sent before closing the request [\#967](https://github.com/Atmosphere/atmosphere/issues/967)

- \[sse\] Uncaught Error: SecurityError: DOM Exception 18  [\#966](https://github.com/Atmosphere/atmosphere/issues/966)

- Backport support for packages scanning with annotations [\#965](https://github.com/Atmosphere/atmosphere/issues/965)

- client-side onError\(\) function is not fired as expected when the client can not connect the server. [\#963](https://github.com/Atmosphere/atmosphere/issues/963)

- \[runtime\] - 1.0.13 cached messages causes streaming to disconnect [\#961](https://github.com/Atmosphere/atmosphere/issues/961)

- jquery-pubsub sample not working on 1.0.12  [\#960](https://github.com/Atmosphere/atmosphere/issues/960)

- \[runtime\] - 1.0.13-SNAPSHOT thread leak [\#959](https://github.com/Atmosphere/atmosphere/issues/959)

- \[runtime\] - messages.offer\(\) not working anymore [\#958](https://github.com/Atmosphere/atmosphere/issues/958)

- \[runtime\] - deadlock [\#957](https://github.com/Atmosphere/atmosphere/issues/957)

- \[atmosphere.js\] Prevent delivering empty message when long-polling connection is resumed [\#956](https://github.com/Atmosphere/atmosphere/issues/956)

- \[atmosphere.js\] Duplicate message when the long-polling connection is resumed and trackMessageLength is used [\#955](https://github.com/Atmosphere/atmosphere/issues/955)

- \[atmosphere.js\] Possible NaN error with message delimiter and transport 'polling' [\#954](https://github.com/Atmosphere/atmosphere/issues/954)

- Corrections to CorsFilter [\#951](https://github.com/Atmosphere/atmosphere/issues/951)

- encodeURL and encodeRedirectURL return null [\#949](https://github.com/Atmosphere/atmosphere/issues/949)

- Deadlock with Jetty9-Websocket [\#945](https://github.com/Atmosphere/atmosphere/issues/945)

-  AtmosphereFramework init handling parameters too late [\#819](https://github.com/Atmosphere/atmosphere/issues/819)

**Merged pull requests:**

- Update  Grizzly2WebSocketSupport to support http://java.net/jira/browse/GRIZZLY-1438 [\#947](https://github.com/Atmosphere/atmosphere/pull/947) ([dermarens](https://github.com/dermarens))

- Partial update from 1.0 to 1.1.0beta4 [\#946](https://github.com/Atmosphere/atmosphere/pull/946) ([flowersinthesand](https://github.com/flowersinthesand))

## [atmosphere-project-1.0.12](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.12) (2013-03-01)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.11...atmosphere-project-1.0.12)

**Closed issues:**

- \[atmosphere.js\] when unsubscribe , clearTimeout problem [\#944](https://github.com/Atmosphere/atmosphere/issues/944)

- Characterset is not used on IE 8 & 9 sending message from client to server [\#943](https://github.com/Atmosphere/atmosphere/issues/943)

- \[atmosphere.js\] when unsubscribe, if request url contains search key, push close event will cause problems  [\#942](https://github.com/Atmosphere/atmosphere/issues/942)

- \[runtime\] Encoding issue [\#940](https://github.com/Atmosphere/atmosphere/issues/940)

- \[websocket\] AtmosphereResource associated WebSocket's Message needs to be removed from Broadcaster [\#938](https://github.com/Atmosphere/atmosphere/issues/938)

- \[atmosphere.js\] WebSocket timeout only honored once [\#937](https://github.com/Atmosphere/atmosphere/issues/937)

- \[atmosphere.js\] onOpen is called twice when using long-polling [\#936](https://github.com/Atmosphere/atmosphere/issues/936)

- \[atmosphere.js\] IE9 aborted connection [\#935](https://github.com/Atmosphere/atmosphere/issues/935)

- SimpleBroadcaster with UUIDBroadcasterCache can not be used in version 1.0.11 [\#933](https://github.com/Atmosphere/atmosphere/issues/933)

- infinite Loop in Atmosphere Servlet [\#932](https://github.com/Atmosphere/atmosphere/issues/932)

- Not able to received data on suspended connection when using AtmosphereInterceptor [\#931](https://github.com/Atmosphere/atmosphere/issues/931)

- TrackMessageSizeFilter not working properly when using AFTER\_FILTER [\#930](https://github.com/Atmosphere/atmosphere/issues/930)

- unsubscribeUrl does not invoke X-Atmosphere-Transport=close for enableProtocol=true and resource is not removed from broadcaster [\#928](https://github.com/Atmosphere/atmosphere/issues/928)

**Merged pull requests:**

- fixing broken build [\#939](https://github.com/Atmosphere/atmosphere/pull/939) ([rlfnb](https://github.com/rlfnb))

## [atmosphere-project-1.0.11](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.11) (2013-02-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.10...atmosphere-project-1.0.11)

**Closed issues:**

- \[atmosphere.js\] Prevent onClose called twice when unsubscribe [\#927](https://github.com/Atmosphere/atmosphere/issues/927)

- \[atmosphere.js\] Strange behavior when push is called from onClose [\#926](https://github.com/Atmosphere/atmosphere/issues/926)

- RedisBroadcaster configuration - cluster [\#924](https://github.com/Atmosphere/atmosphere/issues/924)

- Update Hazelcast version and code [\#922](https://github.com/Atmosphere/atmosphere/issues/922)

- \[atmosphere.js\] function resolution issue [\#921](https://github.com/Atmosphere/atmosphere/issues/921)

- \[Tomcat + GWT + Websocket\] Comet event shouldn't spin infinitely [\#920](https://github.com/Atmosphere/atmosphere/issues/920)

- \[Tomcat7\] Atmosphere Websocket handler should overwrite default outbound buffer size [\#919](https://github.com/Atmosphere/atmosphere/issues/919)

- Redis Shared Pool being closed/destroyed when one of the broadcasters is destroyed [\#918](https://github.com/Atmosphere/atmosphere/issues/918)

- \[DefaultBroadcaster\] When one Broadcaster per connection is used, reduce at the minimum the number of thread used [\#917](https://github.com/Atmosphere/atmosphere/issues/917)

- Issue with long-polling and broadcast multiple times [\#916](https://github.com/Atmosphere/atmosphere/issues/916)

- \[atmosphere.js\] enableProtocol = true and streaming issue with Meteor [\#915](https://github.com/Atmosphere/atmosphere/issues/915)

- \[sample\] Using an distant redis server doesn't work [\#913](https://github.com/Atmosphere/atmosphere/issues/913)

- Embedded Usage with Jetty and Close / Disconnect Events [\#911](https://github.com/Atmosphere/atmosphere/issues/911)

- \[regression\] BroadcasterLifecyclePolicy doesn't work when using SimpleBroadcaster [\#910](https://github.com/Atmosphere/atmosphere/issues/910)

- native socket.io demo doesn't use websocket if nio configured in Tomcat 7 [\#885](https://github.com/Atmosphere/atmosphere/issues/885)

- NPE at InternalNioOutputBuffer.addToBB\(\) in Tomcat 7 [\#874](https://github.com/Atmosphere/atmosphere/issues/874)

- \[jQuery\] streaming onOpen/OnReconnect not fired after reconnect [\#550](https://github.com/Atmosphere/atmosphere/issues/550)

- RFE: Wait for the connection estabilished event before sending any message to the server for each request. [\#495](https://github.com/Atmosphere/atmosphere/issues/495)

- APR Connector on win7/tomcat doesn't seem to support websockets [\#435](https://github.com/Atmosphere/atmosphere/issues/435)

- \[socketio\] Re-add SocketIO Unit Test [\#394](https://github.com/Atmosphere/atmosphere/issues/394)

- \[sample\] 404 when index.html is appended to the URL [\#310](https://github.com/Atmosphere/atmosphere/issues/310)

**Merged pull requests:**

- tiny jquery tweaks [\#925](https://github.com/Atmosphere/atmosphere/pull/925) ([casualjim](https://github.com/casualjim))

## [atmosphere-project-1.0.10](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.10) (2013-02-15)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.beta3...atmosphere-project-1.0.10)

**Closed issues:**

- INFO: Using shared ExecutorServices amongst all Atmosphere components: Broadcaster ... [\#909](https://github.com/Atmosphere/atmosphere/issues/909)

- \[atmosphere.js\] request.reconnect == false should at least execute one request [\#907](https://github.com/Atmosphere/atmosphere/issues/907)

- \[asynchronous\] Add support for content-type on suspend. [\#906](https://github.com/Atmosphere/atmosphere/issues/906)

- \[Broadcaster\] Enabled Shared Thread Pool by Default [\#903](https://github.com/Atmosphere/atmosphere/issues/903)

- \[Tomcat\] Possible Denial of Service with WebSocket [\#902](https://github.com/Atmosphere/atmosphere/issues/902)

- When Broadcaster is empty, perRequestFilter must always be executed for BEFORE and AFTER strategy [\#901](https://github.com/Atmosphere/atmosphere/issues/901)

- Duplicate Filter Execution [\#899](https://github.com/Atmosphere/atmosphere/issues/899)

- \[websockets\] The ResourceConfig instance does not contain any root resource classes [\#897](https://github.com/Atmosphere/atmosphere/issues/897)

- Wrong error/warning message on TrackMessageSizeFilter [\#896](https://github.com/Atmosphere/atmosphere/issues/896)

- sse with jQuery.atmosphere and https [\#895](https://github.com/Atmosphere/atmosphere/issues/895)

- Broadcaster can locks if the I/O layer block [\#894](https://github.com/Atmosphere/atmosphere/issues/894)

- \[atmosphere.js\] WebSocket reconnect using the wrong timeout [\#893](https://github.com/Atmosphere/atmosphere/issues/893)

- wss downgrades to comet [\#892](https://github.com/Atmosphere/atmosphere/issues/892)

- Tilde character ~ in path causes error [\#891](https://github.com/Atmosphere/atmosphere/issues/891)

- Add support for write timeout with Broadcaster [\#890](https://github.com/Atmosphere/atmosphere/issues/890)

- \[Tomcat\] Add timeout support for WebSocket to prevent thread waiting indefinitely. [\#888](https://github.com/Atmosphere/atmosphere/issues/888)

- testEmptyBroadcastMethod\(org.atmosphere.cpr.BroadcasterTest\) FAILED [\#886](https://github.com/Atmosphere/atmosphere/issues/886)

- Should serializers close or flush their OutputStream? [\#884](https://github.com/Atmosphere/atmosphere/issues/884)

- Recover from an IOException when a message is found from the cache [\#883](https://github.com/Atmosphere/atmosphere/issues/883)

- Enforce same origin policy in TomcatWebSocketUtil [\#880](https://github.com/Atmosphere/atmosphere/issues/880)

- \[atmosphere.js\] Long-Polling reconnect twice on timeout [\#879](https://github.com/Atmosphere/atmosphere/issues/879)

- checkMessageLength fails on Firefox when xml has trailing carriage return [\#878](https://github.com/Atmosphere/atmosphere/issues/878)

- \[jersey\] Make @Broadcast asynchronous [\#877](https://github.com/Atmosphere/atmosphere/issues/877)

- EventCacheBroadcasterCache should use System.currentTimeMillis instead of System.nanoTime [\#876](https://github.com/Atmosphere/atmosphere/issues/876)

- Heap dump while running Atmosphere \(1.0.9\) with TomEE 1.5.2-SNAPSHOT \(20130128 version which has Tomcat 7.0.35 dependency\) [\#875](https://github.com/Atmosphere/atmosphere/issues/875)

- Logging message [\#863](https://github.com/Atmosphere/atmosphere/issues/863)

- \[atmosphere.js\] Cannot set client request timeout and get WebSocket error [\#860](https://github.com/Atmosphere/atmosphere/issues/860)

- \[tomcat\]\[grails\]Threads get suck writing and BLOCK the Broadcaster [\#849](https://github.com/Atmosphere/atmosphere/issues/849)

- \[jquery\]\[streaming\] - Atmosphere crashes ie10 on windows 7 [\#848](https://github.com/Atmosphere/atmosphere/issues/848)

- \[jQuery\] cannot close/abort connection in IE 9 [\#829](https://github.com/Atmosphere/atmosphere/issues/829)

- messages lost with SessionBroadcasterCache on broadcast [\#743](https://github.com/Atmosphere/atmosphere/issues/743)

- Last message rebroadcast when using WS w/maxInactiveActivity & BroadcastCache [\#706](https://github.com/Atmosphere/atmosphere/issues/706)

- Executor of all type must be shareable amongst all components  [\#645](https://github.com/Atmosphere/atmosphere/issues/645)

- \[NettoSphere\] Unexpected behavior with suspend\(\) / resume\(\) [\#633](https://github.com/Atmosphere/atmosphere/issues/633)

- \[NettoSphere\] AtmosphereResource.suspend\(timeout, true\) results in unpredictable channel closing [\#620](https://github.com/Atmosphere/atmosphere/issues/620)

- \[jQuery\] streaming,network disconnected abruptly, disconnect not fired [\#604](https://github.com/Atmosphere/atmosphere/issues/604)

- onDisconnect is not called in firefox for sse transport until firefox is fully closed [\#565](https://github.com/Atmosphere/atmosphere/issues/565)

- \[tomcat\] org.atmosphere.websocket.maxIdleTime - ignored [\#524](https://github.com/Atmosphere/atmosphere/issues/524)

- Add support for OnDisconnectAtmosphereInterceptor [\#523](https://github.com/Atmosphere/atmosphere/issues/523)

- BroadcasterCache json response bad [\#514](https://github.com/Atmosphere/atmosphere/issues/514)

- \[jetty\] WebSocketHandshakeFilter must force underlying Websocket to close [\#471](https://github.com/Atmosphere/atmosphere/issues/471)

- Websocket transport can not handle large data greater than default TCP buffer [\#282](https://github.com/Atmosphere/atmosphere/issues/282)

**Merged pull requests:**

- Get protocol from window.location for sse origin check. [\#898](https://github.com/Atmosphere/atmosphere/pull/898) ([beise](https://github.com/beise))

- Fix for \#880 Optional same origin policy with real implementation [\#882](https://github.com/Atmosphere/atmosphere/pull/882) ([toddwest](https://github.com/toddwest))

- Optional same origin policy with real implementation [\#881](https://github.com/Atmosphere/atmosphere/pull/881) ([toddwest](https://github.com/toddwest))

## [atmosphere-project-1.1.0.beta3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.beta3) (2013-01-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.9...atmosphere-project-1.1.0.beta3)

**Closed issues:**

- Inability to use WebLogic native support without providing Tomcat, JBossWeb, etc. libs [\#873](https://github.com/Atmosphere/atmosphere/issues/873)

- PrimeFaces.widget.Socket = ignore configuration [\#872](https://github.com/Atmosphere/atmosphere/issues/872)

- \[atmosphere.js\] Bump to jQuery 1.9.0 [\#871](https://github.com/Atmosphere/atmosphere/issues/871)

- Guice support broken with Jersey 1.14 [\#870](https://github.com/Atmosphere/atmosphere/issues/870)

- java.lang.NumberFormatException: For input string: "\(TomEE\)/7" [\#869](https://github.com/Atmosphere/atmosphere/issues/869)

- Uncomment BroadcasterCacheTest [\#868](https://github.com/Atmosphere/atmosphere/issues/868)

- NPE at AtmosphereRequest.setAttribute\(AtmosphereRequest.java:541\) [\#866](https://github.com/Atmosphere/atmosphere/issues/866)

- \[cache\] EventCacheBroadcasterCache still miss some messages, need a new API [\#864](https://github.com/Atmosphere/atmosphere/issues/864)

- \[glassfish\] Cancelling the connection with streaming. [\#862](https://github.com/Atmosphere/atmosphere/issues/862)

- Tomcat, Servlet 3: The request associated with the AsyncContext has already completed processing. [\#858](https://github.com/Atmosphere/atmosphere/issues/858)

- jquery.atmosphere.js does not support websockets with binary frames [\#855](https://github.com/Atmosphere/atmosphere/issues/855)

- Broken Pipe even with org.atmosphere.cpr.CometSupport.maxInactiveActivity if server broadcast events in intervalls < maxInactiveActivity [\#854](https://github.com/Atmosphere/atmosphere/issues/854)

- Cache messages as soon as we know an AtmosphereResource has been resumed [\#853](https://github.com/Atmosphere/atmosphere/issues/853)

- \[BroadcasterCache\] Ensure the cache is delivered in the order it was populated [\#852](https://github.com/Atmosphere/atmosphere/issues/852)

- Messages are delivered not in order they get broadcasted [\#851](https://github.com/Atmosphere/atmosphere/issues/851)

- Upgrade Jetty 9 support to 9.0.0.M5 [\#850](https://github.com/Atmosphere/atmosphere/issues/850)

- Mix od Broadcast filter and PerRequestBroadcastFilter produces wrong messages [\#847](https://github.com/Atmosphere/atmosphere/issues/847)

- \[firefox\]\[glassfish\] When websocket not enabled, firefox doesn't automatically close the connection [\#846](https://github.com/Atmosphere/atmosphere/issues/846)

- \[glassfish\] WebSocket behaves strangely when timing out WebSocket [\#845](https://github.com/Atmosphere/atmosphere/issues/845)

- \[atmosphere.js\] Always try to reconnect when receiving a 503 [\#844](https://github.com/Atmosphere/atmosphere/issues/844)

- TrackMessageSizeFilter perform the calculation on the original message instead of the transformed one [\#843](https://github.com/Atmosphere/atmosphere/issues/843)

- TrackMessageSizeFilter perform the calculation on the original message instead of the transformed one [\#842](https://github.com/Atmosphere/atmosphere/issues/842)

- BroadcasterFactory issues with WebFragment/multiple AtmosphereServlet or Injection [\#841](https://github.com/Atmosphere/atmosphere/issues/841)

- \[Glassfish\] NPE on connection times out [\#840](https://github.com/Atmosphere/atmosphere/issues/840)

- jQuery.browser is deprecated and removed in jQuery 1.9 [\#839](https://github.com/Atmosphere/atmosphere/issues/839)

- OnMessage handler must handles BroadcasterCache's ArrayList [\#838](https://github.com/Atmosphere/atmosphere/issues/838)

- NPE failed to timeout resource null [\#837](https://github.com/Atmosphere/atmosphere/issues/837)

- MetaBroadcaster won't find broadcasters with @ in the name [\#836](https://github.com/Atmosphere/atmosphere/issues/836)

- dispatchUrl unused by websocket push [\#835](https://github.com/Atmosphere/atmosphere/issues/835)

- \[http\] Gives a chance to the connection to be established before calling the callback [\#833](https://github.com/Atmosphere/atmosphere/issues/833)

- \[broadcasterCache\] Add a new cache that only retrieve messages when reconnecting [\#832](https://github.com/Atmosphere/atmosphere/issues/832)

- \[websocket\] Push the uuid and the server timestamp as the first message so it get set [\#831](https://github.com/Atmosphere/atmosphere/issues/831)

- \[websocket\] When used with the org.atmosphere.cpr.CometSupport.maxInactiveActivity mechanism, must reset the timestamp [\#830](https://github.com/Atmosphere/atmosphere/issues/830)

- \[GlassFish\] Wrong WebSocket error code on browser close [\#828](https://github.com/Atmosphere/atmosphere/issues/828)

- Log an exception when a broken BroadcastFilter throw an exception [\#827](https://github.com/Atmosphere/atmosphere/issues/827)

- Expose BroadcasterCache STATEGY API to DefaultBroadcaster [\#826](https://github.com/Atmosphere/atmosphere/issues/826)

- DefaultBroadcaster is caching it's object instead of the message [\#825](https://github.com/Atmosphere/atmosphere/issues/825)

- Port BroadcasterCache's PerRequest filter code to 1.1.x [\#824](https://github.com/Atmosphere/atmosphere/issues/824)

- \[GlassFish\] Two AtmosphereHandler will maps to the same GlassFishWebSocketHandler [\#823](https://github.com/Atmosphere/atmosphere/issues/823)

- \[GlassFish\] NPE on shutdown [\#822](https://github.com/Atmosphere/atmosphere/issues/822)

- \[GlassFish\] GlassFishWebSocketHandler needs to be stateless as it cause thread race [\#821](https://github.com/Atmosphere/atmosphere/issues/821)

- onOpen\(\) callback function in jquery.atmospehere.js does not fire properly when using long-polling [\#816](https://github.com/Atmosphere/atmosphere/issues/816)

- \[runtime\] - atmosphere switches to BIO when exception occurs [\#813](https://github.com/Atmosphere/atmosphere/issues/813)

- jquery long-polling with trackMessageLength bug [\#775](https://github.com/Atmosphere/atmosphere/issues/775)

- TrackMessageSizeInterceptor: Allow for delimiter character in message content [\#756](https://github.com/Atmosphere/atmosphere/issues/756)

- \[jQuery\]\[long-polling\]\[XDR\] IE9 does not reissue GET after receiving a message [\#750](https://github.com/Atmosphere/atmosphere/issues/750)

- Add support for interface free component in Atmosphere [\#705](https://github.com/Atmosphere/atmosphere/issues/705)

- Add semi protocol negotiation to the atmosphere.js  [\#680](https://github.com/Atmosphere/atmosphere/issues/680)

- cacheLostMessage breaks broadcaster cache [\#658](https://github.com/Atmosphere/atmosphere/issues/658)

- Document enableXDR and it's limitation [\#636](https://github.com/Atmosphere/atmosphere/issues/636)

- \[runtime\] JSONP Transport doesn't work with BroadcasterCache [\#249](https://github.com/Atmosphere/atmosphere/issues/249)

**Merged pull requests:**

- Fix stack overflow on page reload in IE [\#861](https://github.com/Atmosphere/atmosphere/pull/861) ([nite23](https://github.com/nite23))

- Patch to enable passing instances of Servlets and Filters to MeteorServlet. [\#859](https://github.com/Atmosphere/atmosphere/pull/859) ([lukiano](https://github.com/lukiano))

- Changes to jquery.atmosphere.js in order to suppoer websockets with binary frames [\#856](https://github.com/Atmosphere/atmosphere/pull/856) ([aleksandarn](https://github.com/aleksandarn))

- Base64 encoding interceptor, as outlined in Issue \#756 [\#820](https://github.com/Atmosphere/atmosphere/pull/820) ([nite23](https://github.com/nite23))

## [atmosphere-project-1.0.9](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.9) (2013-01-15)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.8...atmosphere-project-1.0.9)

**Closed issues:**

- \[api\] Allow an AtmosphereResource to close a WebSocket connection [\#817](https://github.com/Atmosphere/atmosphere/issues/817)

- onResume and onDisconnect are not fired with WebSocket connection. [\#814](https://github.com/Atmosphere/atmosphere/issues/814)

- NPE from Wicket Atmospere support when shutting down Tomcat [\#812](https://github.com/Atmosphere/atmosphere/issues/812)

- socket.io sample not working [\#789](https://github.com/Atmosphere/atmosphere/issues/789)

- send max reconnect reached error code or event [\#774](https://github.com/Atmosphere/atmosphere/issues/774)

- Using atmosphere causes Glassfish requests to hang, only over SSL. [\#770](https://github.com/Atmosphere/atmosphere/issues/770)

- \[runtime\] thread leak in DefaultBroadcasterFactory [\#766](https://github.com/Atmosphere/atmosphere/issues/766)

- \[runtime\] , MetaBroadcaster regex issue [\#693](https://github.com/Atmosphere/atmosphere/issues/693)

- Scan dependencies or other locations for annotations [\#614](https://github.com/Atmosphere/atmosphere/issues/614)

- \[RFE\] Introduce maxRetryAttempts property instead of maxRequest  [\#589](https://github.com/Atmosphere/atmosphere/issues/589)

- Comet connection lost in IE8 when clicking a hash link [\#465](https://github.com/Atmosphere/atmosphere/issues/465)

- \[JBossWeb\] error when refreshing page with wicket servlet with tcnative-1.dll [\#374](https://github.com/Atmosphere/atmosphere/issues/374)

- Enhancement : transport\(\) could support custom transport [\#328](https://github.com/Atmosphere/atmosphere/issues/328)

- \[websocket\]\[atmosphere.js\] Not able to change request timeout \(client side\) [\#291](https://github.com/Atmosphere/atmosphere/issues/291)

**Merged pull requests:**

- Fix for \#766 [\#815](https://github.com/Atmosphere/atmosphere/pull/815) ([nite23](https://github.com/nite23))

## [atmosphere-project-1.0.8](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.8) (2013-01-09)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.7...atmosphere-project-1.0.8)

**Closed issues:**

- \[long-polling\] AbstractReflectorAtmosphereHandler does not resume on broadcast [\#806](https://github.com/Atmosphere/atmosphere/issues/806)

- AtmosphereRequest.queryString\(Sring\) method seems to be bug\(values size\) [\#805](https://github.com/Atmosphere/atmosphere/issues/805)

- \[JbossWeb\] Websocket protocol not supported [\#804](https://github.com/Atmosphere/atmosphere/issues/804)

- \[atmosphere.js\] Sharing tabs timeout issue [\#803](https://github.com/Atmosphere/atmosphere/issues/803)

- \[IE regression\] maxReconnectOnClose undefined [\#802](https://github.com/Atmosphere/atmosphere/issues/802)

- \[gwt\] unloadHandlerReg [\#801](https://github.com/Atmosphere/atmosphere/issues/801)

- Configure to suppres java.lang.IllegalStateException: The event object has been recycled and is no longer associated with a request [\#800](https://github.com/Atmosphere/atmosphere/issues/800)

- Atmosphere servlet fails to broadcast the response when configuring with apache web server [\#798](https://github.com/Atmosphere/atmosphere/issues/798)

- setCharacterEncoding method doesn't work in onPreSuspend method [\#797](https://github.com/Atmosphere/atmosphere/issues/797)

- GWT Comet over HTTPS on Chrome doesn't work [\#796](https://github.com/Atmosphere/atmosphere/issues/796)

- Improper GET parameter addition [\#795](https://github.com/Atmosphere/atmosphere/issues/795)

- resource.getRequest\(\).getAttribute\(...\) doesn't work for long-polling/IE/Tomcat 7 [\#794](https://github.com/Atmosphere/atmosphere/issues/794)

- Inject servlets and filters instantiated by ReflectorServletProcessor. [\#793](https://github.com/Atmosphere/atmosphere/issues/793)

- \[jQuery\] reconnectInterval has no effect on reissue request after timeout [\#792](https://github.com/Atmosphere/atmosphere/issues/792)

- Exception when using SimpleBroadcaster instead of the default. [\#791](https://github.com/Atmosphere/atmosphere/issues/791)

- Osgi problem Import-Package org.mortbay.util.ajax is not optional [\#790](https://github.com/Atmosphere/atmosphere/issues/790)

- Problem with HeaderBroadcasterCache and long polling [\#788](https://github.com/Atmosphere/atmosphere/issues/788)

- Unnecessary data being sent upon connecting to receive updates [\#787](https://github.com/Atmosphere/atmosphere/issues/787)

- Add annotation scanner dependencies by default [\#785](https://github.com/Atmosphere/atmosphere/issues/785)

- AbstractBroadcasterCache.getQueueDepth\(\) [\#783](https://github.com/Atmosphere/atmosphere/issues/783)

- NPE in DefaultBroadcaster.entryDone using SimpleBroadcaster or sub-class thereof [\#782](https://github.com/Atmosphere/atmosphere/issues/782)

- messageDelimiter in jquery.atmosphere.js [\#780](https://github.com/Atmosphere/atmosphere/issues/780)

- Intermittent failure IllegalStateException during @Suspend [\#772](https://github.com/Atmosphere/atmosphere/issues/772)

- \[Glassfish\] \[HTTPS\] Сonnection breaks every few seconds [\#744](https://github.com/Atmosphere/atmosphere/issues/744)

- Add WebSocket support for JBoss 7.x [\#362](https://github.com/Atmosphere/atmosphere/issues/362)

**Merged pull requests:**

- Fixed ConcurrentModification issues in AbstractBroadcasterCache [\#786](https://github.com/Atmosphere/atmosphere/pull/786) ([rs017991](https://github.com/rs017991))

- Added AbstractBroadcasterCache.getQueueDepth\(\) [\#784](https://github.com/Atmosphere/atmosphere/pull/784) ([rs017991](https://github.com/rs017991))

## [atmosphere-project-1.0.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.7) (2012-12-18)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.6...atmosphere-project-1.0.7)

**Closed issues:**

- \[SimpleBroadcaster\] NPE with latest 1.0.6 [\#781](https://github.com/Atmosphere/atmosphere/issues/781)

## [atmosphere-project-1.0.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.6) (2012-12-17)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.beta2...atmosphere-project-1.0.6)

## [atmosphere-project-1.1.0.beta2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.beta2) (2012-12-17)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.5...atmosphere-project-1.1.0.beta2)

**Closed issues:**

- \[performance\] Allow out of order broadcast delivery [\#779](https://github.com/Atmosphere/atmosphere/issues/779)

- Add support for unique AtmosphereResource UUID with WebSocket [\#778](https://github.com/Atmosphere/atmosphere/issues/778)

- Add Session's creation Interceptor [\#777](https://github.com/Atmosphere/atmosphere/issues/777)

- Resuming with Serializers [\#776](https://github.com/Atmosphere/atmosphere/issues/776)

- Atmosphere resources with the same uuid [\#773](https://github.com/Atmosphere/atmosphere/issues/773)

- Add .zip distribution with all dependencies [\#769](https://github.com/Atmosphere/atmosphere/issues/769)

- Intermittent InterruptedException destroying Broadcaster [\#768](https://github.com/Atmosphere/atmosphere/issues/768)

- Ruby client [\#767](https://github.com/Atmosphere/atmosphere/issues/767)

- \[runtime\] DefaultBroadcaster hold threads under load. [\#765](https://github.com/Atmosphere/atmosphere/issues/765)

- comet not working with tomcat7 and atmos 1.0.5 [\#764](https://github.com/Atmosphere/atmosphere/issues/764)

- 404 url results in infinite requests to the server [\#763](https://github.com/Atmosphere/atmosphere/issues/763)

- Failed using comet support with tomcat NIO connector [\#762](https://github.com/Atmosphere/atmosphere/issues/762)

- If possible trigger event when maximum reconnect is reached [\#761](https://github.com/Atmosphere/atmosphere/issues/761)

- Jguery plugin: reset connectionCount after reconnect [\#760](https://github.com/Atmosphere/atmosphere/issues/760)

- \[runtime\] leak [\#751](https://github.com/Atmosphere/atmosphere/issues/751)

- \[Glassfish\] PWC3990: getWriter\(\) has already been called for this response [\#722](https://github.com/Atmosphere/atmosphere/issues/722)

- Parameter parsing problem in 1.1.0-SNAPSHOT [\#691](https://github.com/Atmosphere/atmosphere/issues/691)

## [atmosphere-project-1.0.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.5) (2012-12-07)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.4...atmosphere-project-1.0.5)

**Closed issues:**

- \[atmosphere.js\] Deprecate maxRequest, add maxReconnectOnClose [\#759](https://github.com/Atmosphere/atmosphere/issues/759)

- WebSocketProtocol and Handler must first be loaded using the Thread's classloader [\#758](https://github.com/Atmosphere/atmosphere/issues/758)

- \[websocket\]\[tomcat\] org.atmosphere.cpr.AtmosphereRequest.getRemoteAddr\(\) returns empty string [\#757](https://github.com/Atmosphere/atmosphere/issues/757)

- \[GlassFish\] resource.getRequest\(\).getPathInfo\(\) issue [\#755](https://github.com/Atmosphere/atmosphere/issues/755)

- \[Glassfish and PrimeFaces Push\] No BroadcasterCache configured.  [\#754](https://github.com/Atmosphere/atmosphere/issues/754)

- Websocket connection doesnt close with firefox [\#753](https://github.com/Atmosphere/atmosphere/issues/753)

- \[runtime\] - performance issue \(thread blocked\) [\#749](https://github.com/Atmosphere/atmosphere/issues/749)

- Critical memory leak [\#748](https://github.com/Atmosphere/atmosphere/issues/748)

- \[jersey\] @Asynchronous must set the Broadcaster's life cycle to EMPTY\_DESTROY  [\#747](https://github.com/Atmosphere/atmosphere/issues/747)

- Force resuming long-polling connection on Callable exception [\#746](https://github.com/Atmosphere/atmosphere/issues/746)

- toLowerCase on parameters [\#745](https://github.com/Atmosphere/atmosphere/issues/745)

- Generate a default UUID for WebSocket/Streaming [\#742](https://github.com/Atmosphere/atmosphere/issues/742)

- Configuration of Primefaces-Push with Apache Server Load Balancing  [\#741](https://github.com/Atmosphere/atmosphere/issues/741)

- Resolution of optional bundles [\#740](https://github.com/Atmosphere/atmosphere/issues/740)

- \[runtime\] - onSuspend not invoked after reconnect with cached message [\#738](https://github.com/Atmosphere/atmosphere/issues/738)

- Passing the actual location to WebSocketImpl [\#737](https://github.com/Atmosphere/atmosphere/issues/737)

- applicationConfig not parsed properly in atmosphere.xml [\#736](https://github.com/Atmosphere/atmosphere/issues/736)

- perRequestFilter must not synchronize when no filters defined [\#735](https://github.com/Atmosphere/atmosphere/issues/735)

- \[atmosphere.js\] global onTransportFailure  not defined properly [\#734](https://github.com/Atmosphere/atmosphere/issues/734)

- Atmosphere.js reports 1.0.3 in 1.0.4 [\#733](https://github.com/Atmosphere/atmosphere/issues/733)

- Implement getAuth and setAuth in RedisFilter.  [\#732](https://github.com/Atmosphere/atmosphere/issues/732)

- \[runtime\] - push performance issue [\#730](https://github.com/Atmosphere/atmosphere/issues/730)

- Setting org.atmosphere.cpr.broadcasterLifeCyclePolicy in atmosphere.xml doesn't work [\#728](https://github.com/Atmosphere/atmosphere/issues/728)

- 0.a.c.AtmosphereRequest.getLocale\(\) returns null on Tomcat7 whereas on Jetty8 or JBoss7 it returns non-null Locale. [\#727](https://github.com/Atmosphere/atmosphere/issues/727)

- \[streaming\] reconnect issue when maxStreamingLength is reached [\#726](https://github.com/Atmosphere/atmosphere/issues/726)

- \[performance\] Make WebSocketProcessor a singleton [\#725](https://github.com/Atmosphere/atmosphere/issues/725)

- \[runtime\] - 1.0.4-SNAPSHOT broadcaster.lookup is not thread safe [\#723](https://github.com/Atmosphere/atmosphere/issues/723)

- SimpleBroadcaster does not create a unique instance of BroadcasterConfig [\#720](https://github.com/Atmosphere/atmosphere/issues/720)

- Support for non-jquery atmosphere.js [\#684](https://github.com/Atmosphere/atmosphere/issues/684)

- createStreamingPadding does not respect padding member variable [\#672](https://github.com/Atmosphere/atmosphere/issues/672)

- IE closes socket after 10 minutes [\#623](https://github.com/Atmosphere/atmosphere/issues/623)

- \[atmosphere.js\] IE 8 doesn't work when share = true [\#580](https://github.com/Atmosphere/atmosphere/issues/580)

- \[runtime\] Possible Broacaster leaks [\#528](https://github.com/Atmosphere/atmosphere/issues/528)

- Inconsisten use of BroadcasterFactory in AtmosphereFramework [\#430](https://github.com/Atmosphere/atmosphere/issues/430)

- \[client\] Add a Java Based Atmosphere's Client [\#262](https://github.com/Atmosphere/atmosphere/issues/262)

**Merged pull requests:**

- corrected:   RedisFilter.java [\#739](https://github.com/Atmosphere/atmosphere/pull/739) ([JosefK](https://github.com/JosefK))

- fixed bug where requests-array was modified while iterating [\#731](https://github.com/Atmosphere/atmosphere/pull/731) ([ClemensSchneider](https://github.com/ClemensSchneider))

- Class Cast exception [\#721](https://github.com/Atmosphere/atmosphere/pull/721) ([xylifyx](https://github.com/xylifyx))

## [atmosphere-project-1.0.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.4) (2012-11-02)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.3...atmosphere-project-1.0.4)

**Closed issues:**

- Server XML Configuration table in the wiki doesn't display properly. [\#719](https://github.com/Atmosphere/atmosphere/issues/719)

- \[runtime\] \[long-polling\] \[EMPTY\_DESTROY\] - AsyncWrite thread leak [\#717](https://github.com/Atmosphere/atmosphere/issues/717)

- Growing number of broadcaster threads [\#716](https://github.com/Atmosphere/atmosphere/issues/716)

## [atmosphere-project-1.0.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.3) (2012-10-29)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.1.0.beta1...atmosphere-project-1.0.3)

## [atmosphere-project-1.1.0.beta1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.1.0.beta1) (2012-10-29)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.2...atmosphere-project-1.1.0.beta1)

**Closed issues:**

- Update GWT module to support GWT 2.5.0 [\#713](https://github.com/Atmosphere/atmosphere/issues/713)

- \[android 2.3.3/3.2\] Messages get lost if published "at the same time" [\#712](https://github.com/Atmosphere/atmosphere/issues/712)

- Publish - Reconnect Problem on Android [\#711](https://github.com/Atmosphere/atmosphere/issues/711)

- \[jetty\] EofException / IOException using InternetExplorer [\#710](https://github.com/Atmosphere/atmosphere/issues/710)

- ArrayIndexOutOfBoundsException in AbstractBroadcasterCache [\#709](https://github.com/Atmosphere/atmosphere/issues/709)

- GWT client can fail to post messages to server  [\#708](https://github.com/Atmosphere/atmosphere/issues/708)

- webSocketUrl does not dispatch to proper Jersey resource method [\#707](https://github.com/Atmosphere/atmosphere/issues/707)

- Add support for excluding content-type when TrackMessageSizeInterceptor is used [\#704](https://github.com/Atmosphere/atmosphere/issues/704)

- Clients hang when multiple tabs  \(~20+\) opened [\#702](https://github.com/Atmosphere/atmosphere/issues/702)

- withCredentials not set when atmosphere.push is called. [\#700](https://github.com/Atmosphere/atmosphere/issues/700)

- \[runtime\] MetaBroadcaster Leaks [\#699](https://github.com/Atmosphere/atmosphere/issues/699)

- Add support for WebSocketStreamHandler [\#698](https://github.com/Atmosphere/atmosphere/issues/698)

- \[GlassFish\] WebSocket implementation doesn't call doCometSupport [\#696](https://github.com/Atmosphere/atmosphere/issues/696)

- json response is invalid [\#695](https://github.com/Atmosphere/atmosphere/issues/695)

- \[GWT\] \(de-\)serialization issue when broadcasting nested, complex object [\#694](https://github.com/Atmosphere/atmosphere/issues/694)

- onError not called for streaming/long-polling [\#692](https://github.com/Atmosphere/atmosphere/issues/692)

- AtmosphereResource.closeStreamOrWriter should not close the WebSocket. [\#690](https://github.com/Atmosphere/atmosphere/issues/690)

- \[runtime\] - AsyncWriteToken leak [\#689](https://github.com/Atmosphere/atmosphere/issues/689)

- \[atmosphere.js\] Mixing Callback and Function doesn't work with WebSocket [\#687](https://github.com/Atmosphere/atmosphere/issues/687)

- ClassNotFoundException for GWT 2.5-RC2 [\#686](https://github.com/Atmosphere/atmosphere/issues/686)

- \[GlassFish\] Atmosphere interrupts writer thread even if it didn't complete writing data to a client [\#651](https://github.com/Atmosphere/atmosphere/issues/651)

- resource.getRequest\(\).getCookies\(\) doesn't work [\#485](https://github.com/Atmosphere/atmosphere/issues/485)

**Merged pull requests:**

- Extend webSocketUrl to Comet transports [\#715](https://github.com/Atmosphere/atmosphere/pull/715) ([sbalmos](https://github.com/sbalmos))

- Fix for \#707 webSocketUrl Jersey dispatching [\#714](https://github.com/Atmosphere/atmosphere/pull/714) ([sbalmos](https://github.com/sbalmos))

- Copy original request headers on subsequent polling POSTs [\#697](https://github.com/Atmosphere/atmosphere/pull/697) ([sbalmos](https://github.com/sbalmos))

- Fix NPE on client unload [\#688](https://github.com/Atmosphere/atmosphere/pull/688) ([nite23](https://github.com/nite23))

## [atmosphere-project-1.0.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.2) (2012-10-12)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.1...atmosphere-project-1.0.2)

**Closed issues:**

- Add a new meta annotation that does everything: @AtmosphereManagedService [\#685](https://github.com/Atmosphere/atmosphere/issues/685)

- Suspended requests never freed when using Tomcat Native \(CometEvent.END handling bug\) [\#683](https://github.com/Atmosphere/atmosphere/issues/683)

- onStateChange not always called when AtmosphereResource.resume\(\) is called. [\#682](https://github.com/Atmosphere/atmosphere/issues/682)

- AtmosphereResponse must invoke onDisconnect callback on IOException [\#681](https://github.com/Atmosphere/atmosphere/issues/681)

- X-Cache-Date and X-Atmosphere-tracking-id not properly read in jquery.atmosphere.js [\#679](https://github.com/Atmosphere/atmosphere/issues/679)

- onStateChange returning a different resource UUID when connection is closed [\#678](https://github.com/Atmosphere/atmosphere/issues/678)

- onDisconnect must be called after onResume [\#677](https://github.com/Atmosphere/atmosphere/issues/677)

- \[jersey\] Make sure only one Broadcaster gets created when @Asynchronous annotation is used [\#676](https://github.com/Atmosphere/atmosphere/issues/676)

- Deprecate/remove all traces of suspend\(time, junk\) API [\#675](https://github.com/Atmosphere/atmosphere/issues/675)

- Bug in AbstractBroadcasterCache.java [\#674](https://github.com/Atmosphere/atmosphere/issues/674)

- is ie8 supported? [\#673](https://github.com/Atmosphere/atmosphere/issues/673)

- Grizzly2WebSocketSupport and fallbacks [\#670](https://github.com/Atmosphere/atmosphere/issues/670)

- RediBroadcaster broken because of wrong initialization logic [\#667](https://github.com/Atmosphere/atmosphere/issues/667)

- OSGI import packages - issue \#219 [\#666](https://github.com/Atmosphere/atmosphere/issues/666)

- Must honor the defined Broadcaster web.xml instead of auto discovering [\#665](https://github.com/Atmosphere/atmosphere/issues/665)

- \[jetty\]\[websockets\] WebSocketFactory may throws IllegalStateException [\#663](https://github.com/Atmosphere/atmosphere/issues/663)

- Filter based applications do not initialize at all [\#662](https://github.com/Atmosphere/atmosphere/issues/662)

- QueryString must not be re-added after Interceptor/Processor dispatch, must be decoded [\#660](https://github.com/Atmosphere/atmosphere/issues/660)

- Add onPreSuspend callback to AtmosphereResourceEventListener [\#657](https://github.com/Atmosphere/atmosphere/issues/657)

- SessionBroadcasterCache throws ClassCastException  [\#656](https://github.com/Atmosphere/atmosphere/issues/656)

- Document List<Object\> returned by a BroadcasterCache when used with custom AtmosphereHandler [\#655](https://github.com/Atmosphere/atmosphere/issues/655)

- Add an HeartBeat AtmosphereInterceptor [\#654](https://github.com/Atmosphere/atmosphere/issues/654)

- Message is not filtered if there are not resources associated with the Broadcaster [\#653](https://github.com/Atmosphere/atmosphere/issues/653)

- GWT Demo not working with IE9 on Tomcat and Glassfish [\#652](https://github.com/Atmosphere/atmosphere/issues/652)

- @ character not working in url path in Atmosphere framework [\#650](https://github.com/Atmosphere/atmosphere/issues/650)

- Missing shutdown of asyncSupport in case of fallback to other implementation [\#649](https://github.com/Atmosphere/atmosphere/issues/649)

- WebSocketProcessorFactory not application isolated [\#648](https://github.com/Atmosphere/atmosphere/issues/648)

- Typo in configuration parameter name DEFAULT\_CONTENT\_TYPE [\#647](https://github.com/Atmosphere/atmosphere/issues/647)

- Messages lost when using long-polling, Cache and TrackMessageSizeFilter [\#646](https://github.com/Atmosphere/atmosphere/issues/646)

- Add support for Jetty 9 new WebSocket API [\#644](https://github.com/Atmosphere/atmosphere/issues/644)

- Logging for AtmosphereResourceLifecycleInterceptor uses SSEAtmosphereInterceptor logger [\#643](https://github.com/Atmosphere/atmosphere/issues/643)

- AtmosphereHandler in web.xml does not get injected by org.atmosphere.di.Injector [\#642](https://github.com/Atmosphere/atmosphere/issues/642)

- native socket.io chat is broken [\#641](https://github.com/Atmosphere/atmosphere/issues/641)

- \[runtime\] streaming,gzip, broadcast split + stuck issue [\#621](https://github.com/Atmosphere/atmosphere/issues/621)

- SocketIOSessionManagerImpl trying to send on a closed websocke [\#609](https://github.com/Atmosphere/atmosphere/issues/609)

- Allow BroadcasterCache to discard some message [\#607](https://github.com/Atmosphere/atmosphere/issues/607)

- Connection sharing child-to-parent promotion sometimes does not occur [\#602](https://github.com/Atmosphere/atmosphere/issues/602)

- 1.0.0.rc1 breaks GWT apps [\#587](https://github.com/Atmosphere/atmosphere/issues/587)

- GWT client can't build with GWT 2.5.0-rc1 [\#575](https://github.com/Atmosphere/atmosphere/issues/575)

- Support for Gwt comet over https [\#574](https://github.com/Atmosphere/atmosphere/issues/574)

- \[gwt\] When WebSockets fail over 3G, Atmosphere should switch to long-poll [\#571](https://github.com/Atmosphere/atmosphere/issues/571)

- HazlecastBroadcaster wrong use of topic listener [\#536](https://github.com/Atmosphere/atmosphere/issues/536)

- GWT client API needs documenation [\#534](https://github.com/Atmosphere/atmosphere/issues/534)

- GWT with Basic container security causes NPE [\#518](https://github.com/Atmosphere/atmosphere/issues/518)

- Atmosphere GWT Client 1.0.x fails with SYNTAX\_ERR: DOM Exception 12 [\#506](https://github.com/Atmosphere/atmosphere/issues/506)

- \[GlassFish\] When connection is down sometimes AtmosphereResource does not destroy [\#499](https://github.com/Atmosphere/atmosphere/issues/499)

- Inflexible mapping regex [\#498](https://github.com/Atmosphere/atmosphere/issues/498)

- \[gwt\] error on startup sometimes. [\#489](https://github.com/Atmosphere/atmosphere/issues/489)

- NullPointerException on atmosphere + tapestry5 \(centOS + tomcat 7.0.26\) [\#478](https://github.com/Atmosphere/atmosphere/issues/478)

- \[gwt\] unicode character transfer not possible [\#476](https://github.com/Atmosphere/atmosphere/issues/476)

- \[runtime\] Add support for multiple WebSocketHandler [\#470](https://github.com/Atmosphere/atmosphere/issues/470)

- \[gwt/glassfish\] WebSocket not supported after restarting the server [\#462](https://github.com/Atmosphere/atmosphere/issues/462)

- Send X\_ATMOSPHERE\_TRANSPORT header [\#450](https://github.com/Atmosphere/atmosphere/issues/450)

- Atmosphere GWT 0.9/1.0 fails with JavaFX WebView [\#440](https://github.com/Atmosphere/atmosphere/issues/440)

- User selectable tranports [\#433](https://github.com/Atmosphere/atmosphere/issues/433)

- Create Long-Polling transport [\#432](https://github.com/Atmosphere/atmosphere/issues/432)

- Test with JDK 7 [\#416](https://github.com/Atmosphere/atmosphere/issues/416)

- Add support for HeaderBroadcasterCache [\#401](https://github.com/Atmosphere/atmosphere/issues/401)

- \[gwt\] Exception happens when heartbeat parameter is empty string on Tomcat 7. [\#298](https://github.com/Atmosphere/atmosphere/issues/298)

- \[extra\] Add support for Jersey 2 runtime [\#297](https://github.com/Atmosphere/atmosphere/issues/297)

- Add WebLogic pre-12.x Support [\#277](https://github.com/Atmosphere/atmosphere/issues/277)

- Redis/Hazelcast/JMS/XMPP Broadcaster must hook into Jersey marshalling code [\#160](https://github.com/Atmosphere/atmosphere/issues/160)

- \[gwt\] Constant connection attempts made by GWT-Atmosphere client when using IE with Google Chrome Frame [\#145](https://github.com/Atmosphere/atmosphere/issues/145)

- Add support for Terracotta clustering [\#80](https://github.com/Atmosphere/atmosphere/issues/80)

- Need a generic way to configure plugin [\#72](https://github.com/Atmosphere/atmosphere/issues/72)

- Enable protocol configuration for JGroupsBroadcaster [\#68](https://github.com/Atmosphere/atmosphere/issues/68)

- AtmosphereGwtHandler.doComet/HttpSession timeouts [\#66](https://github.com/Atmosphere/atmosphere/issues/66)

**Merged pull requests:**

- Added "resolution optional" to pom.xml [\#669](https://github.com/Atmosphere/atmosphere/pull/669) ([florianpirchner](https://github.com/florianpirchner))

- removed outdated jgroups protocol properties not available anymore [\#668](https://github.com/Atmosphere/atmosphere/pull/668) ([pmiklos](https://github.com/pmiklos))

- discarding own messages in clustered broadcaster using jgroups feature [\#664](https://github.com/Atmosphere/atmosphere/pull/664) ([pmiklos](https://github.com/pmiklos))

- Possible NumberFormatException when HttpSession\#getAttribute returns null. [\#661](https://github.com/Atmosphere/atmosphere/pull/661) ([sjardine](https://github.com/sjardine))

- SSE origin check becomes protocol agnostic [\#659](https://github.com/Atmosphere/atmosphere/pull/659) ([halfbaked](https://github.com/halfbaked))

## [atmosphere-project-1.0.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.1) (2012-09-21)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0...atmosphere-project-1.0.1)

**Closed issues:**

- SimpleHttpProtocol might lose part of the message with messageDelimiter defined [\#640](https://github.com/Atmosphere/atmosphere/issues/640)

- Inconsistent support-session value in atmosphere.xml/web.xml and ignored supportSession in AtmosphereHandlerService annotation [\#639](https://github.com/Atmosphere/atmosphere/issues/639)

- \[atmosphere.js\] JSONP must carry the headers from the server. [\#638](https://github.com/Atmosphere/atmosphere/issues/638)

- \[atmosphere.js\] XDomainRequest logic doesn't support all request's properties [\#637](https://github.com/Atmosphere/atmosphere/issues/637)

- Add support for simple AtmosphereHandler called OnMessage [\#635](https://github.com/Atmosphere/atmosphere/issues/635)

- \[IE 8\] With enableXDR, IE 8 reconnect forever. [\#634](https://github.com/Atmosphere/atmosphere/issues/634)

- @MeteorService breaks Meteor [\#632](https://github.com/Atmosphere/atmosphere/issues/632)

- org.atmosphere.cpr.Meteor, cache Memory Leak [\#631](https://github.com/Atmosphere/atmosphere/issues/631)

- Broadcaster: Improve \#broadcast API [\#629](https://github.com/Atmosphere/atmosphere/issues/629)

- Broadcaster: Improve \#broadcast API [\#628](https://github.com/Atmosphere/atmosphere/issues/628)

- Several AsyncIOWriterAdapter methods default to infinite loops, not no-ops [\#627](https://github.com/Atmosphere/atmosphere/issues/627)

- \[jQuery\] streaming, opera breaks on junk split + FIX [\#625](https://github.com/Atmosphere/atmosphere/issues/625)

- Atmosphere response setHeader adds header value instead of replacing it [\#624](https://github.com/Atmosphere/atmosphere/issues/624)

- Window close events randomly failing to be processed due to dead AtmosphereResource [\#619](https://github.com/Atmosphere/atmosphere/issues/619)

- \[runtime\] Improve BroadcasterCache handling of duplicate [\#616](https://github.com/Atmosphere/atmosphere/issues/616)

- \[jQuery\] \[long-polling\] callbacks for aggregated messages still not fired properly [\#615](https://github.com/Atmosphere/atmosphere/issues/615)

- cleaning up sessions - jboss 5.1 + long polling [\#613](https://github.com/Atmosphere/atmosphere/issues/613)

- annotation detection doesn't work in scala projects [\#611](https://github.com/Atmosphere/atmosphere/issues/611)

- \[websocket\] WebSocket class must be re-written to support AtmosphereInterceptorWriter [\#610](https://github.com/Atmosphere/atmosphere/issues/610)

- IE9 loses messages during alert freeze [\#606](https://github.com/Atmosphere/atmosphere/issues/606)

- Receive part  of streaming junk in onMessage callback [\#605](https://github.com/Atmosphere/atmosphere/issues/605)

- deadlock when disconnecting clients [\#601](https://github.com/Atmosphere/atmosphere/issues/601)

- \[websocket\] \[jetty8\] Cookies get recycled by Jetty and un availaible after initial handshake [\#600](https://github.com/Atmosphere/atmosphere/issues/600)

- messageLength of next message is glued after current one in IE [\#597](https://github.com/Atmosphere/atmosphere/issues/597)

- tracking-id is not reused upon reconnect [\#596](https://github.com/Atmosphere/atmosphere/issues/596)

- AtmosphereResourceLifecycleInterceptor does not resume resource for long-polling [\#594](https://github.com/Atmosphere/atmosphere/issues/594)

- maxInactiveActivity is not detected when tomcat failed using comet support [\#588](https://github.com/Atmosphere/atmosphere/issues/588)

- \[atmosphere.js\] Uncaught TypeError: Cannot call method 'concat' of null  [\#586](https://github.com/Atmosphere/atmosphere/issues/586)

- \[wicket\] NPE on timeout [\#417](https://github.com/Atmosphere/atmosphere/issues/417)

- Atmosphere&Jersey: Wrong restfull api method is called when do push message from client side [\#241](https://github.com/Atmosphere/atmosphere/issues/241)

**Merged pull requests:**

- POM update for atmosphere-runtime bundling [\#622](https://github.com/Atmosphere/atmosphere/pull/622) ([jjongsma](https://github.com/jjongsma))

- Window close events randomly failing [\#618](https://github.com/Atmosphere/atmosphere/pull/618) ([markathomas](https://github.com/markathomas))

- Propagate flushComments correctly [\#617](https://github.com/Atmosphere/atmosphere/pull/617) ([jjongsma](https://github.com/jjongsma))

- Fix for issue 611 [\#612](https://github.com/Atmosphere/atmosphere/pull/612) ([casualjim](https://github.com/casualjim))

- updates the scala plugin to version with incremental compilation [\#608](https://github.com/Atmosphere/atmosphere/pull/608) ([casualjim](https://github.com/casualjim))

- escape message body in URI string [\#603](https://github.com/Atmosphere/atmosphere/pull/603) ([nite23](https://github.com/nite23))

- Thread safety fix [\#599](https://github.com/Atmosphere/atmosphere/pull/599) ([nite23](https://github.com/nite23))

- Fix incorrect message size; Use nio for decoding. [\#598](https://github.com/Atmosphere/atmosphere/pull/598) ([nite23](https://github.com/nite23))

## [atmosphere-project-1.0.0](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0) (2012-09-04)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.RC1...atmosphere-project-1.0.0)

**Closed issues:**

- AtmosphereServlet does not delegate to the default servlet if no filter matches [\#595](https://github.com/Atmosphere/atmosphere/issues/595)

- Session timeout restorer can get overwritten and the original timeout value is lost [\#592](https://github.com/Atmosphere/atmosphere/issues/592)

- Unsubscribe in window.unload instead of window.beforeunload [\#591](https://github.com/Atmosphere/atmosphere/issues/591)

- Message delimiter is added several times if message payload exceeds 8192 when using jersey [\#590](https://github.com/Atmosphere/atmosphere/issues/590)

- \[runtime\] org.atmosphere.cpr.recycleAtmosphereRequestResponse not properly implemented [\#584](https://github.com/Atmosphere/atmosphere/issues/584)

- TomcatWebSocketHandler handle onBinaryMessage as whole ByteBuffer [\#582](https://github.com/Atmosphere/atmosphere/issues/582)

- Grizzly2WebSocketSupport: pathInfo in HttpServletRequestImpl null [\#581](https://github.com/Atmosphere/atmosphere/issues/581)

- Session timeout not restored on serialized sessions [\#555](https://github.com/Atmosphere/atmosphere/issues/555)

- cleaning up sessions - jboss 5.1 + long polling [\#407](https://github.com/Atmosphere/atmosphere/issues/407)

**Merged pull requests:**

- Session timeout works with serialization and multiple requests [\#593](https://github.com/Atmosphere/atmosphere/pull/593) ([Gekkio](https://github.com/Gekkio))

- Compatibility with other frameworks using $ alias [\#583](https://github.com/Atmosphere/atmosphere/pull/583) ([nite23](https://github.com/nite23))

- Hack mapping the request so getPathInfo\(\) and getServletPath\(\) work. [\#585](https://github.com/Atmosphere/atmosphere/pull/585) ([rlubke](https://github.com/rlubke))

## [atmosphere-project-1.0.0.RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.RC1) (2012-08-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta5...atmosphere-project-1.0.0.RC1)

**Closed issues:**

- BroadcasterCache API must pass an ID to implementation [\#577](https://github.com/Atmosphere/atmosphere/issues/577)

- Possible Thread Race when Broadcaster gets destroyed and the re-use option is set to true [\#576](https://github.com/Atmosphere/atmosphere/issues/576)

- Cached messages multiplied [\#573](https://github.com/Atmosphere/atmosphere/issues/573)

- \[runtime\] cached messages missed when using exclude resource [\#572](https://github.com/Atmosphere/atmosphere/issues/572)

- Runtime errors with jdk 5.0 and Weblogic 10.0 [\#570](https://github.com/Atmosphere/atmosphere/issues/570)

- \[jQuery\] firefox, ESC key closes request [\#569](https://github.com/Atmosphere/atmosphere/issues/569)

- \[atmosphere.js\] long-polling, two messages in one request, only last one does callback [\#568](https://github.com/Atmosphere/atmosphere/issues/568)

- SessionBroadcasterCache deliver messages twice [\#567](https://github.com/Atmosphere/atmosphere/issues/567)

- HeaderBroadcasterCache delivers first message twice [\#566](https://github.com/Atmosphere/atmosphere/issues/566)

- MessageLengthInterceptor breaks SSE transport [\#564](https://github.com/Atmosphere/atmosphere/issues/564)

- Connection sharing doesn't work with atmosphere.js after first tab is closed [\#563](https://github.com/Atmosphere/atmosphere/issues/563)

- \[jQuery\] - streaming, junk issue [\#562](https://github.com/Atmosphere/atmosphere/issues/562)

- \[glassfish\]\[jersey\] IllegalAccessException on Chrome browser [\#561](https://github.com/Atmosphere/atmosphere/issues/561)

- \[jQuery\] streaming, IE , disconnect event not fired when server closed [\#559](https://github.com/Atmosphere/atmosphere/issues/559)

- memory leak in the class Meteor [\#558](https://github.com/Atmosphere/atmosphere/issues/558)

- Connection sharing does not work in certain cases [\#557](https://github.com/Atmosphere/atmosphere/issues/557)

- \[runtime\] cached messages - first message is skipped [\#556](https://github.com/Atmosphere/atmosphere/issues/556)

- \[jQuery\] streaming, cached messages not passing through \_trackMessageSize [\#554](https://github.com/Atmosphere/atmosphere/issues/554)

- Request parameters get lost on ws requests on Tomcat 7 [\#553](https://github.com/Atmosphere/atmosphere/issues/553)

- \[jQuery\] - streaming, onMessage is not fired when a lot requests made in short time [\#552](https://github.com/Atmosphere/atmosphere/issues/552)

- \[runtime\] streaming - different broadcasts combined [\#551](https://github.com/Atmosphere/atmosphere/issues/551)

- \[jquery\] IE9 - onClose not fired [\#547](https://github.com/Atmosphere/atmosphere/issues/547)

- disconnect event not fired issue [\#545](https://github.com/Atmosphere/atmosphere/issues/545)

- \[1.0.0b5\] AtmosphereResource.uuid != tracking-id [\#544](https://github.com/Atmosphere/atmosphere/issues/544)

- \[1.0.0b5\] examples chat, meteor-chat don't work [\#543](https://github.com/Atmosphere/atmosphere/issues/543)

- \[1.0.0b5\] SSE Interceptor -  String vs. byte\[\] bug.  [\#542](https://github.com/Atmosphere/atmosphere/issues/542)

- Chat Sample does not seem to work with Firefox 14.0.1 [\#541](https://github.com/Atmosphere/atmosphere/issues/541)

- Allow multiple fallback transports in atmosphere.js [\#540](https://github.com/Atmosphere/atmosphere/issues/540)

- Handling of "glued" messages [\#286](https://github.com/Atmosphere/atmosphere/issues/286)

**Merged pull requests:**

- fix log message onSuspend [\#578](https://github.com/Atmosphere/atmosphere/pull/578) ([cwash](https://github.com/cwash))

- fix for BroadcasterCacheBase IllegalStateException [\#560](https://github.com/Atmosphere/atmosphere/pull/560) ([nite23](https://github.com/nite23))

- First cut of Grizzly2 WebSocket support [\#579](https://github.com/Atmosphere/atmosphere/pull/579) ([rlubke](https://github.com/rlubke))

- Changed Atmosphere Interceptors to have three stages, prePayload, transformPayload and postPayload. [\#546](https://github.com/Atmosphere/atmosphere/pull/546) ([zrvan](https://github.com/zrvan))

- Integration tests to verify wrong message concatenation [\#399](https://github.com/Atmosphere/atmosphere/pull/399) ([chilicat](https://github.com/chilicat))

## [atmosphere-project-1.0.0.beta5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta5) (2012-07-27)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta4...atmosphere-project-1.0.0.beta5)

**Closed issues:**

- \[atmosphere.js\] Add a way to disable reading response's headers [\#539](https://github.com/Atmosphere/atmosphere/issues/539)

- @Asynchronous annotation must use the generatred AtmosphereResource's UUID [\#538](https://github.com/Atmosphere/atmosphere/issues/538)

- AtmosphereResource Initial Suspended's uuid is null when using streaming + POST [\#537](https://github.com/Atmosphere/atmosphere/issues/537)

- \[atmosphere.js\] long-polling XDR fails to suspend in IE9 [\#535](https://github.com/Atmosphere/atmosphere/issues/535)

- streaming transport disconnection event missing [\#533](https://github.com/Atmosphere/atmosphere/issues/533)

- streaming transport disconnection event missing [\#532](https://github.com/Atmosphere/atmosphere/issues/532)

- \[atmosphere.js\] Firefox/IE close tabs not detected [\#531](https://github.com/Atmosphere/atmosphere/issues/531)

- GlassFish WebSocketListenerEvent not working [\#530](https://github.com/Atmosphere/atmosphere/issues/530)

- \[runtime\] Factory should not be destroyed when shared amongst multiple application [\#527](https://github.com/Atmosphere/atmosphere/issues/527)

- \[atmosphere.js\] Wrong logic for executeCallbackBeforeReconnect and jsonp/ajaxtransport [\#526](https://github.com/Atmosphere/atmosphere/issues/526)

- Using multiple AtmosphereInterceptors results in only one being applied [\#525](https://github.com/Atmosphere/atmosphere/issues/525)

- \[atmosphere.js\] \[firefox\] websocket it auto reopened when I refresh the webpage - firefox 14.0.1 [\#522](https://github.com/Atmosphere/atmosphere/issues/522)

- cloneRequest puts copied session in wrong object on Tomcat 7 [\#520](https://github.com/Atmosphere/atmosphere/issues/520)

- SessionTimeoutSupport throws Exception if Session does not exists  [\#510](https://github.com/Atmosphere/atmosphere/issues/510)

- \[runtime\] Refactor WebSocket implementation  [\#270](https://github.com/Atmosphere/atmosphere/issues/270)

**Merged pull requests:**

- Fix to allow for multiple JSONP messages to be delivered in one response. [\#521](https://github.com/Atmosphere/atmosphere/pull/521) ([zrvan](https://github.com/zrvan))

- XDR ie bug fix  [\#466](https://github.com/Atmosphere/atmosphere/pull/466) ([denisz](https://github.com/denisz))

## [atmosphere-project-1.0.0.beta4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta4) (2012-07-20)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta3...atmosphere-project-1.0.0.beta4)

**Closed issues:**

- Occasional GWT server NPE when using IE browser [\#519](https://github.com/Atmosphere/atmosphere/issues/519)

- exclude resource doesnt work [\#517](https://github.com/Atmosphere/atmosphere/issues/517)

- \[streaming\] switch streaming junk from atmosphere to whitespace [\#516](https://github.com/Atmosphere/atmosphere/issues/516)

- \[websocket\] Make sure FakeSession is shared amongst message [\#515](https://github.com/Atmosphere/atmosphere/issues/515)

- AtmosphereRequest.getSession\(create\) may return null, even if a session exists [\#513](https://github.com/Atmosphere/atmosphere/issues/513)

- Autoconfigure Service fails when using embedded Jetty [\#512](https://github.com/Atmosphere/atmosphere/issues/512)

- GWT + GlassFish 3.1.2 + Websockets only [\#511](https://github.com/Atmosphere/atmosphere/issues/511)

- \[atmosphere.js\] Add support for jQuery 1.7.2 [\#509](https://github.com/Atmosphere/atmosphere/issues/509)

- \[runtime\] WebSocketProcessor must be pluggable to support JSR 356 [\#508](https://github.com/Atmosphere/atmosphere/issues/508)

- \[websocket\] Possible memory leak on Tomcat with WebSocket [\#505](https://github.com/Atmosphere/atmosphere/issues/505)

- Atmosphere Server on Android Compatibility [\#503](https://github.com/Atmosphere/atmosphere/issues/503)

- org.atmosphere.filter ignored if org.atmosphere.servlet not set [\#502](https://github.com/Atmosphere/atmosphere/issues/502)

- \[gwt\] AtmosphereProxy - exponential backoff on reconnect [\#497](https://github.com/Atmosphere/atmosphere/issues/497)

- \[atmosphere.js\] Multi Tabs, Multi Window transport sharing [\#493](https://github.com/Atmosphere/atmosphere/issues/493)

- streaming transport not working in Android 2.2/2.3 browser [\#400](https://github.com/Atmosphere/atmosphere/issues/400)

**Merged pull requests:**

- Pull request for issue \#502 [\#504](https://github.com/Atmosphere/atmosphere/pull/504) ([jsarman](https://github.com/jsarman))

- Integrate Grizzly 2 Comet Support [\#501](https://github.com/Atmosphere/atmosphere/pull/501) ([rlubke](https://github.com/rlubke))

- Change for better Atmosphere GWT / Vaadin support [\#500](https://github.com/Atmosphere/atmosphere/pull/500) ([markathomas](https://github.com/markathomas))

## [atmosphere-project-1.0.0.beta3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta3) (2012-07-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta2a...atmosphere-project-1.0.0.beta3)

**Closed issues:**

- \[gwt\] Char sequence "\n" in String messages gets corrupted through PLAIN String serialization in GWT [\#494](https://github.com/Atmosphere/atmosphere/issues/494)

- \[atmosphere.js\] AtmosphereRequest.uuid should be the server generated one [\#492](https://github.com/Atmosphere/atmosphere/issues/492)

- \[atmosphere.js\] unsubscribe events should invoke onClose function [\#491](https://github.com/Atmosphere/atmosphere/issues/491)

- sending pure binary data feature please [\#490](https://github.com/Atmosphere/atmosphere/issues/490)

- each request from the same client returns a different AtmosphereResource [\#488](https://github.com/Atmosphere/atmosphere/issues/488)

- Default Broadcaster /\* should be shareable amongst AtmosphereServlet [\#487](https://github.com/Atmosphere/atmosphere/issues/487)

- \[gwt\] Glassfish , Refresh page error [\#486](https://github.com/Atmosphere/atmosphere/issues/486)

- MetaBroadcaster must return a Future instead of a collection [\#484](https://github.com/Atmosphere/atmosphere/issues/484)

- Add BroadcasterListener support [\#483](https://github.com/Atmosphere/atmosphere/issues/483)

- Broadcaster.broadcast\(\).get\(\) innacurate [\#482](https://github.com/Atmosphere/atmosphere/issues/482)

- \[jersey\] Upgrade to 1.12 [\#481](https://github.com/Atmosphere/atmosphere/issues/481)

- Content-Type in query string not added as header before passing request to Jersey [\#480](https://github.com/Atmosphere/atmosphere/issues/480)

- \[Spring\] java.lang.ClassNotFoundException: \> org.atmosphere.cache.HeaderBroadcasterCache   [\#479](https://github.com/Atmosphere/atmosphere/issues/479)

- Redis: jQuery pubsub example wiping out my redis data [\#477](https://github.com/Atmosphere/atmosphere/issues/477)

- NPE in AtmosphereResponse [\#475](https://github.com/Atmosphere/atmosphere/issues/475)

- NPE logged when client closes WebSocket [\#474](https://github.com/Atmosphere/atmosphere/issues/474)

- \[atmosphere.js\] with long-polling transport \_attachHeaders is not called  [\#473](https://github.com/Atmosphere/atmosphere/issues/473)

- GWT posting Date on serialized data gives error on Glassfish 3.1.2 server [\#472](https://github.com/Atmosphere/atmosphere/issues/472)

- Error in java doc for addAtmosphereResource and  removeAtmosphereResource [\#469](https://github.com/Atmosphere/atmosphere/issues/469)

- Broadcaster\#broadcast\(event, Set\) -\> Set may contains null value [\#467](https://github.com/Atmosphere/atmosphere/issues/467)

- \[Tomcat 7.0.28\] Comet fallback not working [\#464](https://github.com/Atmosphere/atmosphere/issues/464)

- Should Server-Sent Event processing include same-origin check? [\#463](https://github.com/Atmosphere/atmosphere/issues/463)

- Glassfish 3.1.2 atmosphere caching bug [\#461](https://github.com/Atmosphere/atmosphere/issues/461)

- NullPointerException on Glassfish 3.1.2 log [\#460](https://github.com/Atmosphere/atmosphere/issues/460)

- FakeHttpSession\#copyAttributes doesn't do anything [\#459](https://github.com/Atmosphere/atmosphere/issues/459)

- Wrong AsyncSupport detected with webSocketSupported = false [\#457](https://github.com/Atmosphere/atmosphere/issues/457)

- Java Swing and JavaFX integration easy? [\#456](https://github.com/Atmosphere/atmosphere/issues/456)

- Java Swing and JavaFX integration easy? [\#455](https://github.com/Atmosphere/atmosphere/issues/455)

- \[runtime\] SessionSupport must be set to false [\#454](https://github.com/Atmosphere/atmosphere/issues/454)

- Glassfish 3.1.2 bug on web.xml context-path [\#453](https://github.com/Atmosphere/atmosphere/issues/453)

- \[jaxrs2\] Update to last version, fix regression [\#452](https://github.com/Atmosphere/atmosphere/issues/452)

- \[runtime\] Glassfish 3.1.2 WebSocket broken [\#451](https://github.com/Atmosphere/atmosphere/issues/451)

- using broadcaster cache results in reception of nested^3+ ArrayLists [\#449](https://github.com/Atmosphere/atmosphere/issues/449)

- XML response won't work correctly when using HTTP streaming  [\#427](https://github.com/Atmosphere/atmosphere/issues/427)

- ajaxRequest.onreadystatechange fails when junk packet is chunked [\#314](https://github.com/Atmosphere/atmosphere/issues/314)

- Issues with using FixedThreadPool for maxProcessingThreads with SharedExecutors set to true [\#264](https://github.com/Atmosphere/atmosphere/issues/264)

- Can't produce JSONP with Jersey [\#194](https://github.com/Atmosphere/atmosphere/issues/194)

- atmospherehandler does not auto-detect transport in some browsers [\#65](https://github.com/Atmosphere/atmosphere/issues/65)

- Broadcaster LifeCycle Manager should be configurable, implementation outside Broadcaster [\#63](https://github.com/Atmosphere/atmosphere/issues/63)

**Merged pull requests:**

- fix for cross-domain request in IE8/9 not using XDomainRequest for push\(\) [\#496](https://github.com/Atmosphere/atmosphere/pull/496) ([nite23](https://github.com/nite23))

- Fixed a couple of transpositions 'endOfJunkLenght' -\> 'endOfJunkLength' [\#468](https://github.com/Atmosphere/atmosphere/pull/468) ([wimplash](https://github.com/wimplash))

- Update documentation to reflect reality. [\#448](https://github.com/Atmosphere/atmosphere/pull/448) ([wimplash](https://github.com/wimplash))

## [atmosphere-project-1.0.0.beta2a](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta2a) (2012-06-28)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta2...atmosphere-project-1.0.0.beta2a)

**Closed issues:**

- \[documentation\] Document onDisconnect/disconnection behaviour [\#437](https://github.com/Atmosphere/atmosphere/issues/437)

## [atmosphere-project-1.0.0.beta2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta2) (2012-06-28)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.7...atmosphere-project-1.0.0.beta2)

**Closed issues:**

- Possible NPE with WebSocket and Jersey [\#446](https://github.com/Atmosphere/atmosphere/issues/446)

- ClassCastException on broadcaster.addAtmosphereResource\(\) w/ Jersey resource [\#445](https://github.com/Atmosphere/atmosphere/issues/445)

- MetaBroadcaster.broadcast causes NPE if no AtmosphereResource has been accessed [\#442](https://github.com/Atmosphere/atmosphere/issues/442)

- \[websocket\] Default SimpleHttpWebSocketProtocol Content-Type should be text/plain [\#441](https://github.com/Atmosphere/atmosphere/issues/441)

- \[jersey\] Content-Type sometimes written twice [\#439](https://github.com/Atmosphere/atmosphere/issues/439)

- <jquery-atmosphere\>Cross domain requests with IE not working [\#425](https://github.com/Atmosphere/atmosphere/issues/425)

- SuspendResponse not honoring content-type  [\#423](https://github.com/Atmosphere/atmosphere/issues/423)

- AtmosphereFilter content-type not handled correctly [\#418](https://github.com/Atmosphere/atmosphere/issues/418)

- Time interval between re-connect attempts [\#415](https://github.com/Atmosphere/atmosphere/issues/415)

- Rest-Chat: message body writer issues using streaming for transport [\#411](https://github.com/Atmosphere/atmosphere/issues/411)

**Merged pull requests:**

- Increase clarity of javadoc in AtmosphereResourceLifecycleInterceptor [\#447](https://github.com/Atmosphere/atmosphere/pull/447) ([wimplash](https://github.com/wimplash))

- Proposed fix for \#442: [\#443](https://github.com/Atmosphere/atmosphere/pull/443) ([wimplash](https://github.com/wimplash))

## [atmosphere-project-0.9.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.7) (2012-06-26)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.6...atmosphere-project-0.9.7)

**Closed issues:**

- \[regression\] Fix for \#412 brokes WebSocket InputStream [\#438](https://github.com/Atmosphere/atmosphere/issues/438)

- java.lang.IllegalStateException: STREAM Exception since 0.9.5  [\#436](https://github.com/Atmosphere/atmosphere/issues/436)

- NPE session troubles with 0.9.6 and later [\#431](https://github.com/Atmosphere/atmosphere/issues/431)

- Clarify difference between native websocket and websocket [\#429](https://github.com/Atmosphere/atmosphere/issues/429)

- Link ... take a look at the WebSocketProtocol documentation [\#428](https://github.com/Atmosphere/atmosphere/issues/428)

## [atmosphere-project-0.9.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.6) (2012-06-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-1.0.0.beta1...atmosphere-project-0.9.6)

**Closed issues:**

- GWT Starting double comet connection [\#426](https://github.com/Atmosphere/atmosphere/issues/426)

- Fixed a bug where the websocket transport was recreating it's server transport [\#424](https://github.com/Atmosphere/atmosphere/issues/424)

- TrackMessageSizeFilter and MessageLengthInterceptor incompatibility [\#421](https://github.com/Atmosphere/atmosphere/issues/421)

- \[websocket\] Broadcaster set in onOpen lost [\#420](https://github.com/Atmosphere/atmosphere/issues/420)

- NPE with AtmosphereResource.getSession\(\) [\#414](https://github.com/Atmosphere/atmosphere/issues/414)

- atm-jquery: FF12 - reconnect issues. [\#413](https://github.com/Atmosphere/atmosphere/issues/413)

- UTF-8 characters encoding not working [\#412](https://github.com/Atmosphere/atmosphere/issues/412)

- Large message issues [\#405](https://github.com/Atmosphere/atmosphere/issues/405)

- \[gwt\] Add connection sharing between windows [\#372](https://github.com/Atmosphere/atmosphere/issues/372)

**Merged pull requests:**

- Proposed CometD change [\#419](https://github.com/Atmosphere/atmosphere/pull/419) ([pierreh](https://github.com/pierreh))

- Don't check status until readyState == 4 \(IE bug\) [\#392](https://github.com/Atmosphere/atmosphere/pull/392) ([chris-martin](https://github.com/chris-martin))

## [atmosphere-project-1.0.0.beta1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-1.0.0.beta1) (2012-06-08)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.5...atmosphere-project-1.0.0.beta1)

**Closed issues:**

- Allow AtmosphereHandler specific interceptors [\#409](https://github.com/Atmosphere/atmosphere/issues/409)

- AtmosphereInterceptorService's scope issue [\#408](https://github.com/Atmosphere/atmosphere/issues/408)

- Allow removal of pre-installed AtmosphereInterceptor [\#406](https://github.com/Atmosphere/atmosphere/issues/406)

- \[extra\] Add support for CometD support [\#404](https://github.com/Atmosphere/atmosphere/issues/404)

- \[runtime\] Add a BroadcastOnPost AtmosphereInterceptor [\#403](https://github.com/Atmosphere/atmosphere/issues/403)

- \[runtime\] Add support for automatic suspend/upgrade of AtmosphereResource based on the client protocol [\#398](https://github.com/Atmosphere/atmosphere/issues/398)

- \[socketio\] Add official documentation, add sample descrition [\#397](https://github.com/Atmosphere/atmosphere/issues/397)

- \[socketio\] Change Chat logic to use the same CSS/layout than other chat [\#396](https://github.com/Atmosphere/atmosphere/issues/396)

- \[socketio\] SimpleHttpProtocol warning with chat sample [\#395](https://github.com/Atmosphere/atmosphere/issues/395)

- \[doc\] Things I'd like to see documented [\#342](https://github.com/Atmosphere/atmosphere/issues/342)

- Write a sample for Atmosphere multi-request / Wicket integration [\#239](https://github.com/Atmosphere/atmosphere/issues/239)

- Bug \[atmosphere.js\]: long-polling, callback are getting always the whole responseText on readyState == 3 [\#86](https://github.com/Atmosphere/atmosphere/issues/86)

**Merged pull requests:**

- Socket.IO implementation [\#393](https://github.com/Atmosphere/atmosphere/pull/393) ([survivant](https://github.com/survivant))

- Socketio implementation [\#389](https://github.com/Atmosphere/atmosphere/pull/389) ([survivant](https://github.com/survivant))

## [atmosphere-project-0.9.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.5) (2012-05-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/default/master...atmosphere-project-0.9.5)

**Closed issues:**

- onMessage signature change dropped generics [\#391](https://github.com/Atmosphere/atmosphere/issues/391)

- Some wierd code in the wiki examples [\#390](https://github.com/Atmosphere/atmosphere/issues/390)

- \[gwt\] NullPointerException on resumeAfterDeath [\#388](https://github.com/Atmosphere/atmosphere/issues/388)

- \[websocket\] GlassFish's WebSocket implementation scope issue [\#386](https://github.com/Atmosphere/atmosphere/issues/386)

- \[runtime\] Remove Trackable concept and support [\#385](https://github.com/Atmosphere/atmosphere/issues/385)

- \[gwt\] Upgrade atmosphere-gwt to use 'Trackability Support' [\#179](https://github.com/Atmosphere/atmosphere/issues/179)

**Merged pull requests:**

- Don't check status until readyState == 4 \(IE bug\) [\#387](https://github.com/Atmosphere/atmosphere/pull/387) ([chris-martin](https://github.com/chris-martin))

## [default/master](https://github.com/Atmosphere/atmosphere/tree/default/master) (2012-05-29)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.4...default/master)

**Closed issues:**

- broadcasting in IE8  are not getting update after the first broadcast [\#384](https://github.com/Atmosphere/atmosphere/issues/384)

- broadcasting in IE8  are not getting update after the first broadcast [\#383](https://github.com/Atmosphere/atmosphere/issues/383)

- \[atmosphere.js\] Add a specific function for transport failure [\#382](https://github.com/Atmosphere/atmosphere/issues/382)

- \[runtime\] Add support for a MetaBroadcaster [\#381](https://github.com/Atmosphere/atmosphere/issues/381)

- \[runtime\] Generate an UUID for each AtmosphereResource and add a method to find them [\#379](https://github.com/Atmosphere/atmosphere/issues/379)

- Plugin JQuery not compatible with RequireJS [\#378](https://github.com/Atmosphere/atmosphere/issues/378)

- Enhancement : SSEAtmosphereInterceptor and JSONPAtmosphereInterceptor should be annoted [\#377](https://github.com/Atmosphere/atmosphere/issues/377)

- GWT demo fails in bc32ef6 [\#375](https://github.com/Atmosphere/atmosphere/issues/375)

- \[runtime\] Add AsyncSupportListener [\#373](https://github.com/Atmosphere/atmosphere/issues/373)

- Websocket wierd exceptions on disconnect [\#371](https://github.com/Atmosphere/atmosphere/issues/371)

- \[runtime\] Cookies issues [\#370](https://github.com/Atmosphere/atmosphere/issues/370)

- ClassCastException with Servlet 3.0 onTimeout [\#368](https://github.com/Atmosphere/atmosphere/issues/368)

- IllegalStateException: No SessionManager even when PROPERTY\_SESSION\_SUPPORT set to false [\#367](https://github.com/Atmosphere/atmosphere/issues/367)

- \[runtime\] Prevent AtmosphereFilter to intercept static resource [\#366](https://github.com/Atmosphere/atmosphere/issues/366)

- Disconnect Not Called On Tomcat7-Jersey [\#365](https://github.com/Atmosphere/atmosphere/issues/365)

- \[ webSocket.resource\(\) == null \] in the WebSocketHandler.onOpen\(WebSocket webSocket\) method  [\#364](https://github.com/Atmosphere/atmosphere/issues/364)

- \[atmosphere-jersey\] forward URL is broken [\#363](https://github.com/Atmosphere/atmosphere/issues/363)

- AtmosphereRequest may return invalid Session [\#361](https://github.com/Atmosphere/atmosphere/issues/361)

- \[atmosphere.js\] Allow disabling Atmosphere's Headers [\#360](https://github.com/Atmosphere/atmosphere/issues/360)

- Http11NioProtocol on Tomcat: AtmosphereResource's compound with Comet transports [\#359](https://github.com/Atmosphere/atmosphere/issues/359)

- java.lang.IncompatibleClassChangeError Using WebSockets on Jetty 8.1.3 [\#358](https://github.com/Atmosphere/atmosphere/issues/358)

- \[runtime\] URI mapping exception [\#357](https://github.com/Atmosphere/atmosphere/issues/357)

- broadcasting in IE8  stop working when moving to 0.9.4 [\#356](https://github.com/Atmosphere/atmosphere/issues/356)

- Error when writing with jetty [\#354](https://github.com/Atmosphere/atmosphere/issues/354)

- NullPointerException when version.properties is not present [\#346](https://github.com/Atmosphere/atmosphere/issues/346)

- IE8 & jquery.atmosphere.js: JS error if server connection is lost [\#331](https://github.com/Atmosphere/atmosphere/issues/331)

- \[jersey\] NPE during Broadcast [\#323](https://github.com/Atmosphere/atmosphere/issues/323)

- Add support for Annotated Atmosphere's Component [\#316](https://github.com/Atmosphere/atmosphere/issues/316)

- Add full WebSocket support for GWT [\#237](https://github.com/Atmosphere/atmosphere/issues/237)

- atmospherehandler timeout needs documentation [\#64](https://github.com/Atmosphere/atmosphere/issues/64)

**Merged pull requests:**

- avoid duplicate InterceptorService to be added [\#380](https://github.com/Atmosphere/atmosphere/pull/380) ([survivant](https://github.com/survivant))

- Fix for Servlet 3.0 ClassCastExceptions \(\#368\) [\#369](https://github.com/Atmosphere/atmosphere/pull/369) ([Gekkio](https://github.com/Gekkio))

- Socket.IO implementation [\#178](https://github.com/Atmosphere/atmosphere/pull/178) ([survivant](https://github.com/survivant))

## [atmosphere-project-0.9.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.4) (2012-05-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.7...atmosphere-project-0.9.4)

**Closed issues:**

- Simple long-poll example that doesn't reconnect on timeout [\#355](https://github.com/Atmosphere/atmosphere/issues/355)

- connection being  Close after Suspending - JBossWebCometSupport  [\#353](https://github.com/Atmosphere/atmosphere/issues/353)

- \[websocket\] WebSocketProtocol\#onOpen must be called before dispatch [\#352](https://github.com/Atmosphere/atmosphere/issues/352)

- \[atmosphere.js\] Problem with JSESSIONID and GlassFish [\#351](https://github.com/Atmosphere/atmosphere/issues/351)

- \[runtime\] AtmosphereRequest/Response Wrapper lack of setRequest/Response [\#350](https://github.com/Atmosphere/atmosphere/issues/350)

- Jetty and Websocket, warning abotu status code \>400 [\#349](https://github.com/Atmosphere/atmosphere/issues/349)

- NullPointerException from AtmosphereFramework\#setBroadcasterFactory [\#348](https://github.com/Atmosphere/atmosphere/issues/348)

- Undefined variables in jquery.atmosphere.js [\#347](https://github.com/Atmosphere/atmosphere/issues/347)

- AtmosphereInterceptor should be configurable [\#344](https://github.com/Atmosphere/atmosphere/issues/344)

- exception using spring mvc with MeteorServlet on jboss 6.1 [\#343](https://github.com/Atmosphere/atmosphere/issues/343)

- Package jquery.atmosphere.js in a jar [\#340](https://github.com/Atmosphere/atmosphere/issues/340)

- \[runtime\] Possible StackOverflow when using AsyncIOWriter and AtmosphereResource.write API [\#336](https://github.com/Atmosphere/atmosphere/issues/336)

- \[runtime\] AsyncProtocol should works without the needs of AsyncIOWriter [\#335](https://github.com/Atmosphere/atmosphere/issues/335)

- \[runtime\] Mapping Algorithm fail to map /a/x to /a [\#317](https://github.com/Atmosphere/atmosphere/issues/317)

- \[runtime\] Add support for auto discovering AtmosphereHandler in library [\#222](https://github.com/Atmosphere/atmosphere/issues/222)

- \[gwt\] AtmosphereCometHandler.cometTerminated never called [\#67](https://github.com/Atmosphere/atmosphere/issues/67)

## [atmosphere-project-0.8.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.7) (2012-05-07)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.3...atmosphere-project-0.8.7)

**Closed issues:**

- SerializationException: Too few tokens in RPC request [\#320](https://github.com/Atmosphere/atmosphere/issues/320)

**Merged pull requests:**

- Atmosphere 0.8.x [\#345](https://github.com/Atmosphere/atmosphere/pull/345) ([vasim](https://github.com/vasim))

## [atmosphere-project-0.9.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.3) (2012-05-07)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.2...atmosphere-project-0.9.3)

**Closed issues:**

- \[runtime\] JBossWebCometSupport EOF issue [\#318](https://github.com/Atmosphere/atmosphere/issues/318)

## [atmosphere-project-0.9.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.2) (2012-05-05)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.1...atmosphere-project-0.9.2)

**Closed issues:**

- \[atmosphere.js\] Add a AtmosphereRequest.reconnect attribute to be set and prevent reconnecting [\#341](https://github.com/Atmosphere/atmosphere/issues/341)

- \[websocket\] Expose Binary Write API [\#339](https://github.com/Atmosphere/atmosphere/issues/339)

- \[websocket\] Remove AsyncProtocol API, replace with WebSocketResponseFilter [\#338](https://github.com/Atmosphere/atmosphere/issues/338)

- Safari Error when connecting Websockets [\#337](https://github.com/Atmosphere/atmosphere/issues/337)

- \[atmosphere.js\] Add "ajax" transport support [\#334](https://github.com/Atmosphere/atmosphere/issues/334)

- \[atmosphere.js\] jsonp transport reconnect forever [\#333](https://github.com/Atmosphere/atmosphere/issues/333)

- Memory leak on Tomcat when websocket endpoint terminated [\#332](https://github.com/Atmosphere/atmosphere/issues/332)

- 'Original SevletRequest or wrapped original ServletRequest not passed to RequestDispatcher in violation of SRV.8.2 and SRV.14.2.5.1' [\#330](https://github.com/Atmosphere/atmosphere/issues/330)

- \[annotation\] Add contentType support to the Suspend annotation [\#329](https://github.com/Atmosphere/atmosphere/issues/329)

- AtmosphereRequest.getParameter should check for isNotNoOps\(\) [\#327](https://github.com/Atmosphere/atmosphere/issues/327)

- AtmosphereRequest.getParameter should check for isNotNoOps\(\) [\#326](https://github.com/Atmosphere/atmosphere/issues/326)

- \[websocket\] WebSocket + Comet issue using the Http11NioProtocol [\#325](https://github.com/Atmosphere/atmosphere/issues/325)

- \[websocket\] return a 501 instead of a 202 when WebSocket Handshake fail [\#324](https://github.com/Atmosphere/atmosphere/issues/324)

- load AtmosphereResourceConfig add a trim [\#322](https://github.com/Atmosphere/atmosphere/issues/322)

- AtmosphereFramework getFiles wrong validation [\#321](https://github.com/Atmosphere/atmosphere/issues/321)

- SerializationException: Too few tokens in RPC request [\#319](https://github.com/Atmosphere/atmosphere/issues/319)

- \[atmosphere.js\] FF 12 breaks WebSocket [\#315](https://github.com/Atmosphere/atmosphere/issues/315)

- \[websocket\] Improve WebSocketHandshakeFilter logic [\#313](https://github.com/Atmosphere/atmosphere/issues/313)

- \[atmosphere.js\] Expose the Request to the Response object [\#312](https://github.com/Atmosphere/atmosphere/issues/312)

- AtmosphereRequest.getSession returns null when session is set [\#311](https://github.com/Atmosphere/atmosphere/issues/311)

- \[atmosphere.js\] Reconnect function must always be invoked before reconnect [\#309](https://github.com/Atmosphere/atmosphere/issues/309)

- \[tomcat7\] Fix Tomcat + WebSocket + Nio Connector Support [\#308](https://github.com/Atmosphere/atmosphere/issues/308)

- \[v0.9.1\] error JSON parsing for sample rest-chat [\#307](https://github.com/Atmosphere/atmosphere/issues/307)

- \[runtime\] Add support for AtmosphereInterceptor [\#306](https://github.com/Atmosphere/atmosphere/issues/306)

- Tomcat 7.0.27 websocket onClose called every 60 seconds   [\#305](https://github.com/Atmosphere/atmosphere/issues/305)

- \[atmosphere.js\] Client JS should handle null fallbackTransport [\#304](https://github.com/Atmosphere/atmosphere/issues/304)

- Serializer will be ignored in case JersyBroadcaster in in use [\#303](https://github.com/Atmosphere/atmosphere/issues/303)

- \[runtime\] Add support for HTLM5 Server Side Events [\#302](https://github.com/Atmosphere/atmosphere/issues/302)

- Move GWTResponseWriter concepts to Atmosphre Core [\#73](https://github.com/Atmosphere/atmosphere/issues/73)

## [atmosphere-project-0.9.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.1) (2012-04-23)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9...atmosphere-project-0.9.1)

**Closed issues:**

- \[runtime\] NettyCometSupport must be detected at the very last [\#301](https://github.com/Atmosphere/atmosphere/issues/301)

- \[runtime\] NPE on SessionBroadcasterCache.cache\(\) [\#300](https://github.com/Atmosphere/atmosphere/issues/300)

- \[sample\] Add the Tiles/Spring samples [\#299](https://github.com/Atmosphere/atmosphere/issues/299)

- \[Guice\] GuiceManagedAtmosphereServlet broken [\#296](https://github.com/Atmosphere/atmosphere/issues/296)

- \[runtime\] Expose atmosphere.xml path in AtmosphereFramework [\#295](https://github.com/Atmosphere/atmosphere/issues/295)

- WebSocket enabled but implementing different transport protocol [\#294](https://github.com/Atmosphere/atmosphere/issues/294)

- atmosphere-jquery socket.subscribe\(\) succeeds although server returns 500 [\#293](https://github.com/Atmosphere/atmosphere/issues/293)

- \[websocket\]\[firefox11\] Wrong handling of Connection: keep-alive, Upgrade. [\#292](https://github.com/Atmosphere/atmosphere/issues/292)

- \[jgroups\] \*.xml not added to the jar, default constructor missed breaking @Cluster [\#290](https://github.com/Atmosphere/atmosphere/issues/290)

- \[websocket\] \[Tomcat\] One or more reserved bits are on: reserved1 = 0, reserved2 = 1, reserved3 = 1 [\#289](https://github.com/Atmosphere/atmosphere/issues/289)

- \[websocket\] ConcurrentModification Exception when protocol use asyncDispatch\(true\) [\#288](https://github.com/Atmosphere/atmosphere/issues/288)

- Tomcat Error: "getWriter\(\) has already been called..." w/ Spring Security, Spring MVC, MeteorServlet [\#287](https://github.com/Atmosphere/atmosphere/issues/287)

- Add jquery.atmosphere.js connection error handling callbacks [\#285](https://github.com/Atmosphere/atmosphere/issues/285)

- AtmosphereRequest.getSession returns null when it shouldn't \(Spring Security\) [\#284](https://github.com/Atmosphere/atmosphere/issues/284)

- Tomcat7 websocket support blows up with spring security enabled [\#283](https://github.com/Atmosphere/atmosphere/issues/283)

- \[websocket\] Basic Authentication? [\#281](https://github.com/Atmosphere/atmosphere/issues/281)

- \[tomcat\] Sprint exception with WebSocket [\#280](https://github.com/Atmosphere/atmosphere/issues/280)

- \[atmosphere.js\] Firefox Bug: getAllResponseHeaders\(\) returns empty String [\#273](https://github.com/Atmosphere/atmosphere/issues/273)

**Merged pull requests:**

- Restore session timeout after resource resume/timeout/cancel. [\#279](https://github.com/Atmosphere/atmosphere/pull/279) ([mbezjak](https://github.com/mbezjak))

## [atmosphere-project-0.9](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9) (2012-04-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.0.RC3...atmosphere-project-0.9)

**Closed issues:**

- \[websocket\] \[regression\] Safari supports broken [\#278](https://github.com/Atmosphere/atmosphere/issues/278)

- \[runtime\] Add writeOnTimeout API to AtmosphereResource [\#276](https://github.com/Atmosphere/atmosphere/issues/276)

- \[websocket\] Add support for suspend\(timeout\) semantic [\#275](https://github.com/Atmosphere/atmosphere/issues/275)

- \[atmosphere.js\] long-polling issue with jQuery Atmosphere 0.9 RC3 [\#274](https://github.com/Atmosphere/atmosphere/issues/274)

- \[runtime\] Deprecate Jetty 7.4.x and 8.0.0 Mx Websocket support [\#272](https://github.com/Atmosphere/atmosphere/issues/272)

- \[runtime\] Deprecate WebLogic support. [\#271](https://github.com/Atmosphere/atmosphere/issues/271)

- Problem with Meteor.resumeOnBroadcast [\#269](https://github.com/Atmosphere/atmosphere/issues/269)

- IE does not reconnect streaming http connections [\#268](https://github.com/Atmosphere/atmosphere/issues/268)

- \[runtime\] Do not send 503 on connection closed detection [\#267](https://github.com/Atmosphere/atmosphere/issues/267)

- \[atmosphere.js\] Support connect timeout [\#266](https://github.com/Atmosphere/atmosphere/issues/266)

- withCredentials support in doRequest \#2 [\#265](https://github.com/Atmosphere/atmosphere/issues/265)

- \[performance\] Reduce the wrapping of Request [\#263](https://github.com/Atmosphere/atmosphere/issues/263)

- \[jersey\]\[0.9.0.RC2\] Not all parameters are applied by pushing with websockit [\#261](https://github.com/Atmosphere/atmosphere/issues/261)

- \[jersey\]\[0.9.0.RC2\] The WebSocketProtocol.configure\(\) method is never call if custom WebSocketProtocol is assigned in the web.xml   [\#260](https://github.com/Atmosphere/atmosphere/issues/260)

- \[documentation\] Need a migration guide for 0.x to 0.9 new API [\#246](https://github.com/Atmosphere/atmosphere/issues/246)

- \[runtime\] Add support for Tomcat WebSocket [\#195](https://github.com/Atmosphere/atmosphere/issues/195)

- \[Jersey\] Add support for ExecutionContext API [\#182](https://github.com/Atmosphere/atmosphere/issues/182)

## [atmosphere-project-0.9.0.RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.0.RC3) (2012-03-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.0.RC2...atmosphere-project-0.9.0.RC3)

**Closed issues:**

- \[Jersey\] \[0.9.0.RC2\] NPE during debugging [\#259](https://github.com/Atmosphere/atmosphere/issues/259)

- \[guice\] \[regression\] Initialization logic broken [\#258](https://github.com/Atmosphere/atmosphere/issues/258)

- \[atmosphere.js\] \[regression\] Re-add $.atmosphere.publish API [\#257](https://github.com/Atmosphere/atmosphere/issues/257)

- \[atmosphere.js\] attachHeaderAsQueryString set to true by default [\#256](https://github.com/Atmosphere/atmosphere/issues/256)

- \[runtime\] Rename CometSupport -\> AsyncSupport [\#255](https://github.com/Atmosphere/atmosphere/issues/255)

- \[Jersey\] \[0.9-SNAPSHOT\] NPE [\#254](https://github.com/Atmosphere/atmosphere/issues/254)

- \[runtime\] Future returned by Broadcast\#broadcast\(Object, Set\) doesn't block properly on get [\#253](https://github.com/Atmosphere/atmosphere/issues/253)

- \[runtime\] Improve Broadcaster fluid API [\#252](https://github.com/Atmosphere/atmosphere/issues/252)

- withCredentials support in doRequest [\#251](https://github.com/Atmosphere/atmosphere/issues/251)

- \[atmosphere.js\] \[regression\] Unable to override reconnect method/data [\#250](https://github.com/Atmosphere/atmosphere/issues/250)

- \[runtime\] Add support for CacheMessage ID [\#248](https://github.com/Atmosphere/atmosphere/issues/248)

- onStateChange not called when client disconnects [\#247](https://github.com/Atmosphere/atmosphere/issues/247)

- AtmosphereResponse.DummyHttpServletResponse\#flushBuffer throws UnsupportedOperationException [\#245](https://github.com/Atmosphere/atmosphere/issues/245)

- Atmosphere&Jersey: method of PerRequestBroadcastFilter is not called  [\#244](https://github.com/Atmosphere/atmosphere/issues/244)

- Atmosphere&Jersey: interesting behavior with JSON support powered by Jersey [\#243](https://github.com/Atmosphere/atmosphere/issues/243)

- Atmosphere&Jersey: Problem with using the session [\#242](https://github.com/Atmosphere/atmosphere/issues/242)

- \[runtime\] JBoss7 initialization issue [\#240](https://github.com/Atmosphere/atmosphere/issues/240)

- \[websocket\] Attributes must not be cleaned when AtmosphereRequest.isDestroyable return true [\#231](https://github.com/Atmosphere/atmosphere/issues/231)

- \[runtime\]\[jetty\] Session lost on first request [\#230](https://github.com/Atmosphere/atmosphere/issues/230)

- \[atmosphere.js\] Redesign client to support passing function for event [\#227](https://github.com/Atmosphere/atmosphere/issues/227)

- \[atmosphere.js\] Script must survive callback error [\#210](https://github.com/Atmosphere/atmosphere/issues/210)

- Json data that I pass using atmosphere sometimes is corrupted [\#200](https://github.com/Atmosphere/atmosphere/issues/200)

- jQuery.atmosphere.request.executeCallbackBeforeReconnect inverse behavior [\#122](https://github.com/Atmosphere/atmosphere/issues/122)

- DefaultBroadcaster.push contains recursive error [\#115](https://github.com/Atmosphere/atmosphere/issues/115)

- \[atmosphere.js\] Add support for TrackMessageSize [\#70](https://github.com/Atmosphere/atmosphere/issues/70)

## [atmosphere-project-0.9.0.RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.0.RC2) (2012-03-23)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.0.RC1...atmosphere-project-0.9.0.RC2)

**Closed issues:**

- Jersey's @HeaderParam no more injected with WebSocket [\#238](https://github.com/Atmosphere/atmosphere/issues/238)

- \[extras\] Stop supporting GrizzlyAdapter [\#236](https://github.com/Atmosphere/atmosphere/issues/236)

- \[runtime\] AtmosphereRequest/Response must not extends HttpServlet\*\*Wrapper [\#235](https://github.com/Atmosphere/atmosphere/issues/235)

- \[jersey\] Broadcast annotation must respect the writeEntity value [\#234](https://github.com/Atmosphere/atmosphere/issues/234)

- \[runtime\] Skip auto-discovering of AtmosphereHandler is already specified [\#233](https://github.com/Atmosphere/atmosphere/issues/233)

- Null contentType when suspending response [\#218](https://github.com/Atmosphere/atmosphere/issues/218)

- Jetty8WebSocket - 405 Method Not Allowed [\#208](https://github.com/Atmosphere/atmosphere/issues/208)

- \[jersey\] @Singleton fail to initialize [\#207](https://github.com/Atmosphere/atmosphere/issues/207)

- Cannot instantiate 2 JGroupsFilters w/ default constructor [\#205](https://github.com/Atmosphere/atmosphere/issues/205)

- Improve client broken connection detection  [\#198](https://github.com/Atmosphere/atmosphere/issues/198)

- NPE in AbstractReflectorAtmosphereHandler [\#180](https://github.com/Atmosphere/atmosphere/issues/180)

## [atmosphere-project-0.9.0.RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.0.RC1) (2012-03-16)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.9.0.alpha.v20120301...atmosphere-project-0.9.0.RC1)

**Closed issues:**

- \[regression\] \[runtime\] MeteorServlet Broken [\#229](https://github.com/Atmosphere/atmosphere/issues/229)

- \[regression\] \[runtime\] Some components not loaded by the MeteorServlet [\#228](https://github.com/Atmosphere/atmosphere/issues/228)

- \[atmosphere.js\] regression with 0.9: push method should take request as param [\#226](https://github.com/Atmosphere/atmosphere/issues/226)

- \[atmosphere.js\] Fires 'opening' event for transport other than Websocket [\#225](https://github.com/Atmosphere/atmosphere/issues/225)

- \[runtime\] Add .transport\(\) method to AtmosphereResource [\#224](https://github.com/Atmosphere/atmosphere/issues/224)

- \[websocket\] onControl must not be propagated to AtmosphereHandler [\#223](https://github.com/Atmosphere/atmosphere/issues/223)

- \[runtime\] IE 9 issues client/server issues with streaming [\#221](https://github.com/Atmosphere/atmosphere/issues/221)

- \[runtime\] Allow WebSocketProtocol to dispatch asynchronously [\#220](https://github.com/Atmosphere/atmosphere/issues/220)

- \[runtime\] OSGi - combat bundles - import package optional [\#219](https://github.com/Atmosphere/atmosphere/issues/219)

- AmosphereServlet mapping and relative @Path? [\#217](https://github.com/Atmosphere/atmosphere/issues/217)

- Filters defined in the web.xml are not applied to the MeteorServlet [\#216](https://github.com/Atmosphere/atmosphere/issues/216)

- jquery-pubsub 0.8.6/0.9 first push seems to be incorrect [\#215](https://github.com/Atmosphere/atmosphere/issues/215)

- \[runtime\] Make AtmosphereResource API fluid [\#214](https://github.com/Atmosphere/atmosphere/issues/214)

- \[runtime\] DefaultBroadcaster must never return null instead of Future [\#213](https://github.com/Atmosphere/atmosphere/issues/213)

- Atmosphere-meteor bug with IE 9. [\#209](https://github.com/Atmosphere/atmosphere/issues/209)

- \[websocket\] Add support for simple WebSocketHandler [\#206](https://github.com/Atmosphere/atmosphere/issues/206)

- Incorrect Access-Control-Allow-Origin Headers on Credentialed CORS request [\#204](https://github.com/Atmosphere/atmosphere/issues/204)

- \[runtime\] Get rid of Generic HttpServlet\* type [\#196](https://github.com/Atmosphere/atmosphere/issues/196)

- \[runtime\] Possible deadlock on unsubscribe [\#188](https://github.com/Atmosphere/atmosphere/issues/188)

- jquery pubsub behaves differently using long-polling and websocket  [\#187](https://github.com/Atmosphere/atmosphere/issues/187)

- Improve OSGi metadata [\#91](https://github.com/Atmosphere/atmosphere/issues/91)

- close event : NULL vs EMPTY [\#77](https://github.com/Atmosphere/atmosphere/issues/77)

- New wildcard mapping missing /a/b/\*  [\#51](https://github.com/Atmosphere/atmosphere/issues/51)

**Merged pull requests:**

- GWT bug fix [\#212](https://github.com/Atmosphere/atmosphere/pull/212) ([markathomas](https://github.com/markathomas))

- Atmosphere 0.8.x [\#211](https://github.com/Atmosphere/atmosphere/pull/211) ([markathomas](https://github.com/markathomas))

## [atmosphere-project-0.9.0.alpha.v20120301](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.9.0.alpha.v20120301) (2012-03-01)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.6...atmosphere-project-0.9.0.alpha.v20120301)

**Closed issues:**

- Atmosphere 0.8.3 and bug in sample samples\jquery-pubsub\  [\#153](https://github.com/Atmosphere/atmosphere/issues/153)

- RPGGameDemo does not work.. [\#35](https://github.com/Atmosphere/atmosphere/issues/35)

## [atmosphere-project-0.8.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.6) (2012-03-01)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.5...atmosphere-project-0.8.6)

**Closed issues:**

- \[runtime\] Tomcat 6 disconnection detection broken [\#203](https://github.com/Atmosphere/atmosphere/issues/203)

- SCRIPT16389: Unspecified error in 0.8.5 and 0.9 [\#197](https://github.com/Atmosphere/atmosphere/issues/197)

- \[runtime\] \[api-break\] Fix PerRequestFilter dependencies on HttpServlet\* classes [\#193](https://github.com/Atmosphere/atmosphere/issues/193)

- \[runtime\] Allow multiple instance of the same BroadcastFilter [\#192](https://github.com/Atmosphere/atmosphere/issues/192)

- undefined 'headers' in jquery.atmosphere.js v0.9 [\#191](https://github.com/Atmosphere/atmosphere/issues/191)

- \[guice, websockets\] WebSocketProtocol implementation does not get DI [\#190](https://github.com/Atmosphere/atmosphere/issues/190)

- \[guice, websockets\] The ResourceConfig instance does not contain any root resource classes [\#189](https://github.com/Atmosphere/atmosphere/issues/189)

- \[runtime\] AtmosphereServlet must throw AtmosphereMappingException instead of SevletException when mapping fail [\#186](https://github.com/Atmosphere/atmosphere/issues/186)

- Add support for the Netty Framework [\#185](https://github.com/Atmosphere/atmosphere/issues/185)

- \[Jersey\] Add support for customizable ContainerResponseWriter [\#183](https://github.com/Atmosphere/atmosphere/issues/183)

- \[websocket\] Do not Destroy AtmosphereRequest/Response after delegating the original request [\#181](https://github.com/Atmosphere/atmosphere/issues/181)

- AtmosphereConfig should be exposed to Framework [\#120](https://github.com/Atmosphere/atmosphere/issues/120)

- getInitParameterNames should return also the local param [\#113](https://github.com/Atmosphere/atmosphere/issues/113)

- All web.xml init-params must be configurable using atmosphere.xml [\#41](https://github.com/Atmosphere/atmosphere/issues/41)

**Merged pull requests:**

- Check if resource is alive before using it in GWT module [\#202](https://github.com/Atmosphere/atmosphere/pull/202) ([markathomas](https://github.com/markathomas))

- Add another check to ensure resource is alive [\#201](https://github.com/Atmosphere/atmosphere/pull/201) ([markathomas](https://github.com/markathomas))

- Check if resource is alive before using it in GWT module [\#199](https://github.com/Atmosphere/atmosphere/pull/199) ([markathomas](https://github.com/markathomas))

- remove @override annotation in samples causing Eclipse compilation errors [\#184](https://github.com/Atmosphere/atmosphere/pull/184) ([survivant](https://github.com/survivant))

- AtmosphereConfig refactoring [\#177](https://github.com/Atmosphere/atmosphere/pull/177) ([survivant](https://github.com/survivant))

## [atmosphere-project-0.8.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.5) (2012-02-06)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.4...atmosphere-project-0.8.5)

**Closed issues:**

- better support to track headers for extensions [\#175](https://github.com/Atmosphere/atmosphere/issues/175)

- Force message caching event if the request went out of scope [\#173](https://github.com/Atmosphere/atmosphere/issues/173)

- Possible NPE with @Asynchronous [\#172](https://github.com/Atmosphere/atmosphere/issues/172)

- \[long-polling\] Message losts during concurrent suspend/broadcast [\#170](https://github.com/Atmosphere/atmosphere/issues/170)

- JerseyBroadcasterUtil must not flush its content with aggregated response [\#169](https://github.com/Atmosphere/atmosphere/issues/169)

- \[WebSocket\] GlassFish 3.1.2 b17 and up broken [\#167](https://github.com/Atmosphere/atmosphere/issues/167)

- Support proxy that lowercase headers and query string [\#166](https://github.com/Atmosphere/atmosphere/issues/166)

- Compile error for javax 2.5 dependent api [\#165](https://github.com/Atmosphere/atmosphere/issues/165)

- \[long-polling\] SuspendBuilder produces java.lang.IllegalStateException: Request object no longer valid. This object has been cancelled  [\#164](https://github.com/Atmosphere/atmosphere/issues/164)

- \[WebSocketProtocol\] Allow bacth request processing [\#163](https://github.com/Atmosphere/atmosphere/issues/163)

- java.lang.NoClassDefFoundError: org/apache/catalina/comet/CometProcessor [\#162](https://github.com/Atmosphere/atmosphere/issues/162)

- JMSBroadcaster is misusing JMS session objects [\#161](https://github.com/Atmosphere/atmosphere/issues/161)

- Add support for Hazelcast [\#159](https://github.com/Atmosphere/atmosphere/issues/159)

- Prevent Jetty from sending Blob instead of String [\#158](https://github.com/Atmosphere/atmosphere/issues/158)

- Unsubscribe doesn't work if you subscribed to multiple request [\#90](https://github.com/Atmosphere/atmosphere/issues/90)

- too many connections on long-polling and readyState==3 [\#87](https://github.com/Atmosphere/atmosphere/issues/87)

- Add feature to intercept broadcasted message to AtmosphereHandler ? [\#76](https://github.com/Atmosphere/atmosphere/issues/76)

- Better X-Cache support [\#71](https://github.com/Atmosphere/atmosphere/issues/71)

- jquery.atmosphere.js add callback when websocket is really open [\#47](https://github.com/Atmosphere/atmosphere/issues/47)

**Merged pull requests:**

- Fix for 149 [\#168](https://github.com/Atmosphere/atmosphere/pull/168) ([mattnathan](https://github.com/mattnathan))

- AtmosphereConfig refactoring [\#176](https://github.com/Atmosphere/atmosphere/pull/176) ([survivant](https://github.com/survivant))

- Socket.IO pull request : included merge with Atmosphere 0.9 [\#174](https://github.com/Atmosphere/atmosphere/pull/174) ([survivant](https://github.com/survivant))

- Integration of Socket.IO into Atmosphere and AtmosphereConfig refactoring [\#171](https://github.com/Atmosphere/atmosphere/pull/171) ([survivant](https://github.com/survivant))

- In the websocket.onopen function: invoke the callback at the the very end [\#52](https://github.com/Atmosphere/atmosphere/pull/52) ([GerjanOnline](https://github.com/GerjanOnline))

## [atmosphere-project-0.8.4](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.4) (2012-01-18)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.3...atmosphere-project-0.8.4)

**Closed issues:**

- BlockingIOCometSupport issue with Session [\#156](https://github.com/Atmosphere/atmosphere/issues/156)

- Deadlock with Tomcat 7 close detection [\#155](https://github.com/Atmosphere/atmosphere/issues/155)

- Improve GlassFish 3.1.2 WebSocket implementation [\#154](https://github.com/Atmosphere/atmosphere/issues/154)

- Improve @Async annotation with support for query string [\#152](https://github.com/Atmosphere/atmosphere/issues/152)

- Add support for unsubscribe event in atmosphere.js [\#151](https://github.com/Atmosphere/atmosphere/issues/151)

- jquery.atmosphere scope request with JSONP [\#150](https://github.com/Atmosphere/atmosphere/issues/150)

- JMSBroadcaster JMS Session is null, every time [\#149](https://github.com/Atmosphere/atmosphere/issues/149)

- Improve the WebSocketHanshakeFilter to drop Firefox invalid websocket request [\#148](https://github.com/Atmosphere/atmosphere/issues/148)

- JSONP transport broken for content-type == null [\#147](https://github.com/Atmosphere/atmosphere/issues/147)

- Tomcat7CometSupport doesn't detect closed connections [\#146](https://github.com/Atmosphere/atmosphere/issues/146)

- java.lang.IllegalStateException: Request object no longer valid [\#144](https://github.com/Atmosphere/atmosphere/issues/144)

- Problem with IE9 connection closed [\#82](https://github.com/Atmosphere/atmosphere/issues/82)

**Merged pull requests:**

- Fix for issues \#90 [\#96](https://github.com/Atmosphere/atmosphere/pull/96) ([mjeanroy](https://github.com/mjeanroy))

## [atmosphere-project-0.8.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.3) (2012-01-12)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.2...atmosphere-project-0.8.3)

**Closed issues:**

- WebSocket\#OnClose doesn't call the callback [\#143](https://github.com/Atmosphere/atmosphere/issues/143)

- HttpSession not available in doPost [\#142](https://github.com/Atmosphere/atmosphere/issues/142)

- Allow Atmosphere Jersey to not write back the entity on the calling connection [\#141](https://github.com/Atmosphere/atmosphere/issues/141)

- Broadcaster\#awaitAndBroadcast is broken [\#140](https://github.com/Atmosphere/atmosphere/issues/140)

- when a request timedout request is closed before the listeners are called [\#139](https://github.com/Atmosphere/atmosphere/issues/139)

- Add a Filter that use can configure to downgrade old WebSocket Implementation [\#138](https://github.com/Atmosphere/atmosphere/issues/138)

- JSONP Transport filter should use array when content-type is null [\#137](https://github.com/Atmosphere/atmosphere/issues/137)

- Apache Shiro + Spring Classloader delegate issue with Tomcat 7 [\#136](https://github.com/Atmosphere/atmosphere/issues/136)

- \[regression\] JSONP transport for IE broken [\#135](https://github.com/Atmosphere/atmosphere/issues/135)

## [atmosphere-project-0.8.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.2) (2011-12-21)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.1...atmosphere-project-0.8.2)

**Closed issues:**

- Reduce memory footprint of AtmosphereResourceImpl, AtmosphereRequest and the grap when a connection is suspended [\#134](https://github.com/Atmosphere/atmosphere/issues/134)

- Clear ContainerResource.entity after Broadcast to reduce memory footprint [\#133](https://github.com/Atmosphere/atmosphere/issues/133)

- Improve Jetty7CometSupport by using ContinuationListener API [\#132](https://github.com/Atmosphere/atmosphere/issues/132)

- Fix regression in path mapping [\#131](https://github.com/Atmosphere/atmosphere/issues/131)

- RedisFilter broadcast called twice [\#130](https://github.com/Atmosphere/atmosphere/issues/130)

- Add a new Production mode for heavy load [\#129](https://github.com/Atmosphere/atmosphere/issues/129)

- WebSocket/SimpleHttpProtocol Implementation takes too much memory, force Protocol implementation to be stateless [\#128](https://github.com/Atmosphere/atmosphere/issues/128)

- DefaultBroadcaster will lost message on a session invalidated [\#127](https://github.com/Atmosphere/atmosphere/issues/127)

- jquery.atmosphere.js invokes callback for non-finished responses [\#126](https://github.com/Atmosphere/atmosphere/issues/126)

- Reduce SimpleBroadcaster Footprint [\#125](https://github.com/Atmosphere/atmosphere/issues/125)

- Fix conflicts between closeDetector and WebSocket idle timeout [\#124](https://github.com/Atmosphere/atmosphere/issues/124)

- jQuery.atmosphere.reconnect\(\) should check if still subscribed [\#123](https://github.com/Atmosphere/atmosphere/issues/123)

- Do not suspend the connection when a Broadcast occurs and resumeOnBroadcast is true [\#121](https://github.com/Atmosphere/atmosphere/issues/121)

- Close idle connection detector is not closing WebSocket, causing leaks [\#118](https://github.com/Atmosphere/atmosphere/issues/118)

- NPE when Entry.originalMessage is null [\#117](https://github.com/Atmosphere/atmosphere/issues/117)

- Add support for @Asynchronous annotation for asynchronous Jersey resource execution [\#116](https://github.com/Atmosphere/atmosphere/issues/116)

- Jersey's AtmosphereFilter leaks AtmosphereResource with WebSocket [\#114](https://github.com/Atmosphere/atmosphere/issues/114)

- Do not invoke listener twice when remote connection occurs [\#112](https://github.com/Atmosphere/atmosphere/issues/112)

- TrackableSession leaks memory [\#111](https://github.com/Atmosphere/atmosphere/issues/111)

- Do not close Tomcat's CometEvent when the input stream is fully read [\#110](https://github.com/Atmosphere/atmosphere/issues/110)

- Add support for a Broadcaster.awaitAndBroadcast method [\#109](https://github.com/Atmosphere/atmosphere/issues/109)

- Possible NPE with SimpleBroadcaster [\#108](https://github.com/Atmosphere/atmosphere/issues/108)

- Enable WebSocket support by default [\#106](https://github.com/Atmosphere/atmosphere/issues/106)

- DefaultBroadcast must interupt broadcast when Callable throw an exception [\#105](https://github.com/Atmosphere/atmosphere/issues/105)

- XDR incorrectly reconnect [\#102](https://github.com/Atmosphere/atmosphere/issues/102)

- PerBroadcastFilter must not get invoked with Callable [\#101](https://github.com/Atmosphere/atmosphere/issues/101)

- Broadcaster Callable message must be executed before getting cached [\#99](https://github.com/Atmosphere/atmosphere/issues/99)

- Wrong default Broadcaster Class in BroadcasterFactory when Jersey is auto-detected [\#98](https://github.com/Atmosphere/atmosphere/issues/98)

- AsyncWrite and Broadcast WAIT Thread [\#97](https://github.com/Atmosphere/atmosphere/issues/97)

- Create Spring Injector [\#93](https://github.com/Atmosphere/atmosphere/issues/93)

- Add support for JSONP transport [\#92](https://github.com/Atmosphere/atmosphere/issues/92)

- Regression: Jetty 7.x java.lang.NoSuchMethodError with WebSocket [\#89](https://github.com/Atmosphere/atmosphere/issues/89)

-  FeatureRequest: use cache-layer before filtering [\#88](https://github.com/Atmosphere/atmosphere/issues/88)

- AtmosphereServlet issues with IE headers and method other than GET and POST [\#85](https://github.com/Atmosphere/atmosphere/issues/85)

-  \(small\) Improvement: BroadcasterCacheBase\#reaper as a static instance [\#84](https://github.com/Atmosphere/atmosphere/issues/84)

- BroadcasterLifeCyclePolicyListener\#onDestroy\(\) should be called on calling broadcaster\#destroy\(\) explicitly [\#83](https://github.com/Atmosphere/atmosphere/issues/83)

- Long-polling / jersey "bug" [\#81](https://github.com/Atmosphere/atmosphere/issues/81)

-  No AtmosphereHandler maps request for /stream/\*/\* [\#79](https://github.com/Atmosphere/atmosphere/issues/79)

- WebSocket.Event broadcasted as String. [\#78](https://github.com/Atmosphere/atmosphere/issues/78)

- EMPTY\_DESTROY -broadcasterLifeCyclePolicy causes error in grizzly [\#57](https://github.com/Atmosphere/atmosphere/issues/57)

- Lifecycle policy scheduler needs to be modified based on activity [\#54](https://github.com/Atmosphere/atmosphere/issues/54)

- jetty websocket request getSession\(\) : java.lang.IllegalStateException: No SessionManager [\#45](https://github.com/Atmosphere/atmosphere/issues/45)

**Merged pull requests:**

- Fix for issue \#54: Lifecycle policy scheduler needs to be modified based on activity [\#119](https://github.com/Atmosphere/atmosphere/pull/119) ([azell](https://github.com/azell))

- Use jQuery.stringifyJSON rather than JSON.stringify [\#107](https://github.com/Atmosphere/atmosphere/pull/107) ([flowersinthesand](https://github.com/flowersinthesand))

- Update headers from my last pull request. [\#104](https://github.com/Atmosphere/atmosphere/pull/104) ([jragingfury](https://github.com/jragingfury))

- Fix race condition in DefaultBroadcasterFactory in Atmosphere 0.8.x [\#100](https://github.com/Atmosphere/atmosphere/pull/100) ([jragingfury](https://github.com/jragingfury))

- Backport of Spring Injector into Atmosphere 0.8.x branch [\#95](https://github.com/Atmosphere/atmosphere/pull/95) ([jragingfury](https://github.com/jragingfury))

- Create Spring Injector [\#94](https://github.com/Atmosphere/atmosphere/pull/94) ([jragingfury](https://github.com/jragingfury))

- Update headers from my last pull request. [\#103](https://github.com/Atmosphere/atmosphere/pull/103) ([jragingfury](https://github.com/jragingfury))

- Added support for Prototype. [\#17](https://github.com/Atmosphere/atmosphere/pull/17) ([fhackenberger](https://github.com/fhackenberger))

## [atmosphere-project-0.8.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.1) (2011-11-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.0...atmosphere-project-0.8.1)

## [atmosphere-project-0.8.0](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.0) (2011-11-24)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.0-RC3...atmosphere-project-0.8.0)

**Closed issues:**

- Cannot define one callback per request  [\#75](https://github.com/Atmosphere/atmosphere/issues/75)

- Wicket-Ajax-Rendering in Websocket Thread. \(Jetty8\) [\#69](https://github.com/Atmosphere/atmosphere/issues/69)

- Call to unsubscribe schould remove callback [\#62](https://github.com/Atmosphere/atmosphere/issues/62)

- Meteor aren't recycled on DISCONNECT [\#60](https://github.com/Atmosphere/atmosphere/issues/60)

- Initialization problem when embedded and AtmosphereServlet not started [\#58](https://github.com/Atmosphere/atmosphere/issues/58)

- SCOPE == REQUEST and Meteor creates 2 broadcasters [\#56](https://github.com/Atmosphere/atmosphere/issues/56)

- Warning when using atmosphere-jgroups [\#55](https://github.com/Atmosphere/atmosphere/issues/55)

- WebSocket API writeError and redirect not implemented [\#53](https://github.com/Atmosphere/atmosphere/issues/53)

- WebSocketHttpServletResponse methods needs to be fully implemented [\#50](https://github.com/Atmosphere/atmosphere/issues/50)

- memory leak when running 2 atmosphere instances in one VM [\#43](https://github.com/Atmosphere/atmosphere/issues/43)

**Merged pull requests:**

- Fixed compile errors for jetty 7.x with javax 2.5. [\#74](https://github.com/Atmosphere/atmosphere/pull/74) ([haed](https://github.com/haed))

- Ensure that setID is called before the broadcast handler is executed.  O... [\#61](https://github.com/Atmosphere/atmosphere/pull/61) ([azell](https://github.com/azell))

- RedisBroadcaster changes [\#59](https://github.com/Atmosphere/atmosphere/pull/59) ([azell](https://github.com/azell))

## [atmosphere-project-0.8.0-RC3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.0-RC3) (2011-11-02)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.0-RC2...atmosphere-project-0.8.0-RC3)

**Closed issues:**

- WebSocketHttpServletResponse.encodeUrl\(String\) and friend should not throw exception [\#49](https://github.com/Atmosphere/atmosphere/issues/49)

- resolving AtmosphereHandlers needs to support deep-path wildcards [\#48](https://github.com/Atmosphere/atmosphere/issues/48)

- jquery.atmosphere.js cosmetic change websocket.onopen [\#46](https://github.com/Atmosphere/atmosphere/issues/46)

## [atmosphere-project-0.8.0-RC2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.0-RC2) (2011-10-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.8.0-RC1...atmosphere-project-0.8.0-RC2)

**Closed issues:**

- is it possible to hide this exception on Jetty shutdown. [\#44](https://github.com/Atmosphere/atmosphere/issues/44)

- AtmosphereResourceImpl.atmosphereResourceEventListener -\> getAtmosphereResourceEventListener [\#42](https://github.com/Atmosphere/atmosphere/issues/42)

- Jquery Pub/Sub with multiple channel hangs  [\#40](https://github.com/Atmosphere/atmosphere/issues/40)

- Glassfish 3.1.1 invalid connection header [\#38](https://github.com/Atmosphere/atmosphere/issues/38)

- Wrong TOMCAT\_7 class name in DefaultCometSupportResolver ? [\#36](https://github.com/Atmosphere/atmosphere/issues/36)

- Examples / push / chat does not work out of the box [\#34](https://github.com/Atmosphere/atmosphere/issues/34)

- NPE if header-param not set [\#31](https://github.com/Atmosphere/atmosphere/issues/31)

- jquery-pubsub: Websocket transport fails [\#28](https://github.com/Atmosphere/atmosphere/issues/28)

- could this be a race condition? [\#23](https://github.com/Atmosphere/atmosphere/issues/23)

- Configuration file settings are different for websocket vs. comet use [\#14](https://github.com/Atmosphere/atmosphere/issues/14)

- Minimum packet size for google chrome [\#13](https://github.com/Atmosphere/atmosphere/issues/13)

- Unterminated broadcaster threads [\#12](https://github.com/Atmosphere/atmosphere/issues/12)

**Merged pull requests:**

- Added better message reasons during websocket.onclose where a String reason isn't provided [\#37](https://github.com/Atmosphere/atmosphere/pull/37) ([sroebuck](https://github.com/sroebuck))

- mvn fails to build from trunk because Config static variables were moved and certain Test classes were not updated [\#39](https://github.com/Atmosphere/atmosphere/pull/39) ([evdubs](https://github.com/evdubs))

## [atmosphere-project-0.8.0-RC1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.8.0-RC1) (2011-10-03)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.7.2...atmosphere-project-0.8.0-RC1)

**Closed issues:**

- atmosphere.jquery.js not working for newer versions of Firefox / Mozilla [\#22](https://github.com/Atmosphere/atmosphere/issues/22)

- Incompatibility between atmosphere releases and jetty releases not documented [\#20](https://github.com/Atmosphere/atmosphere/issues/20)

- Path to /Meteor incorrect in meteor-chat sample code [\#19](https://github.com/Atmosphere/atmosphere/issues/19)

- jqeury plugin gives problem at ie9 [\#15](https://github.com/Atmosphere/atmosphere/issues/15)

- Inconsistent transport implementation [\#11](https://github.com/Atmosphere/atmosphere/issues/11)

**Merged pull requests:**

- if jersey classes are found in the classpath and atmosphere-jersey module is not include, it won't crash [\#33](https://github.com/Atmosphere/atmosphere/pull/33) ([survivant](https://github.com/survivant))

- Prevent IE8 error "The data necessary to complete this operation is not yet available" [\#32](https://github.com/Atmosphere/atmosphere/pull/32) ([ghengeli](https://github.com/ghengeli))

- Fix for Safari closed websocket without error. [\#29](https://github.com/Atmosphere/atmosphere/pull/29) ([sroebuck](https://github.com/sroebuck))

- fix for issue with long-polling getting empty body [\#27](https://github.com/Atmosphere/atmosphere/pull/27) ([mrtidy](https://github.com/mrtidy))

- add TimeUnit to suspend a request [\#26](https://github.com/Atmosphere/atmosphere/pull/26) ([survivant](https://github.com/survivant))

- little refactoring for : HeaderBroadcasterCache  that didn't extends the right class [\#25](https://github.com/Atmosphere/atmosphere/pull/25) ([survivant](https://github.com/survivant))

- Added `MozWebSocket` support for later Firefox browsers [\#21](https://github.com/Atmosphere/atmosphere/pull/21) ([sroebuck](https://github.com/sroebuck))

- Fixed a problem with setID\(\) calls after the initialisation. [\#18](https://github.com/Atmosphere/atmosphere/pull/18) ([fhackenberger](https://github.com/fhackenberger))

- Fixed a race-condition [\#16](https://github.com/Atmosphere/atmosphere/pull/16) ([fhackenberger](https://github.com/fhackenberger))

- Recursive requests on 'streaming' transport.  \(Added some debug logging\). [\#30](https://github.com/Atmosphere/atmosphere/pull/30) ([sroebuck](https://github.com/sroebuck))

- en gros, ajout de l'utilisation de TimeUnit pour le suspend au lieu de simplement ms. [\#24](https://github.com/Atmosphere/atmosphere/pull/24) ([survivant](https://github.com/survivant))

## [atmosphere-project-0.7.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.7.2) (2011-06-10)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.7.1...atmosphere-project-0.7.2)

## [atmosphere-project-0.7.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.7.1) (2011-04-05)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-project-0.7...atmosphere-project-0.7.1)

**Merged pull requests:**

- Fix for race condition [\#10](https://github.com/Atmosphere/atmosphere/pull/10) ([trask](https://github.com/trask))

## [atmosphere-project-0.7](https://github.com/Atmosphere/atmosphere/tree/atmosphere-project-0.7) (2011-02-25)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6.5...atmosphere-project-0.7)

## [atmosphere-0.6.5](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6.5) (2011-02-11)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6.4.GA...atmosphere-0.6.5)

## [atmosphere-0.6.4.GA](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6.4.GA) (2011-02-04)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6.3...atmosphere-0.6.4.GA)

## [atmosphere-0.6.3](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6.3) (2010-10-08)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6.2...atmosphere-0.6.3)

## [atmosphere-0.6.2](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6.2) (2010-09-30)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6.1...atmosphere-0.6.2)

## [atmosphere-0.6.1](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6.1) (2010-07-22)

[Full Changelog](https://github.com/Atmosphere/atmosphere/compare/atmosphere-0.6...atmosphere-0.6.1)

## [atmosphere-0.6](https://github.com/Atmosphere/atmosphere/tree/atmosphere-0.6) (2010-06-24)



\* *This Change Log was automatically generated by [github_changelog_generator](https://github.com/skywinder/Github-Changelog-Generator)*