/*
* Copyright 2008-2024 Async-IO.org
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
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Jetty 9 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class Jetty9AsyncSupportWithWebSocket extends AbstractJetty9AsyncSupportWithWebSocket {
    private static final Logger logger = LoggerFactory.getLogger(Jetty9AsyncSupportWithWebSocket.class);
    private final WebSocketServerFactory webSocketFactory;

    public Jetty9AsyncSupportWithWebSocket(final AtmosphereConfig config) {
        super(config, logger);

        final WebSocketPolicy policy = GetPolicy(config);
        final WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());

        webSocketFactory = new WebSocketServerFactory(config.getServletContext(), policy) {
            @Override
            public boolean acceptWebSocket(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
                setCreator(Jetty9AsyncSupportWithWebSocket.this.buildCreator(request, response, webSocketProcessor));
                return super.acceptWebSocket(request, response);
            }
        };

        try {
            webSocketFactory.start();
        } catch (Exception e) {
            logger.error("", e);
        }
    }

    @Override
    WebSocketServerFactory getWebSocketFactory() {
        return webSocketFactory;
    }
}
