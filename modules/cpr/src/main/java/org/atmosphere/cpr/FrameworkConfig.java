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

/**
 * Request Attribute a framework integrator can use to lookup Atmosphere internal object.
 *
 * @author Jeanfrancois Arcand
 */
public interface FrameworkConfig {
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
     * The default XMPP Broadcaster class
     */
    String XMPP_BROADCASTER = "org.atmosphere.plugin.xmpp.XMPPBroadcaster";
    /**
     * The default Jersey container class
     */
    String JERSEY_CONTAINER = "com.sun.jersey.spi.container.servlet.ServletContainer";
    /**
     * The web.xml location.
     */
    String WEB_INF_CLASSES = "/WEB-INF/classes/";
    /**
     * A request attribute used to lookup the {@link AtmosphereServlet}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_SERVLET = AtmosphereServlet.class.getName();
    /**
     * A request attribute used to lookup the {@link AtmosphereResource}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    /**
     * Tell a {@link CometSupport} it can support session or not
     */
    String SUPPORT_SESSION = AsynchronousProcessor.class.getName() + ".supportSession";
    /**
     * A request attribute used to lookup the {@link AtmosphereHandler}. This attribute is for framework integrator and not recommend for normal application.
     */
    String ATMOSPHERE_HANDLER = AtmosphereHandler.class.getName();
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
}
