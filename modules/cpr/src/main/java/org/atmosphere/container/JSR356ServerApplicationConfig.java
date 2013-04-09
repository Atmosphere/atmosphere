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
package org.atmosphere.container;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class JSR356ServerApplicationConfig implements ServerApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(JSR356ServerApplicationConfig.class);

    @Override
    public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpointClasses) {
        logger.debug("{} detected by the WebServer", JSR356ServerApplicationConfig.class.getName());
        return new HashSet<ServerEndpointConfig>() {{
            add(ServerEndpointConfig.Builder.create(JSR356Endpoint.class, "/{path}").configurator(new ServerEndpointConfig.Configurator() {
                public <T> T getEndpointInstance(java.lang.Class<T> endpointClass) throws java.lang.InstantiationException {
                    if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
                        AtmosphereConfig config = BroadcasterFactory.getDefault().lookup("/*", true).getBroadcasterConfig().getAtmosphereConfig();
                        AtmosphereFramework f = config.framework();
                        return (T) new JSR356Endpoint(f, WebSocketProcessorFactory.getDefault().getWebSocketProcessor(f));
                    } else {
                        return super.getEndpointInstance(endpointClass);
                    }
                }

            }).build());
        }};
    }

    @Override
    public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
        return Collections.emptySet();
    }
}
