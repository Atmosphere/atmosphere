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

import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.websockets.DataFrame;
import com.sun.grizzly.websockets.DefaultWebSocket;
import com.sun.grizzly.websockets.WebSocket;
import com.sun.grizzly.websockets.WebSocketApplication;
import org.atmosphere.container.version.GrizzlyWebSocket;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Glassfish 3.2.x WebSocket support.
 */
public class GlassFishWebSocketHandler extends WebSocketApplication {
    private static final Logger logger = LoggerFactory.getLogger(GlassFishWebSocketSupport.class);
    
    private final AtmosphereConfig config;

    public GlassFishWebSocketHandler(AtmosphereConfig config) {
        this.config = config;
    }

    public void onConnect(WebSocket w) {
        super.onConnect(w);
        //logger.debug("onOpen");
        if (!DefaultWebSocket.class.isAssignableFrom(w.getClass())) {
            throw new IllegalStateException();
        }

        DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
        try {

            AtmosphereRequest r = AtmosphereRequest.wrap(webSocket.getRequest());
            try {
                // GlassFish http://java.net/jira/browse/GLASSFISH-18681
                if (r.getPathInfo().startsWith(r.getContextPath())) {
                    r.servletPath(r.getPathInfo().substring(r.getContextPath().length()));
                    r.pathInfo(null);
                }
            } catch (Exception e) {
                // Whatever exception occurs skip it
                logger.trace("", e);
            }

            WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault()
                    .newWebSocketProcessor(new GrizzlyWebSocket(webSocket, config));
            webSocket.getRequest().setAttribute("grizzly.webSocketProcessor", webSocketProcessor);
            webSocketProcessor.open(r);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public boolean isApplicationRequest(Request request) {
        return true;
    }

    @Override
    public void onClose(WebSocket w, DataFrame df) {
        super.onClose(w, df);
        logger.trace("onClose {} ", w);
        DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
        if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
            WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
            webSocketProcessor.close(1000);
        }
    }

    @Override
    public void onMessage(WebSocket w, String text) {
        logger.trace("onMessage {} ", w);
        DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
        if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
            WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
            webSocketProcessor.invokeWebSocketProtocol(text);
        }
    }

    @Override
    public void onMessage(WebSocket w, byte[] bytes) {
        logger.trace("onMessage (bytes) {} ", w);
        DefaultWebSocket webSocket = DefaultWebSocket.class.cast(w);
        if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
            WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
            webSocketProcessor.invokeWebSocketProtocol(bytes, 0, bytes.length);
        }
    }

    @Override
    public void onPing(WebSocket w, byte[] bytes) {
        logger.trace("onPing (bytes) {} ", w);
    }

    @Override
    public void onPong(WebSocket w, byte[] bytes) {
        logger.trace("onPong (bytes) {} ", w);
    }

    @Override
    public void onFragment(WebSocket w, byte[] bytes, boolean last) {
        logger.trace("onFragment (bytes) {} ", w);
    }

    @Override
    public void onFragment(WebSocket w, String text, boolean last) {
        logger.trace("onFragment (string) {} ", w);
    }

}