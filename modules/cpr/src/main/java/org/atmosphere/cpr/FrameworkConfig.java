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

import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereRequest.NoOpsRequest;
import org.atmosphere.interceptor.JavaScriptProtocol;

/**
 * Request Attribute a framework integrator can use to lookup Atmosphere internal object.
 *
 * @author Jeanfrancois Arcand
 */
public interface FrameworkConfig {
    /**
     * The default Hazelcast Broadcaster class
     */
    String HAZELCAST_BROADCASTER = "org.atmosphere.plugin.hazelcast.HazelcastBroadcaster";
    /**
     * The default Jersey Broadcaster class
     */
    String JERSEY_BROADCASTER = "org.atmosphere.jersey.JerseyBroadcaster";
    /**
     * The default Redis Broadcaster class
     */
    String REDIS_BROADCASTER = "org.atmosphere.plugin.redis.RedisBroadcaster";
    /**
     * The default JMS Broadcaster class
     */
    String JMS_BROADCASTER = "org.atmosphere.plugin.jms.JMSBroadcaster";
    /**
     * The default JGroups Broadcaster class
     */
    String JGROUPS_BROADCASTER = "org.atmosphere.plugin.jgroups.JGroupsBroadcaster";
    /**
     * The default RMI Broadcaster class
     */
    String RMI_BROADCASTER = "org.atmosphere.plugin.rmi.RMIBroadcaster";
    /**
     * The default RabbitMQ Broadcaster class
     */
    String RABBITMQ_BROADCASTER = "org.atmosphere.plugin.rabbitmq.RabbitMQBroadcaster";
    /**
     * The default XMPP Broadcaster class
     */
    String XMPP_BROADCASTER = "org.atmosphere.plugin.xmpp.XMPPBroadcaster";
    /**
     * The default Jersey container class
     */
    String JERSEY_CONTAINER = "com.sun.jersey.spi.container.servlet.ServletContainer";
    /**
     * A request attribute used to lookup the {@link AtmosphereNativeCometServlet}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_SERVLET = "org.atmosphere.cpr.AtmosphereServlet";
    /**
     * A request attribute used to lookup the {@link AtmosphereResource}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    /**
     * A request attribute used to lookup the {@link AtmosphereResource} created by an external component and injected inside the {@link AsynchronousProcessor}
     */
    String INJECTED_ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName() + ".injected";
    /**
     * Tell a {@link AsyncSupport} it can support session or not
     */
    String SUPPORT_SESSION = AsynchronousProcessor.class.getName() + ".supportSession";
    /**
     * A request attribute used to lookup the {@link AtmosphereHandler}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_HANDLER_WRAPPER = AtmosphereHandlerWrapper.class.getName();
    /**
     * True if the {@link AtmosphereHandlerWrapper} has been modified by an {@link AtmosphereInterceptor}
     */
    String NEW_MAPPING = AtmosphereHandlerWrapper.class.getName() + ".newMapping";
    /**
     * A reference to the Jersey's {@link ContainerResponse}
     */
    String CONTAINER_RESPONSE = "org.atmosphere.jersey.containerResponse";
    /**
     * Decide to write extra header.
     */
    String WRITE_HEADERS = AtmosphereResource.class.getName() + ".writeHeader";
    /**
     * Used by a Container to tell Atmosphere Runtime what is the expected content type
     */
    String EXPECTED_CONTENT_TYPE = FrameworkConfig.class.getName() + ".expectedContentType";
    /**
     * The name of the sub-protocol used.
     */
    String WEBSOCKET_SUBPROTOCOL = "websocket-subprotocol";
    /**
     * The SimpleHttpProtocol
     */
    String SIMPLE_HTTP_OVER_WEBSOCKET = "polling-websocket-message";
    /**
     * Cance suspending a connection
     */
    String CANCEL_SUSPEND_OPERATION = "doNotSuspend";
    /**
     * AtmosphereConfig instance
     */
    String ATMOSPHERE_CONFIG = AtmosphereConfig.class.getName();
    /**
     * Instance of Jersey's ContainerResponseWriter that can be configured by a Framework running on top of Atmosphere
     */
    String JERSEY_CONTAINER_RESPONSE_WRITER_INSTANCE = "org.atmosphere.jersey.containerResponseWriterInstance";
    /**
     * Current transport used
     */
    String TRANSPORT_IN_USE = AtmosphereConfig.class.getName() + ".transportUsed";
    /**
     *  Callback hook for Framework implementing Atmosphere support.
     */
    String ASYNCHRONOUS_HOOK = FrameworkConfig.class.getName() + ".asynchronousProcessorHook";
    /**
     * The Callback for handshaking the {@link org.atmosphere.interceptor.JavaScriptProtocol}
     */
    String CALLBACK_JAVASCRIPT_PROTOCOL = JavaScriptProtocol.class.getName() + ".callback";
    /**
     * The Jersey package used for scanning annotation.
     */
    String JERSEY_SCANNING_PACKAGE = "com.sun.jersey.config.property.packages";
    /**
     * Throw Exception from cloned request
     */
    String THROW_EXCEPTION_ON_CLONED_REQUEST = NoOpsRequest.class.getName() + ".throwExceptionOnClonedRequest";
}
