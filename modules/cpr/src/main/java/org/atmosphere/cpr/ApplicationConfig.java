/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.cpr;

import org.atmosphere.client.MessageLengthInterceptor;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.websocket.WebSocketProtocol;

/**
 * Web.xml init-param configuration supported by Atmosphere.
 *
 * @author Jeanfrancois Arcand
 */
public interface ApplicationConfig {
    /**
     * The location of the atmosphere.xml file.
     */
    String PROPERTY_ATMOSPHERE_XML = "org.atmosphere.atmosphereDotXml";
    /**
     * The path that will be used to map request to Jersey
     */
    String PROPERTY_SERVLET_MAPPING = "org.atmosphere.jersey.servlet-mapping";
    /**
     * Set Atmosphere to use the {@link org.atmosphere.container.BlockingIOCometSupport}, e.g blocking I/O
     */
    String PROPERTY_BLOCKING_COMETSUPPORT = "org.atmosphere.useBlocking";
    /**
     * Set Atmosphere to use the container native Comet support
     */
    String PROPERTY_NATIVE_COMETSUPPORT = "org.atmosphere.useNative";
    /**
     * Force Atmosphere to use WebSocket
     */
    String WEBSOCKET_SUPPORT = "org.atmosphere.useWebSocket";
    /**
     * Force Atmosphere to use stream when writing bytes.
     */
    String PROPERTY_USE_STREAM = "org.atmosphere.useStream";
    /**
     * The default {@link org.atmosphere.cpr.BroadcasterFactory} class.
     */
    String BROADCASTER_FACTORY = ApplicationConfig.class.getPackage().getName() + ".broadcasterFactory";
    /**
     * The default {@link org.atmosphere.cpr.Broadcaster} class.
     */
    String BROADCASTER_CLASS = ApplicationConfig.class.getPackage().getName() + ".broadcasterClass";
    /**
     * The default {@link org.atmosphere.cpr.BroadcasterCache} class.
     */
    String BROADCASTER_CACHE = ApplicationConfig.class.getPackage().getName() + ".broadcasterCacheClass";
    /**
     * Tell Atmosphere which {@link AsyncSupport} implementation to use.
     */
    String PROPERTY_COMET_SUPPORT = ApplicationConfig.class.getPackage().getName() + ".asyncSupport";
    /**
     * Tell Atmosphere to use {@link javax.servlet.http.HttpSession}. Default is false.
     */
    String PROPERTY_SESSION_SUPPORT = ApplicationConfig.class.getPackage().getName() + ".sessionSupport";
    /**
     * Force Atmosphere to invoke {@link AtmosphereResource#resume()} after the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} invokation.
     */
    String RESUME_ON_BROADCAST = "org.atmosphere.resumeOnBroadcast";
    /**
     * The default Servlet used when forwarding request.
     */
    String DEFAULT_NAMED_DISPATCHER = "default";
    /**
     * Tell Atmosphere to not write the no-cache header. Default is false, e.g Atmosphere will write them.
     */
    String NO_CACHE_HEADERS = ApplicationConfig.class.getPackage().getName() + ".noCacheHeaders";
    /**
     * Tell Atmosphere to not write the access-control header. Default is false, e.g Atmosphere will write them.
     */
    String DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = ApplicationConfig.class.getPackage().getName() + ".dropAccessControlAllowOriginHeader";
    /**
     * The {@link org.atmosphere.cpr.BroadcasterLifeCyclePolicy} policy to use
     */
    String BROADCASTER_LIFECYCLE_POLICY = ApplicationConfig.class.getPackage().getName() + ".broadcasterLifeCyclePolicy";
    /**
     * Tell Atmosphere the {@link org.atmosphere.websocket.WebSocketProcessor} to use.
     */
    String WEBSOCKET_PROTOCOL = WebSocketProtocol.class.getName();
    /**
     * Tell Atmosphere the content-type to use when a WebSocket message is dispatched as an AtmosphereRequest
     */
    String WEBSOCKET_CONTENT_TYPE = "org.atmosphere.websocket.messageContentType";
    /**
     * Tell Atmosphere the content-type to use when a WebSocket message is dispatched as an AtmosphereRequest
     */
    String SSE_CONTENT_TYPE = "org.atmosphere.sse.contentType";
    /**
     * Tell Atmosphere the method to use when a WebSocket message is dispatched as an AtmosphereRequest
     */
    String WEBSOCKET_METHOD = "org.atmosphere.websocket.messageMethod";
    /**
     * Tell Atmosphere how long a WebSocket connection can stay idle. Default is 5 minutes
     */
    String WEBSOCKET_IDLETIME = "org.atmosphere.websocket.maxIdleTime";
    /**
     * Tell Atmosphere the WebSocket write buffer size. Default is 8192
     */
    String WEBSOCKET_BUFFER_SIZE = "org.atmosphere.websocket.bufferSize";
    /**
     * Tell Atmosphere the path delimiter to use when a WebSocket message contains the path as it first line. The
     * value is used to create a HttpServletRequest.
     */
    String WEBSOCKET_PATH_DELIMITER = "org.atmosphere.websocket.pathDelimiter";
    /**
     * Set the WebSocket mas text size: size<0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
     */
    String WEBSOCKET_MAXTEXTSIZE = "org.atmosphere.websocket.maxTextMessageSize";
    /**
     * Set the WebSocket mas text size: size<0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
     */
    String WEBSOCKET_MAXBINARYSIZE = "org.atmosphere.websocket.maxBinaryMessageSize";
    /**
     * The Atmosphere resource to use.
     */
    String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    /**
     * A list of {@link BroadcastFilter} separated with coma that will be added to every new {@link Broadcaster}
     */
    String BROADCAST_FILTER_CLASSES = ApplicationConfig.class.getPackage().getName() + ".broadcastFilterClasses";
    /**
     * A request attribute used to tell {@link AsyncSupport} implementation to keep alive the connection or not. Default is to delegate the talk to the underlying WebServer.
     */
    String RESUME_AND_KEEPALIVE = "org.atmosphere.cpr.AtmosphereServlet.resumeAndKeepAlive";
    /**
     * A request attribute telling a {@link AsyncSupport} if the AtmosphereResource was resumed on timeout or by an application. This attribute is for WebServer that doesn't support times out (like Jetty 6)
     */
    String RESUMED_ON_TIMEOUT = "org.atmosphere.cpr.AtmosphereServlet.resumedOnTimeout";
    /**
     * Disable invoking {@link org.atmosphere.cpr.AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)} when the connection times out or get cancelled
     */
    String DISABLE_ONSTATE_EVENT = "org.atmosphere.disableOnStateEvent";
    /**
     * The maximum time, in milisecond, a connection gets idle. This properly can be used with Jetty and BlockingIOCometSupport. Other WebServer supports detection of idle connection (idle or remotely closed)
     */
    String MAX_INACTIVE = "org.atmosphere.cpr.CometSupport.maxInactiveActivity";
    /**
     * Allow query string as set as request's header.
     */
    String ALLOW_QUERYSTRING_AS_REQUEST = ApplicationConfig.class.getPackage().getName() + ".allowQueryStreamAsPostOrGet";
    /**
     * Configure the padding used when streaming is used. Value can be atmosphere or whitespace. Default is ATMOSPHERE {@link org.atmosphere.cpr.AtmosphereResourceImpl#createStreamingPadding(String)} ()}
     */
    String STREAMING_PADDING_MODE = ApplicationConfig.class.getPackage().getName() + ".padding";
    /**
     * Configure {@link Broadcaster} to share the same {@link java.util.concurrent.ExecutorService} instead among them. Default is false.
     */
    String BROADCASTER_SHARABLE_THREAD_POOLS = ApplicationConfig.class.getPackage().getName() + ".broadcaster.shareableThreadPool";
    /**
     * The maximum number of Thread created when processing broadcast operations {@link BroadcasterConfig#setExecutorService(java.util.concurrent.ExecutorService)}
     */
    String BROADCASTER_MESSAGE_PROCESSING_THREADPOOL_MAXSIZE = ApplicationConfig.class.getPackage().getName() + ".broadcaster.maxProcessingThreads";
    /**
     * The maximum number of Thread created when writing requests {@link BroadcasterConfig#setAsyncWriteService(java.util.concurrent.ExecutorService)}
     */
    String BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE = ApplicationConfig.class.getPackage().getName() + ".broadcaster.maxAsyncWriteThreads";
    /**
     * BroadcasterLifecycle max idle time before executing. Default is 5 minutes
     */
    String BROADCASTER_LIFECYCLE_POLICY_IDLETIME = ApplicationConfig.class.getPackage().getName() + ".maxBroadcasterLifeCyclePolicyIdleTime";
    /**
     * Recover from a {@link Broadcaster} that has been destroyed. Default is true.
     */
    String RECOVER_DEAD_BROADCASTER = ApplicationConfig.class.getPackage().getName() + ".recoverFromDestroyedBroadcaster";
    /**
     * Tell Atmosphere which AtmosphereHandler should be used. You can do the same using atmosphere.xml
     */
    String ATMOSPHERE_HANDLER = AtmosphereHandler.class.getName();
    /**
     * The AtmosphereHandler defined using the property will be mapped to that value. Same as atmosphere.xml
     */
    String ATMOSPHERE_HANDLER_MAPPING = AtmosphereHandler.class.getName() + ".contextRoot";
    /**
     * The Servlet's name where {@link Meteor} will be available
     */
    String SERVLET_CLASS = "org.atmosphere.servlet";
    /**
     * The Filter's name where {@link Meteor} will be available
     */
    String FILTER_CLASS = "org.atmosphere.filter";
    /**
     * The Servlet's mapping value to the SERVLET_CLASS
     */
    String MAPPING = "org.atmosphere.mapping";
    /**
     * The Servlet's mapping value to the FILTER_CLASS
     */
    String FILTER_NAME = "org.atmosphere.filter.name";
    /**
     * Define when a broadcasted message is cached. Value can be 'beforeFilter' or 'afterFilter'. Default is afterFilter
     */
    String BROADCASTER_CACHE_STRATEGY = BroadcasterCache.class.getName() + ".strategy";
    /**
     * Support the Jersey location header for resuming. WARNING: this can cause memory leak if the connection is never
     * resumed.
     */
    String SUPPORT_LOCATION_HEADER = "org.atmosphere.jersey.supportLocationHeader";
    /**
     * WebSocket version to exclude and downgrade to comet. Version are separated by comma
     */
    String WEB_SOCKET_BANNED_VERSION = "org.atmosphere.websocket.bannedVersion";
    /**
     * Prevent Tomcat from closing connection when inputStream#read() reach the end of the stream, as documented in
     * the tomcat documentation
     */
    String TOMCAT_CLOSE_STREAM = "org.atmosphere.container.TomcatCometSupport.discardEOF";
    /**
     * Write binary instead of String
     */
    String WEBSOCKET_BINARY_WRITE = "org.atmosphere.websocket.binaryWrite";
    /**
     * Recycle (make them unusable) AtmosphereRequest/Response after wrapping a WebSocket message and delegating it to
     * a Container
     */
    String RECYCLE_ATMOSPHERE_REQUEST_RESPONSE = ApplicationConfig.class.getPackage().getName() + "recycleAtmosphereRequestResponse";
    /**
     * The location of classes implementing the {@link AtmosphereHandler} interface. Default to "/WEB-INF/classes".
     */
    String ATMOSPHERE_HANDLER_PATH = ApplicationConfig.class.getPackage().getName() + ".atmosphereHandlerPath";
    /**
     * Jersey's ContainerResponseWriter.
     */
    String JERSEY_CONTAINER_RESPONSE_WRITER_CLASS = "org.atmosphere.jersey.containerResponseWriterClass";
    /**
     * Execute the {@link WebSocketProtocol#onMessage(org.atmosphere.websocket.WebSocket, byte[], int, int)}
     */
    String WEBSOCKET_PROTOCOL_EXECUTION = WebSocketProtocol.class.getName() + ".executeAsync";
    /**
     * The default content-type value used when Atmosphere requires one. Default is text/plain.
     */
    String DEFAULT_CONTENT_TYPE = ApplicationConfig.class.getPackage().getName() + ".defaultContextType";
    /**
     * Wrap the request/response with associated {@link javax.servlet.http.HttpServletRequest} and
     * {@link javax.servlet.http.HttpServletResponse}
     */
    String USE_SERVLET_WRAPPER = ApplicationConfig.class.getPackage().getName() + ".useWrappers";
    /**
     * A list of {@link AtmosphereInterceptor} class name that will be invoked before the {@link AtmosphereResource}
     * gets delivered to an application or framework
     */
    String ATMOSPHERE_INTERCEPTORS = AtmosphereInterceptor.class.getName();
    /**
     * Regex pattern for excluding file from being serviced by {@link AtmosphereFilter}
     */
    String ATMOSPHERE_EXCLUDED_FILE = AtmosphereFilter.class.getName() + ".excludes";
    /**
     * The token used to separate broadcasted messages. This value is used by the client to parse several messages
     * received in one chunk. Default is '<||>'
     */
    String MESSAGE_DELIMITER = MessageLengthInterceptor.class.getName() + ".delimiter";
    /**
     * The method used that trigger automatic management of {@link AtmosphereResource} when the {@link AtmosphereResourceLifecycleInterceptor}
     * is used
     */
    String ATMOSPHERERESOURCE_INTERCEPTOR_METHOD = AtmosphereResourceLifecycleInterceptor.class.getName() + ".method";
    /**
     * Disable au-discovery of pre-installed {@link AtmosphereInterceptor}
     */
    String DISABLE_ATMOSPHEREINTERCEPTOR = AtmosphereInterceptor.class.getName() + ".disableDefaults";
}
