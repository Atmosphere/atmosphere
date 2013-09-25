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
package org.atmosphere.container;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.DeploymentException;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

public class JSR356AsyncSupport extends Servlet30CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JSR356AsyncSupport.class);
    private static final String PATH = "/{path";
    private final AtmosphereConfigurator configurator = new AtmosphereConfigurator();

    public JSR356AsyncSupport(AtmosphereConfig config) {
        super(config);
        ServerContainer container = (ServerContainer) config.getServletContext().getAttribute(ServerContainer.class.getName());

        int pathLength = 5;
        String s = config.getInitParameter(ApplicationConfig.JSR356_PATH_MAPPING_LENGTH);
        if (s != null) {
            pathLength = Integer.valueOf(s);
        }
        logger.trace("JSR356 Path mapping Size {}", pathLength);
        StringBuilder b = new StringBuilder(PATH).append("}");
        for (int i = 0; i < pathLength; i++) {
            try {
                container.addEndpoint(ServerEndpointConfig.Builder.create(JSR356Endpoint.class, b.toString()).configurator(configurator).build());
            } catch (DeploymentException e) {
                throw new RuntimeException(e);
            }
            b.append(PATH).append(i).append("}");
        }
    }

    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " using javax.servlet/3.0 and jsr356/WebSocket API";
    }

    public final static class AtmosphereConfigurator extends ServerEndpointConfig.Configurator {

        final ThreadLocal<JSR356Endpoint> endPoint = new ThreadLocal<JSR356Endpoint>();

        public <T> T getEndpointInstance(java.lang.Class<T> endpointClass) throws java.lang.InstantiationException {
            if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
                AtmosphereConfig config = BroadcasterFactory.getDefault().lookup("/*", true).getBroadcasterConfig().getAtmosphereConfig();
                AtmosphereFramework f = config.framework();
                JSR356Endpoint e = new JSR356Endpoint(f, WebSocketProcessorFactory.getDefault().getWebSocketProcessor(f));
                endPoint.set(e);
                return (T) e;
            } else {
                return super.getEndpointInstance(endpointClass);
            }
        }

        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            endPoint.get().handshakeRequest(request);
            endPoint.set(null);
        }
    }
}
