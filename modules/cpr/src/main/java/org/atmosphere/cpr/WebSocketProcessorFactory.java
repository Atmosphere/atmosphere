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

import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;

/**
 * Factory for {@link WebSocketProcessor}.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketProcessorFactory {

    private static WebSocketProcessorFactory factory;
    private WebSocketProcessor webSocketprocessor;
    private AtmosphereFramework framework;

    public final static synchronized WebSocketProcessorFactory getDefault() {
        if (factory == null) {
            factory = new WebSocketProcessorFactory();
        }
        return factory;
    }

    /**
     * Return the {@link WebSocketProcessor}
     * @param framework {@link AtmosphereFramework}
     * @return an instance of {@link WebSocketProcessor}
     */
    public synchronized WebSocketProcessor getWebSocketProcessor(AtmosphereFramework framework) {
        if (webSocketprocessor == null) {
            this.framework = framework;
            String webSocketProcessorName = framework.getWebSocketProcessorClassName();
            if (!webSocketProcessorName.equalsIgnoreCase(DefaultWebSocketProcessor.class.getName())) {
                try {
                    webSocketprocessor = (WebSocketProcessor) Thread.currentThread().getContextClassLoader()
                            .loadClass(webSocketProcessorName).newInstance();
                } catch (Exception ex) {
                    try {
                        webSocketprocessor = (WebSocketProcessor) getClass().getClassLoader()
                                .loadClass(webSocketProcessorName).newInstance();
                    } catch (Exception ex2) {
                    }
                }
            }

            if (webSocketprocessor == null) {
                webSocketprocessor = new DefaultWebSocketProcessor(framework);
            }
        }

        // More than one Atmosphere Application on the same JVM
        if (!this.framework.equals(framework)) {
            webSocketprocessor = new DefaultWebSocketProcessor(framework);
        }

        return webSocketprocessor;
    }

    public synchronized void destroy() {
        if (webSocketprocessor != null) {
            webSocketprocessor.destroy();
            webSocketprocessor = null;
        }
    }

}
