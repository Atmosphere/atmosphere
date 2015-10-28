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
package org.atmosphere.container;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.websocket.DeploymentException;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

public class JSR356AsyncSupport extends Servlet30CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JSR356AsyncSupport.class);
    private static final String PATH = "/{path";
    private final AtmosphereConfigurator configurator;
    
    public JSR356AsyncSupport(AtmosphereConfig config) {
        this(config, config.getServletContext());
    }

    public JSR356AsyncSupport(AtmosphereConfig config, ServletContext ctx) {
        super(config);
        ServerContainer container = (ServerContainer) ctx.getAttribute(ServerContainer.class.getName());

        if (container == null) {
            if (ctx.getServerInfo().contains("WebLogic")) {
                logger.error("{} must use JDK 1.8+ with WebSocket", ctx.getServerInfo());
            }
            throw new IllegalStateException("Unable to configure jsr356 at that stage. ServerContainer is null");
        }

        int pathLength = 5;
        String s = config.getInitParameter(ApplicationConfig.JSR356_PATH_MAPPING_LENGTH);
        if (s != null) {
            pathLength = Integer.valueOf(s);
        }
        logger.trace("JSR356 Path mapping Size {}", pathLength);

        String servletPath = config.getInitParameter(ApplicationConfig.JSR356_MAPPING_PATH);
        if (servletPath == null) {
            servletPath = IOUtils.guestServletPath(config);
            if (servletPath.equals("") || servletPath.equals("/") || servletPath.equals("/*")) {
                servletPath = PATH +"}";
            }
        }
        logger.info("JSR 356 Mapping path {}", servletPath);
        configurator = new AtmosphereConfigurator(config.framework());

        StringBuilder b = new StringBuilder(servletPath);
        for (int i = 0; i < pathLength; i++) {
            try {
                container.addEndpoint(ServerEndpointConfig.Builder.create(JSR356Endpoint.class, b.toString()).configurator(configurator).build());
            } catch (DeploymentException e) {
                logger.warn("Duplicate Servlet Mapping Path {}. Use {} init-param to prevent this message", servletPath, ApplicationConfig.JSR356_MAPPING_PATH);
                logger.trace("", e);
                servletPath = IOUtils.guestServletPath(config);
                logger.warn("Duplicate guess {}", servletPath, e);
                b.setLength(0);
                b.append(servletPath);
            }
            b.append(PATH).append(i).append("}");
        }
    }

    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " and jsr356/WebSocket API";
    }

    public final static class AtmosphereConfigurator extends ServerEndpointConfig.Configurator {

        private final AtmosphereFramework framework;
        /**
         * TODO: UGLY!
         * GlassFish/Jetty call modifyHandshake BEFORE getEndpointInstance() where other jsr356 do the reverse.
         */
        final ThreadLocal<JSR356Endpoint> endPoint = new ThreadLocal<JSR356Endpoint>();
        final ThreadLocal<HandshakeRequest> hRequest = new ThreadLocal<HandshakeRequest>();

        public AtmosphereConfigurator(AtmosphereFramework framework) {
            this.framework = framework;
        }

        public <T> T getEndpointInstance(java.lang.Class<T> endpointClass) throws java.lang.InstantiationException {
            if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
                JSR356Endpoint e = new JSR356Endpoint(framework, WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework));
                if (hRequest.get() != null) {
                    e.handshakeRequest(hRequest.get());
                    hRequest.set(null);
                } else {
                    endPoint.set(e);
                }
                return (T) e;
            } else {
                return super.getEndpointInstance(endpointClass);
            }
        }

        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            // Broken GlassFish, which call modifyHandshake BEFORE getEndpointInstance!
            if (endPoint.get() == null) {
                hRequest.set(request);
            } else {
                endPoint.get().handshakeRequest(request);
                endPoint.set(null);
            }
        }
    }
}
