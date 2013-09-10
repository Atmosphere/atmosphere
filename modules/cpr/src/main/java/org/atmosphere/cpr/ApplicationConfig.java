/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;

/**
 * Web.xml init-param configuration supported by Atmosphere.
 *
 * @author Jeanfrancois Arcand
 */
public interface ApplicationConfig {
    /**
     * The location of the atmosphere.xml file.
     * <p>
     * Default: META-INF/<br>
     * Value: org.atmosphere.atmosphereDotXml
     */
    String PROPERTY_ATMOSPHERE_XML = "org.atmosphere.atmosphereDotXml";
    /**
     * The path that will be used to map request to Jersey.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.jersey.servlet-mapping
     */
    String PROPERTY_SERVLET_MAPPING = "org.atmosphere.jersey.servlet-mapping";
    /**
     * Set Atmosphere to use the {@link org.atmosphere.container.BlockingIOCometSupport}, e.g blocking I/O.
     * <p>
     * Default: false<br>
     * <p>Value: org.atmosphere.useBlocking
     */
    String PROPERTY_BLOCKING_COMETSUPPORT = "org.atmosphere.useBlocking";
    /**
     * Set Atmosphere to throw exception on cloned request
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.throwExceptionOnClonedRequest
     */
    String PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST = "org.atmosphere.throwExceptionOnClonedRequest";
    /**
     * Set Atmosphere to use the container native Comet support.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.useNative
     */
    String PROPERTY_NATIVE_COMETSUPPORT = "org.atmosphere.useNative";
    /**
     * Force Atmosphere to use WebSocket.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.useWebSocket
     */
    String WEBSOCKET_SUPPORT = "org.atmosphere.useWebSocket";
    /**
     * Force Atmosphere to use WebSocket + Servlet 3.0 API.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.useWebSocketAndServlet3
     */
    String WEBSOCKET_SUPPORT_SERVLET3 = "org.atmosphere.useWebSocketAndServlet3";
    /**
     * Force Atmosphere to use stream when writing bytes.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.useStream
     */
    String PROPERTY_USE_STREAM = "org.atmosphere.useStream";
    /**
     * The default {@link org.atmosphere.cpr.BroadcasterFactory} class.
     * <p>
     * Default: org.atmosphere.cpr.DefaultBroadcasterFactory<br>
     * Value: org.atmosphere.cpr.broadcasterFactory
     */
    String BROADCASTER_FACTORY = ApplicationConfig.class.getPackage().getName() + ".broadcasterFactory";
    /**
     * The default {@link org.atmosphere.cpr.Broadcaster} class.
     * <p>
     * Default: org.atmosphere.cpr.DefaultBroadcaster<br>
     * Value: org.atmosphere.cpr.broadcasterClass
     */
    String BROADCASTER_CLASS = ApplicationConfig.class.getPackage().getName() + ".broadcasterClass";
    /**
     * The default {@link org.atmosphere.cpr.BroadcasterCache} class.
     * <p>
     * Default: "" (Not installed by default)<br>
     * Value: org.atmosphere.cpr.broadcasterCacheClass
     */
    String BROADCASTER_CACHE = ApplicationConfig.class.getPackage().getName() + ".broadcasterCacheClass";
    /**
     * Tell Atmosphere which {@link AsyncSupport} implementation to use.
     * <p>
     * Default: "" (Auto detected by Atmosphere)<br>
     * Value: org.atmosphere.cpr.asyncSupport
     */
    String PROPERTY_COMET_SUPPORT = ApplicationConfig.class.getPackage().getName() + ".asyncSupport";
    /**
     * Tell Atmosphere to use {@link javax.servlet.http.HttpSession}.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.sessionSupport
     */
    String PROPERTY_SESSION_SUPPORT = ApplicationConfig.class.getPackage().getName() + ".sessionSupport";
    /**
     * Force Atmosphere to invoke {@link AtmosphereResource#resume()} after the first {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} invocation.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.resumeOnBroadcast
     */
    String RESUME_ON_BROADCAST = "org.atmosphere.resumeOnBroadcast";
    /**
     * The default Servlet used when forwarding request.
     * <p>
     * Default: default<br>
     * Value: default
     */
    String DEFAULT_NAMED_DISPATCHER = "default";
    /**
     * Tell Atmosphere to not write the no-cache header. Default is false, e.g Atmosphere will write them.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.noCacheHeaders
     */
    String NO_CACHE_HEADERS = ApplicationConfig.class.getPackage().getName() + ".noCacheHeaders";
    /**
     * Tell Atmosphere to not write the access-control header. Default is false, e.g Atmosphere will write them.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.dropAccessControlAllowOriginHeader
     */
    String DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = ApplicationConfig.class.getPackage().getName() + ".dropAccessControlAllowOriginHeader";
    /**
     * The {@link org.atmosphere.cpr.BroadcasterLifeCyclePolicy} policy to use.
     * <p>
     * Default: BroadcasterLifeCyclePolicy.NEVER<br>
     * Value: org.atmosphere.cpr.broadcasterLifeCyclePolicy
     */
    String BROADCASTER_LIFECYCLE_POLICY = ApplicationConfig.class.getPackage().getName() + ".broadcasterLifeCyclePolicy";
    /**
     * Tell Atmosphere the {@link org.atmosphere.websocket.WebSocketProcessor} to use.
     * <p>
     * Default: org.atmosphere.websocket.DefaultWebSocketProcessor<br>
     * Value: org.atmosphere.websocket.WebSocketProcessor
     */
    String WEBSOCKET_PROCESSOR = WebSocketProcessor.class.getName();
    /**
     * Tell Atmosphere the {@link org.atmosphere.websocket.WebSocketProtocol} to use.
     * <p>
     * Default: org.atmosphere.websocket.SimpleHttpProtocol<br>
     * Value: org.atmosphere.websocket.WebSocketProtocol
     */
    String WEBSOCKET_PROTOCOL = WebSocketProtocol.class.getName();
    /**
     * Tell Atmosphere the content-type to use when a WebSocket message is dispatched as an AtmosphereRequest.
     * <p>
     * Default: text/plain<br>
     * Value: org.atmosphere.websocket.messageContentType
     */
    String WEBSOCKET_CONTENT_TYPE = "org.atmosphere.websocket.messageContentType";
    /**
     * Tell Atmosphere the content-type to use when a WebSocket message is dispatched as an AtmosphereRequest.
     * <p>
     * Default: text/event-stream<br>
     * Value: org.atmosphere.sse.contentType
     */
    String SSE_CONTENT_TYPE = "org.atmosphere.sse.contentType";
    /**
     * Tell Atmosphere the method to use when a WebSocket message is dispatched as an AtmosphereRequest.
     * <p>
     * Default: POST<br>
     * Value: org.atmosphere.websocket.messageMethod
     */
    String WEBSOCKET_METHOD = "org.atmosphere.websocket.messageMethod";
    /**
     * Tell Atmosphere how long a WebSocket connection can stay idle.
     * <p>
     * Default: 5 minutes<br>
     * Value: org.atmosphere.websocket.maxIdleTime
     */
    String WEBSOCKET_IDLETIME = "org.atmosphere.websocket.maxIdleTime";
    /**
     * Tell Atmosphere the WebSocket write buffer size.
     * <p>
     * Default: 8192<br>
     * Value: org.atmosphere.websocket.bufferSize
     */
    String WEBSOCKET_BUFFER_SIZE = "org.atmosphere.websocket.bufferSize";
    /**
     * Tell Atmosphere the path delimiter to use when a WebSocket message contains the path as it first line. The
     * value is used to create a HttpServletRequest.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.websocket.pathDelimiter
     */
    String WEBSOCKET_PATH_DELIMITER = "org.atmosphere.websocket.pathDelimiter";
    /**
     * Set the WebSocket max text size. Size lower than 0: no aggregation of frames to messages, larger than 0: max size of text frame aggregation buffer in characters
     * <p>
     * Default: 8192<br>
     * Value: org.atmosphere.websocket.maxTextMessageSize
     */
    String WEBSOCKET_MAXTEXTSIZE = "org.atmosphere.websocket.maxTextMessageSize";
    /**
     * Set the WebSocket max text size. Size < 0: no aggregation of frames to messages, size >=0: max size of text frame aggregation buffer in characters
     * <p>
     * Default: 8192<br>
     * Value: org.atmosphere.websocket.maxBinaryMessageSize
     */
    String WEBSOCKET_MAXBINARYSIZE = "org.atmosphere.websocket.maxBinaryMessageSize";
    /**
     * Tell Atmosphere to enforce the same origin policy for all incoming WebSocket handshakes.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.websocket.requireSameOrigin
     */
    String WEBSOCKET_REQUIRE_SAME_ORIGIN = "org.atmosphere.websocket.requireSameOrigin";
    /**
     * The {@link AtmosphereResource}.
     * <p>
     * Default: org.atmosphere.cpr.AtmosphereResourceImpl<br>
     * Value: org.atmosphere.cpr.AtmosphereResource
     */
    String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    /**
     * A list of {@link BroadcastFilter} separated by comma that will be added to every new {@link Broadcaster}.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.cpr.broadcastFilterClasses
     */
    String BROADCAST_FILTER_CLASSES = ApplicationConfig.class.getPackage().getName() + ".broadcastFilterClasses";
    /**
     * A request attribute used to tell {@link AsyncSupport} implementation to keep alive the connection or not. Default is to delegate the talk to the underlying WebServer.
     * <p>
     * Default: false (to delegate the talk to the underlying WebServer)<br>
     * Value: org.atmosphere.cpr.AtmosphereServlet.resumeAndKeepAlive
     */
    String RESUME_AND_KEEPALIVE = "org.atmosphere.cpr.AtmosphereServlet.resumeAndKeepAlive";
    /**
     * A request attribute telling a {@link AsyncSupport} if the AtmosphereResource was resumed on timeout or by an application.
     * This attribute is for WebServer that doesn't support time-outs (like Jetty 6)
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.AtmosphereServlet.resumedOnTimeout
     */
    String RESUMED_ON_TIMEOUT = "org.atmosphere.cpr.AtmosphereServlet.resumedOnTimeout";
    /**
     * Disable invoking {@link org.atmosphere.cpr.AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)} when the connection times out or get cancelled.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.disableOnStateEvent
     */
    String DISABLE_ONSTATE_EVENT = "org.atmosphere.disableOnStateEvent";
    /**
     * The maximum time, in milliseconds, a connection gets idle. This property can be used with Jetty and BlockingIOCometSupport.
     * Other WebServers support detection of idle connection (idle or remotely closed)
     * <p>
     * Default: -1 (disabled)<br>
     * Value: org.atmosphere.cpr.CometSupport.maxInactiveActivity
     */
    String MAX_INACTIVE = "org.atmosphere.cpr.CometSupport.maxInactiveActivity";
    /**
     * Allow query string as set as request's header.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.allowQueryStreamAsPostOrGet
     */
    String ALLOW_QUERYSTRING_AS_REQUEST = ApplicationConfig.class.getPackage().getName() + ".allowQueryStreamAsPostOrGet";
    /**
     * Configure {@link Broadcaster} to share the same {@link java.util.concurrent.ExecutorService} among them.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.broadcaster.shareableThreadPool
     */
    String BROADCASTER_SHARABLE_THREAD_POOLS = ApplicationConfig.class.getPackage().getName() + ".broadcaster.shareableThreadPool";
    /**
     * The maximum number of Thread created when processing broadcast operations {@link BroadcasterConfig#setExecutorService(java.util.concurrent.ExecutorService)}.
     * <p>
     * Default: unlimited<br>
     * Value: org.atmosphere.cpr.broadcaster.maxProcessingThreads
     */
    String BROADCASTER_MESSAGE_PROCESSING_THREADPOOL_MAXSIZE = ApplicationConfig.class.getPackage().getName() + ".broadcaster.maxProcessingThreads";
    /**
     * The maximum number of Thread created when writing requests {@link BroadcasterConfig#setAsyncWriteService(java.util.concurrent.ExecutorService)}.
     * <p>
     * Default: unlimited<br>
     * Value: org.atmosphere.cpr.broadcaster.maxAsyncWriteThreads
     */
    String BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE = ApplicationConfig.class.getPackage().getName() + ".broadcaster.maxAsyncWriteThreads";
    /**
     * BroadcasterLifecycle max idle time before executing.
     * <p>
     * Default: 5 minutes<br>
     * Value: org.atmosphere.cpr.maxBroadcasterLifeCyclePolicyIdleTime
     */
    String BROADCASTER_LIFECYCLE_POLICY_IDLETIME = ApplicationConfig.class.getPackage().getName() + ".maxBroadcasterLifeCyclePolicyIdleTime";
    /**
     * Recover from a {@link Broadcaster} that has been destroyed.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.recoverFromDestroyedBroadcaster
     */
    String RECOVER_DEAD_BROADCASTER = ApplicationConfig.class.getPackage().getName() + ".recoverFromDestroyedBroadcaster";
    /**
     * Tell Atmosphere which AtmosphereHandler should be used. You can do the same using atmosphere.xml
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.cpr.AtmosphereHandler
     */
    String ATMOSPHERE_HANDLER = AtmosphereHandler.class.getName();
    /**
     * The AtmosphereHandler defined using the property will be mapped to that value. Same as atmosphere.xml
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.AtmosphereHandler.contextRoot
     */
    String ATMOSPHERE_HANDLER_MAPPING = AtmosphereHandler.class.getName() + ".contextRoot";
    /**
     * The Servlet's name where {@link Meteor} will be available.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.servlet
     */
    String SERVLET_CLASS = "org.atmosphere.servlet";
    /**
     * The Filter's name where {@link Meteor} will be available.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.filter
     */
    String FILTER_CLASS = "org.atmosphere.filter";
    /**
     * The Servlet's mapping value to the SERVLET_CLASS.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.mapping
     */
    String MAPPING = "org.atmosphere.mapping";
    /**
     * The Servlet's mapping value to the FILTER_CLASS.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.filter.name
     */
    String FILTER_NAME = "org.atmosphere.filter.name";
    /**
     * Define when a broadcasted message is cached. Value can be 'beforeFilter' or 'afterFilter'.
     * <p>
     * Default: afterFilter<br>
     * Value: org.atmosphere.cpr.BroadcasterCache.strategy
     */
    String BROADCASTER_CACHE_STRATEGY = BroadcasterCache.class.getName() + ".strategy";
    /**
     * Support the Jersey location header for resuming. WARNING: this can cause memory leak if the connection is never
     * resumed.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.jersey.supportLocationHeader
     */
    String SUPPORT_LOCATION_HEADER = "org.atmosphere.jersey.supportLocationHeader";
    /**
     * WebSocket version to exclude and downgrade to comet. Versions are separated by comma.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.websocket.bannedVersion
     */
    String WEB_SOCKET_BANNED_VERSION = "org.atmosphere.websocket.bannedVersion";
    /**
     * Prevent Tomcat from closing connection when inputStream#read() reach the end of the stream, as documented in
     * the tomcat documentation.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.container.TomcatCometSupport.discardEOF
     */
    String TOMCAT_CLOSE_STREAM = "org.atmosphere.container.TomcatCometSupport.discardEOF";
    /**
     * Write binary instead of String.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.websocket.binaryWrite
     */
    String WEBSOCKET_BINARY_WRITE = "org.atmosphere.websocket.binaryWrite";
    /**
     * Recycle (make them unusable) AtmosphereRequest/Response after wrapping a WebSocket message and delegating it to
     * a Container.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.recycleAtmosphereRequestResponse
     */
    String RECYCLE_ATMOSPHERE_REQUEST_RESPONSE = ApplicationConfig.class.getPackage().getName() + ".recycleAtmosphereRequestResponse";
    /**
     * The location of classes implementing the {@link AtmosphereHandler} interface.
     * <p>
     * Default: "/WEB-INF/classes".<br>
     * Value: org.atmosphere.cpr.atmosphereHandlerPath
     */
    String ATMOSPHERE_HANDLER_PATH = ApplicationConfig.class.getPackage().getName() + ".atmosphereHandlerPath";
    /**
     * Jersey's ContainerResponseWriter.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.jersey.containerResponseWriterClass
     */
    String JERSEY_CONTAINER_RESPONSE_WRITER_CLASS = "org.atmosphere.jersey.containerResponseWriterClass";
    /**
     * Execute the {@link WebSocketProtocol#onMessage(org.atmosphere.websocket.WebSocket, byte[], int, int)}.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.websocket.WebSocketProtocol.executeAsync
     */
    String WEBSOCKET_PROTOCOL_EXECUTION = WebSocketProtocol.class.getName() + ".executeAsync";
    /**
     * The default content-type value used when Atmosphere requires one.
     * <p>
     * Default: "text/plain"<br>
     * Value: org.atmosphere.cpr.defaultContentType
     */
    String DEFAULT_CONTENT_TYPE = ApplicationConfig.class.getPackage().getName() + ".defaultContentType";
    /**
     * A list of {@link AtmosphereInterceptor} class name that will be invoked before the {@link AtmosphereResource}
     * gets delivered to an application or framework.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.cpr.AtmosphereInterceptor
     */
    String ATMOSPHERE_INTERCEPTORS = AtmosphereInterceptor.class.getName();
    /**
     * Regex pattern for excluding file from being serviced by {@link AtmosphereFilter}.
     * <p>
     * Default: {@link AtmosphereFilter#EXCLUDE_FILES}<br>
     * Value: org.atmosphere.cpr.AtmosphereFilter.excludes
     */
    String ATMOSPHERE_EXCLUDED_FILE = AtmosphereFilter.class.getName() + ".excludes";
    /**
     * The token used to separate broadcasted messages. This value is used by the client to parse several messages
     * received in one chunk.
     * <p>
     * Default: "|"<br>
     * Value: org.atmosphere.client.TrackMessageSizeInterceptor.delimiter
     */
    String MESSAGE_DELIMITER = TrackMessageSizeInterceptor.class.getName() + ".delimiter";
    /**
     * The method used that trigger automatic management of {@link AtmosphereResource} when the {@link AtmosphereResourceLifecycleInterceptor}
     * is used.
     * <p>
     * Default: "GET"<br>
     * Value: org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor.method
     */
    String ATMOSPHERERESOURCE_INTERCEPTOR_METHOD = AtmosphereResourceLifecycleInterceptor.class.getName() + ".method";
    /**
     * Disable au-discovery of pre-installed {@link AtmosphereInterceptor}s.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.AtmosphereInterceptor.disableDefaults
     */
    String DISABLE_ATMOSPHEREINTERCEPTOR = AtmosphereInterceptor.class.getName() + ".disableDefaults";
    /**
     * Set to true if Atmosphere is used as a library and you don't want to destroy associated static factory when undeploying.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.runtime.shared
     */
    String SHARED = "org.atmosphere.runtime.shared";
    /**
     * The suspended UUID of the suspended connection which is the same as {@link HeaderConfig#X_ATMOSPHERE_TRACKING_ID}
     * but available to all transport.
     * <p>
     * Value: org.atmosphere.cpr.AtmosphereResource.suspended.uuid
     */
    String SUSPENDED_ATMOSPHERE_RESOURCE_UUID = AtmosphereResource.class.getName() + "suspended.uuid";
    /**
     * Use a unique UUID for all WebSocket message delivered on the same connection.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.AtmosphereResource.uniqueUUID
     */
    String UNIQUE_UUID_WEBSOCKET = AtmosphereResource.class.getName() + ".uniqueUUID";
    /**
     * Set to true if order of message delivered to the client is not important.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.Broadcaster.supportOutOfOrderBroadcast
     */
    String OUT_OF_ORDER_BROADCAST = Broadcaster.class.getName() + ".supportOutOfOrderBroadcast";
    /**
     * The write operation timeout, in millisecond, when using the {@link DefaultBroadcaster}. When the timeout occurs, the calling thread gets interrupted.
     * <p>
     * Default: 5 * 60 * 1000 (5 minutes)<br>
     * Value: org.atmosphere.cpr.Broadcaster.writeTimeout
     */
    String WRITE_TIMEOUT = Broadcaster.class.getName() + ".writeTimeout";
    /**
     * The sleep time, in millisecond, before the {@link DefaultBroadcaster} release its reactive thread for pushing message
     * and execute async write. Setting this value too high may create too many threads.
     * <p>
     * Default: 1000<br>
     * Value: org.atmosphere.cpr.Broadcaster.threadWaitTime
     */
    String BROADCASTER_WAIT_TIME = Broadcaster.class.getName() + ".threadWaitTime";
    /**
     * Before 1.0.12, WebSocket's AtmosphereResource manually added to {@link Broadcaster} were added without checking
     * if the parent, e.g the AtmosphereResource's created on the first request was already added to the Broadcaster. That caused
     * some messages to be written twice instead of once.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.websocket.backwardCompatible.atmosphereResource
     */
    String BACKWARD_COMPATIBLE_WEBSOCKET_BEHAVIOR = "org.atmosphere.websocket.backwardCompatible.atmosphereResource";
    /**
     * A list, separated by comma, of package name to scan when looking for Atmosphere's component annotated with Atmosphere's annotation.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.cpr.packages
     */
    String ANNOTATION_PACKAGE = "org.atmosphere.cpr.packages";
    /**
     * The annotation processor.
     * <p>
     * Default: org.atmosphere.cpr.DefaultAnnotationProcessor<br>
     * Value: org.atmosphere.cpr.AnnotationProcessor
     */
    String ANNOTATION_PROCESSOR = AnnotationProcessor.class.getName();
    /**
     * Define an implementation of the {@link org.atmosphere.util.EndpointMapper}.
     * <p>
     * Default: org.atmosphere.cpr.DefaultEndpointMapper<br>
     * Value: org.atmosphere.cpr.EndpointMapper
     */
    String ENDPOINT_MAPPER = EndpointMapper.class.getName();
    /**
     * The list of content-type to exclude when delimiting message.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.client.TrackMessageSizeInterceptor.excludedContentType
     */
    String EXCLUDED_CONTENT_TYPES = TrackMessageSizeInterceptor.class.getName() + ".excludedContentType";
    /**
     * Allow defining the Broadcaster's Suspend Policy {@link Broadcaster#setSuspendPolicy(long, org.atmosphere.cpr.Broadcaster.POLICY)}.
     * <p>
     * Default: FIFO<br>
     * Value: org.atmosphere.cpr.Broadcaster.POLICY
     */
    String BROADCASTER_POLICY = Broadcaster.POLICY.class.getName();
    /**
     * Allow defining the Broadcaster's maximum Suspended Atmosphere Policy {@link Broadcaster#setSuspendPolicy(long, org.atmosphere.cpr.Broadcaster.POLICY)}.
     * <p>
     * Default: -1 (unlimited)<br>
     * Value: org.atmosphere.cpr.Broadcaster.POLICY.maximumSuspended
     */
    String BROADCASTER_POLICY_TIMEOUT = Broadcaster.POLICY.class.getName() + ".maximumSuspended";
    /**
     * Change the default regex used when mapping AtmosphereHandler. Default: {@link AtmosphereFramework#MAPPING_REGEX}
     * <p>
     * Default: "[a-zA-Z0-9-&.*_=@;\?]+"<br>
     * Value: org.atmosphere.client.ApplicationConfig.mappingRegex
     */
    String HANDLER_MAPPING_REGEX = ApplicationConfig.class.getPackage().getName() + ".mappingRegex";
    /**
     * The timeout, in milliseconds, before an {@link AtmosphereResource}'s state get discarded.
     * <p>
     * Default: 300000 (5 minutes)<br>
     * Value: org.atmosphere.interceptor.AtmosphereResourceStateRecovery.timeout
     */
    String STATE_RECOVERY_TIMEOUT = AtmosphereResourceStateRecovery.class.getName() + ".timeout";
    /**
     * jsr356 Path mapping length for add(ServerEndpointConfig.Builder.create(JSR356Endpoint.class, "/{path}/{path/...}").
     * Default: 5
     * Value: MUST be set using System's properties: org.atmosphere.cpr.jsr356.pathMappingLength"
     */
    String JSR356_PATH_MAPPING_LENGTH = ApplicationConfig.class.getName() + ".jsr356.pathMappingLength";
    /**
     * Default Server Side Event content type.
     * Default: text/event-stream
     * Value: org.atmosphere.interceptor.SSEAtmosphereInterceptor.contentType
     */
    String SSE_DEFAULT_CONTENTTYPE = SSEAtmosphereInterceptor.class.getName() + ".contentType";
    /**
     * A list, separated by comma, of package name to scan when looking for @AtmosphereAnnotation custom Annotation.
     * <p>
     * Default: ""<br>
     * Value: org.atmosphere.annotation.packages
     */
    String CUSTOM_ANNOTATION_PACKAGE = "org.atmosphere.annotation.packages";
    /**
     * Set to false if you want Atmosphere to scan the entire classpath looking for annotation.
     * <p>
     * Default: true<br>
     * Value: org.atmosphere.cpr.scanClassPath
     */
    String SCAN_CLASSPATH = ApplicationConfig.class.getName() + ".scanClassPath";
    /**
     * Use a build in {@link javax.servlet.http.HttpSession} when using native WebSocket implementation.
     * <p>
     * Default: false<br>
     * Value: org.atmosphere.cpr.useBuildInSession
     */
    String BUILT_IN_SESSION = ApplicationConfig.class.getName() + ".useBuildInSession";
}

