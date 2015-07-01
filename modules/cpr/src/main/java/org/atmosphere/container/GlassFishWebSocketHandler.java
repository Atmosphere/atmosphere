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

import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.websockets.DataFrame;
import com.sun.grizzly.websockets.DefaultWebSocket;
import com.sun.grizzly.websockets.ProtocolHandler;
import com.sun.grizzly.websockets.ServerNetworkHandler;
import com.sun.grizzly.websockets.WebSocket;
import com.sun.grizzly.websockets.WebSocketApplication;
import com.sun.grizzly.websockets.WebSocketListener;
import org.atmosphere.container.version.GrizzlyWebSocket;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glassfish 3.2.x WebSocket support.
 */
public class GlassFishWebSocketHandler extends WebSocketApplication {
    private static final Logger logger = LoggerFactory.getLogger(GlassFishWebSocketSupport.class);

    private final AtmosphereConfig config;
    private final HashMap<String, Boolean> paths = new HashMap<String, Boolean>();
    private final WebSocketProcessor webSocketProcessor;
    // This is so bad, but Glassfish clear the attribute of the webSocket request
    private final ConcurrentHashMap<WebSocket, org.atmosphere.websocket.WebSocket>
            wMap = new ConcurrentHashMap<WebSocket, org.atmosphere.websocket.WebSocket>();
    private final EndpointMapper<Boolean> mapper = new DefaultEndpointMapper<Boolean>();

    public GlassFishWebSocketHandler(AtmosphereConfig config) {
        this.config = config;

        paths(config.getServletContext());
        webSocketProcessor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(config.framework());
    }

    @Override
    public WebSocket createWebSocket(ProtocolHandler protocolHandler, final WebSocketListener... listeners) {
        ServerNetworkHandler handler = (ServerNetworkHandler)protocolHandler.getNetworkHandler();
        HttpServletRequest req = null;
        try {
            req = handler.getRequest();
        } catch (IOException ex) {
        }
        if (!webSocketProcessor.handshake(req)) {
            protocolHandler.close(0x00, "");
            throw new IllegalStateException();
        }
        return super.createWebSocket(protocolHandler,listeners);
    }

    void paths(ServletContext sc) {
        try {
            Map<String, ? extends ServletRegistration> m = config.getServletContext().getServletRegistrations();

            ServletRegistration sr = m.get(config.getServletConfig().getServletName());

            if (sr != null) {
                for (String mapping : sr.getMappings()) {
                    if (mapping.contains("*")) {
                        mapping = mapping.replace("*", AtmosphereFramework.MAPPING_REGEX);
                    }

                    if (mapping.endsWith("/")) {
                        mapping = mapping + AtmosphereFramework.MAPPING_REGEX;
                    }
                    paths.put(mapping, Boolean.TRUE);
                }
            }
        } catch (Exception ex) {
            logger.error("{}", ex);
        }
    }

    @Override
    public void onConnect(WebSocket w) {
        super.onConnect(w);

        org.atmosphere.websocket.WebSocket webSocket = new GrizzlyWebSocket(w, config);

        //logger.debug("onOpen");
        if (!DefaultWebSocket.class.isAssignableFrom(w.getClass())) {
            throw new IllegalStateException();
        }

        DefaultWebSocket dws = DefaultWebSocket.class.cast(w);
        wMap.put(w,webSocket);

        try {

            AtmosphereRequest r = AtmosphereRequestImpl.wrap(dws.getRequest());
            AtmosphereResponse response = AtmosphereResponseImpl.newInstance(config, r, webSocket);
            config.framework().configureRequestResponse(r, response);
            try {
                // Stupid Stupid Stupid
               if (r.getPathInfo() == null) {
                    String uri = r.getRequestURI();
                    String pathInfo = uri.substring(uri.indexOf(r.getServletPath()) + r.getServletPath().length());
                    r.pathInfo(pathInfo);
                }
            } catch (Exception e) {
                // Whatever exception occurs skip it
                logger.trace("", e);
            }
            webSocketProcessor.open(webSocket, r, response);
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public boolean isApplicationRequest(Request request) {
        String path = request.requestURI().toString();

        // remove contextpath from start of request, which may not happen if webapp is set as the default-web-module
        String contextPath = config.getServletContext().getContextPath();
        if (path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        Boolean b = mapper.map(path, paths);
        return b == null? false: b;
    }

    @Override
    public void onClose(WebSocket w, DataFrame df) {
        super.onClose(w, df);
        logger.trace("onClose {} ", w);
        if (webSocketProcessor != null) {
            webSocketProcessor.close(wMap.remove(w), 1005);
        }
    }

    @Override
    public void onMessage(WebSocket w, String text) {
        logger.trace("onMessage {} ", w);
        if (webSocketProcessor != null) {
            webSocketProcessor.invokeWebSocketProtocol(w(w), text);
        }
    }

    @Override
    public void onMessage(WebSocket w, byte[] bytes) {
        logger.trace("onMessage (bytes) {} ", w);
        if (webSocketProcessor != null) {
            webSocketProcessor.invokeWebSocketProtocol(w(w), bytes, 0, bytes.length);
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

    org.atmosphere.websocket.WebSocket w(WebSocket w) {
        return wMap.get(w);
    }
}
