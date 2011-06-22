/*
 * Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.WebSocketProcessor;
import org.atmosphere.websocket.container.Jetty8WebSocketSupport;
import org.atmosphere.websocket.container.JettyWebSocketSupport;
import org.eclipse.jetty.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * Jetty 7 & 8 WebSocket support.
 */
public class JettyWebSocketHandler implements WebSocket, WebSocket.OnFrame, WebSocket.OnBinaryMessage, WebSocket.OnTextMessage, WebSocket.OnControl
{

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocketHandler.class);

    private WebSocketProcessor webSocketProcessor;
    private final HttpServletRequest request;
    private final AtmosphereServlet atmosphereServlet;
    private final String webSocketProcessorClassName;

    public JettyWebSocketHandler(HttpServletRequest request, AtmosphereServlet atmosphereServlet, final String webSocketProcessorClassName)
    {
        this.request = request;
        this.atmosphereServlet = atmosphereServlet;
        this.webSocketProcessorClassName = webSocketProcessorClassName;
    }

    @Override
    public void onConnect(WebSocket.Outbound outbound)
    {
        try {
            webSocketProcessor = (WebSocketProcessor) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProcessorClassName)
                    .getDeclaredConstructor(new Class[]{AtmosphereServlet.class, WebSocketSupport.class})
                    .newInstance(new Object[]{atmosphereServlet, new JettyWebSocketSupport(outbound)});

            webSocketProcessor.connect(new JettyRequestFix(request));
        }
        catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onMessage(byte frame, String data)
    {
        webSocketProcessor.broadcast(data);
    }

    @Override
    public void onMessage(byte frame, byte[] data, int offset, int length)
    {
        webSocketProcessor.broadcast(new String(data, offset, length));
    }

    @Override
    public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length)
    {
        webSocketProcessor.broadcast(new String(data, offset, length));
    }

    @Override
    public void onDisconnect()
    {
        webSocketProcessor.close();
    }

    @Override
    public void onMessage(byte[] data, int offset, int length)
    {
        webSocketProcessor.broadcast(data, offset, length);
    }

    @Override
    public boolean onControl(byte controlCode, byte[] data, int offset, int length)
    {
        webSocketProcessor.broadcast(data, offset, length);
        return false;
    }

    @Override
    public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
    {
        webSocketProcessor.broadcast(data, offset, length);
        logger.debug("WebSocket.onFrame");
        return false;
    }

    @Override
    public void onHandshake(WebSocket.FrameConnection connection)
    {
        logger.debug("WebSocket.onHandshake");
    }

    @Override
    public void onMessage(String data)
    {
        webSocketProcessor.broadcast(data);
    }

    @Override
    public void onOpen(WebSocket.Connection connection)
    {
        try {        
            webSocketProcessor = (WebSocketProcessor) JettyWebSocketHandler.class.getClassLoader()
                    .loadClass(webSocketProcessorClassName)
                    .getDeclaredConstructor(new Class[]{AtmosphereServlet.class, WebSocketSupport.class})
                    .newInstance(new Object[]{atmosphereServlet, new Jetty8WebSocketSupport(connection)});
            webSocketProcessor.connect(new JettyRequestFix(request));
        } catch (Exception e) {
            logger.warn("failed to connect to web socket", e);
        }
    }

    @Override
    public void onClose(int closeCode, String message)
    {
        webSocketProcessor.close();
    }

    /**
     * https://issues.apache.org/jira/browse/WICKET-3190
     */
    private static class JettyRequestFix extends HttpServletRequestWrapper
    {

        public JettyRequestFix(HttpServletRequest request)
        {
            super(request);
        }

        /**
         * Jetty's Websocket doesn't computer the ContextPath properly for WebSocket.
         *
         * @return
         */
        public String getContextPath()
        {
            String uri = getRequestURI();
            String path = super.getContextPath();
            if (path == null) {
                path = uri.substring(0, uri.indexOf("/", 1));
            }
            return path;
        }
    }

}
