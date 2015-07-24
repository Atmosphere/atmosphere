/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.inject.InjectIntrospector;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.websocket.WebSocketProcessor;

/**
 * Request attribute a framework integrator can use to lookup Atmosphere internal objects.
 *
 * @author Jeanfrancois Arcand
 */
public interface FrameworkConfig {
    /**
     * The default Kafka Broadcaster class.
     */
    String KAFKA_BROADCASTER = "org.atmosphere.kafka.KafkaBroadcaster";
    /**
     * The default Hazelcast Broadcaster class.
     */
    String HAZELCAST_BROADCASTER = "org.atmosphere.plugin.hazelcast.HazelcastBroadcaster";
    /**
     * The default Jersey Broadcaster class.
     */
    String JERSEY_BROADCASTER = "org.atmosphere.jersey.JerseyBroadcaster";
    /**
     * The default Redis Broadcaster class.
     */
    String REDIS_BROADCASTER = "org.atmosphere.plugin.redis.RedisBroadcaster";
    /**
     * The default JMS Broadcaster class.
     */
    String JMS_BROADCASTER = "org.atmosphere.plugin.jms.JMSBroadcaster";
    /**
     * The default JGroups Broadcaster class.
     */
    String JGROUPS_BROADCASTER = "org.atmosphere.plugin.jgroups.JGroupsBroadcaster";
    /**
     * The default RMI Broadcaster class.
     */
    String RMI_BROADCASTER = "org.atmosphere.plugin.rmi.RMIBroadcaster";
    /**
     * The default RabbitMQ Broadcaster class.
     */
    String RABBITMQ_BROADCASTER = "org.atmosphere.plugin.rabbitmq.RabbitMQBroadcaster";
    /**
     * The default XMPP Broadcaster class.
     */
    String XMPP_BROADCASTER = "org.atmosphere.plugin.xmpp.XMPPBroadcaster";
    /**
     * The default Jersey container class.
     */
    String JERSEY_CONTAINER = "com.sun.jersey.spi.container.servlet.ServletContainer";
    /**
     * A request attribute used to lookup the {@link AtmosphereServlet}. This attribute is for framework integrators and not recommend for normal applications.
     */
    String ATMOSPHERE_SERVLET = "org.atmosphere.cpr.AtmosphereServlet";
    /**
     * A request attribute used to lookup the {@link AtmosphereResource}. This attribute is for framework integrators and not recommend for normal applications.
     */
    String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    /**
     * A request attribute used to lookup the {@link AtmosphereResource} created by an external component and injected inside the {@link AsynchronousProcessor}.
     */
    String INJECTED_ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName() + ".injected";
    /**
     * Tell a {@link AsyncSupport} it can support session or not.
     */
    String SUPPORT_SESSION = AsynchronousProcessor.class.getName() + ".supportSession";
    /**
     * A request attribute used to lookup the {@link AtmosphereHandler}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_HANDLER_WRAPPER = AtmosphereHandlerWrapper.class.getName();
    /**
     * True if the {@link AtmosphereHandlerWrapper} has been modified by an {@link AtmosphereInterceptor}.
     */
    String NEW_MAPPING = AtmosphereHandlerWrapper.class.getName() + ".newMapping";
    /**
     * A reference to the Jersey's {@link ContainerResponse}.
     */
    String CONTAINER_RESPONSE = "org.atmosphere.jersey.containerResponse";
    /**
     * Decide to write extra header.
     */
    String WRITE_HEADERS = AtmosphereResource.class.getName() + ".writeHeader";
    /**
     * Used by a container to tell Atmosphere Runtime what is the expected content type.
     */
    String EXPECTED_CONTENT_TYPE = FrameworkConfig.class.getName() + ".expectedContentType";
    /**
     * The name of the sub-protocol used.
     */
    String WEBSOCKET_SUBPROTOCOL = "websocket-subprotocol";
    /**
     * The SimpleHttpProtocol.
     */
    String SIMPLE_HTTP_OVER_WEBSOCKET = "polling-websocket-message";
    /**
     * The {@link org.atmosphere.websocket.protocol.StreamingHttpProtocol}.
     */
    String STREAMING_HTTP_OVER_WEBSOCKET = "streaming-websocket-message";
    /**
     * Cancel suspending a connection.
     */
    String CANCEL_SUSPEND_OPERATION = "doNotSuspend";
    /**
     * AtmosphereConfig instance.
     */
    String ATMOSPHERE_CONFIG = AtmosphereConfig.class.getName();
    /**
     * Instance of Jersey's ContainerResponseWriter that can be configured by a Framework running on top of Atmosphere.
     */
    String JERSEY_CONTAINER_RESPONSE_WRITER_INSTANCE = "org.atmosphere.jersey.containerResponseWriterInstance";
    /**
     * Current transport used.
     */
    String TRANSPORT_IN_USE = FrameworkConfig.class.getName() + ".transportUsed";
    /**
     * The callback for handshaking the {@link org.atmosphere.interceptor.JavaScriptProtocol}.
     */
    String CALLBACK_JAVASCRIPT_PROTOCOL = JavaScriptProtocol.class.getName() + ".callback";
    /**
     * The Jersey package used for scanning annotation.
     */
    String JERSEY_SCANNING_PACKAGE = "com.sun.jersey.config.property.packages";
    /**
     * The Jersey package used for scanning annotation.
     */
    String JERSEY2_SCANNING_PACKAGE = "jersey.config.server.provider.packages";
    /**
     * Throw Exception from cloned request.
     */
    String THROW_EXCEPTION_ON_CLONED_REQUEST = AtmosphereRequestImpl.NoOpsRequest.class.getName() + ".throwExceptionOnClonedRequest";
    /**
     * The subject for the current request.
     */
    String SECURITY_SUBJECT = AtmosphereRequestImpl.class.getName() + ".subject";
    /**
     * The {@link javax.servlet.AsyncContext}.
     */
    String ASYNC_CONTEXT = "org.atmosphere.container.asyncContext";
    /**
     * A flag indicating a message has been written on the resource. This is useful to know if a resource must be resumed for transport like
     * long-polling.
     */
    String MESSAGE_WRITTEN = Broadcaster.class.getName() + ".messageWritten";
    /**
     * Guice Injector
     */
    String GUICE_INJECTOR = "org.atmosphere.guice.GuiceObjectFactory";
    /**
     * Spring Injector
     */
    String SPRING_INJECTOR = "org.atmosphere.spring.SpringWebObjectFactory";
    /**
     * CDI Injector
     */
    String CDI_INJECTOR = "org.atmosphere.cdi.CDIObjectFactory";
    /**
     * The path that mapped the {@link AtmosphereHandler}
     */
    String MAPPED_PATH = AtmosphereHandler.class.getName() + ".mappedBy";
    /**
     * Tag for WebSocket's Message
     */
    String WEBSOCKET_MESSAGE = WebSocketProcessor.class.getName() + ".websocket.message";
    /**
     * The Java Inject class
     */
    String INJECT_LIBARY = "javax.inject.Inject";
    /**
     * The current installed {@link org.atmosphere.cpr.BroadcasterFactory}
     */
    String BROADCASTER_FACTORY = BroadcasterFactory.class.getName();
    /**
     * Need runtime injection
     */
    String NEED_RUNTIME_INJECTION = InjectIntrospector.WHEN.DEPLOY.getClass().getName();
}
