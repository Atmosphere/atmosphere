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

    public final static synchronized WebSocketProcessorFactory getDefault() {
        if (factory == null) {
            factory = new WebSocketProcessorFactory();
        }
        return factory;
    }

    public WebSocketProcessor newWebSocketProcessor(WebSocket webSocket) {
        WebSocketProcessor wp = null;
        String webSocketProcessorName = webSocket.config().framework().getWebSocketProcessorClassName();
        if (!webSocketProcessorName.equalsIgnoreCase(DefaultWebSocketProcessor.class.getName())) {
            try {
                wp = (WebSocketProcessor) Thread.currentThread().getContextClassLoader()
                        .loadClass(webSocketProcessorName).newInstance();
            } catch (Exception ex) {
                try {
                    wp = (WebSocketProcessor) getClass().getClassLoader()
                            .loadClass(webSocketProcessorName).newInstance();
                } catch (Exception ex2) {
                }
            }
        }

        if (wp == null) {
            wp = new DefaultWebSocketProcessor(webSocket);
        }

        return wp;
    }

}
