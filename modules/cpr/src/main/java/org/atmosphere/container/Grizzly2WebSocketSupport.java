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

import org.atmosphere.container.version.Grizzly2WebSocket;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.util.DispatcherHelper;
import org.glassfish.grizzly.http.server.util.MappingData;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.servlet.HttpServletRequestImpl;
import org.glassfish.grizzly.servlet.HttpServletResponseImpl;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.DefaultWebSocket;
import org.glassfish.grizzly.websockets.ProtocolHandler;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.grizzly.websockets.WebSocketException;
import org.glassfish.grizzly.websockets.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Grizzly2WebSocketSupport extends AsynchronousProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Grizzly2WebSocketSupport.class);

    private Grizzly2WebSocketApplication application;


    // ------------------------------------------------------------ Constructors

    public Grizzly2WebSocketSupport(AtmosphereConfig config) {
        super(config);
        application = new Grizzly2WebSocketApplication(config);
        WebSocketEngine.getEngine().register(application);
    }


    // -------------------------------------- Methods from AsynchronousProcessor

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {
        return suspended(req, res);
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo() + " with WebSocket enabled.";
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public void shutdown() {
        WebSocketEngine.getEngine().unregister(application);
        super.shutdown();
    }

    // ---------------------------------------------------------- Nested Classes


    private static final class Grizzly2WebSocketApplication extends WebSocketApplication {

        private AtmosphereConfig config;

        // -------------------------------------------------------- Constructors


        public Grizzly2WebSocketApplication(AtmosphereConfig config) {
            this.config = config;
        }


        // --------------------------- Methods from Grizzly2WebSocketApplication


        @Override
        public boolean isApplicationRequest(HttpRequestPacket request) {
            return true;
        }

        @Override
        public WebSocket createSocket(ProtocolHandler handler, HttpRequestPacket requestPacket, WebSocketListener... listeners) {
            return new G2WebSocket(handler, requestPacket, listeners);
        }

        @Override
        public void onClose(WebSocket socket, DataFrame frame) {
            super.onClose(socket, frame);
            LOGGER.trace("onClose {} ", socket);
            G2WebSocket webSocket = G2WebSocket.class.cast(socket);
            if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
                WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
                webSocketProcessor.close(1000);
            }
        }

        @Override
        public void onConnect(WebSocket socket) {
            super.onConnect(socket);
            LOGGER.trace("onConnect {} ", socket);

            if (!G2WebSocket.class.isAssignableFrom(socket.getClass())) {
                throw new IllegalStateException();
            }

            G2WebSocket webSocket = G2WebSocket.class.cast(socket);
            try {

                AtmosphereRequest r = AtmosphereRequest.wrap(webSocket.getRequest());
//                try {
//                    // GlassFish http://java.net/jira/browse/GLASSFISH-18681
//                    if (r.getPathInfo().startsWith(r.getContextPath())) {
//                        r.servletPath(r.getPathInfo().substring(r.getContextPath().length()));
//                        r.pathInfo(null);
//                    }
//                } catch (Exception e) {
//                    // Whatever exception occurs skip it
//                    LOGGER.trace("", e);
//                }

                WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault()
                        .newWebSocketProcessor(new Grizzly2WebSocket(webSocket, config));
                webSocket.getRequest().setAttribute("grizzly.webSocketProcessor", webSocketProcessor);
                webSocketProcessor.open(r);
            } catch (Exception e) {
                LOGGER.warn("failed to connect to web socket", e);
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            super.onMessage(socket, text);
            LOGGER.trace("onMessage(String) {} ", socket);
            G2WebSocket webSocket = G2WebSocket.class.cast(socket);
            if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
                WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
                webSocketProcessor.invokeWebSocketProtocol(text);
            }
        }

        @Override
        public void onMessage(WebSocket socket, byte[] bytes) {
            super.onMessage(socket, bytes);
            LOGGER.trace("onMessage(byte[]) {} ", socket);
            G2WebSocket webSocket = G2WebSocket.class.cast(socket);
            if (webSocket.getRequest().getAttribute("grizzly.webSocketProcessor") != null) {
                WebSocketProcessor webSocketProcessor = (WebSocketProcessor) webSocket.getRequest().getAttribute("grizzly.webSocketProcessor");
                webSocketProcessor.invokeWebSocketProtocol(bytes, 0, bytes.length);
            }
        }

        @Override
        public void onPing(WebSocket socket, byte[] bytes) {
            LOGGER.trace("onPing {} ", socket);
        }

        @Override
        public void onPong(WebSocket socket, byte[] bytes) {
            LOGGER.trace("onPong {} ", socket);
        }

        @Override
        public void onFragment(WebSocket socket, String fragment, boolean last) {
            LOGGER.trace("onFragment(String) {} ", socket);
        }

        @Override
        public void onFragment(WebSocket socket, byte[] fragment, boolean last) {
            LOGGER.trace("onFragment(byte) {} ", socket);
        }

        // ---------------------------------------------------------- Nested Classes


        private final class G2WebSocket extends DefaultWebSocket {

            private final HttpServletRequestImpl servletRequest;
            private final HttpServletResponseImpl servletResponse;


            // -------------------------------------------------------- Constructors


            public G2WebSocket(ProtocolHandler protocolHandler,
                               HttpRequestPacket request,
                               WebSocketListener... listeners) {
                super(protocolHandler, request, listeners);
                Request req = Request.create();
                Response res = req.getResponse();
                req.initialize(request, protocolHandler.getFilterChainContext(), null);
                res.initialize(req, request.getResponse(), protocolHandler.getFilterChainContext(), null, null);
                servletRequest = HttpServletRequestImpl.create();
                servletResponse = HttpServletResponseImpl.create();
                try {
                    WebappContext context = (WebappContext) config.getServletContext();
                    servletRequest.initialize(req, context);
                    servletResponse.initialize(res);
                    mapRequest(context, request, servletRequest);
                } catch (IOException e) {
                    throw new WebSocketException("Unable to initialize WebSocket instance", e);
                }

            }


            // -------------------------------------------------- Public Methods


            public HttpServletRequest getRequest() {
                return servletRequest;
            }

            public HttpServletResponse getResponse() {
                return servletResponse;
            }


            // ------------------------------------------------- Private Methods

            /*
             * Hold your nose!
             */
            private void mapRequest(WebappContext ctx, HttpRequestPacket request, HttpServletRequestImpl servletRequest) {
                try {
                    Field dispatcher = WebappContext.class.getDeclaredField("dispatcherHelper");
                    dispatcher.setAccessible(true);
                    MappingData data = new MappingData();
                    DispatcherHelper helper = (DispatcherHelper) dispatcher.get(ctx);
                    DataChunk host = DataChunk.newInstance();
                    host.setString(request.getHeader("host"));
                    helper.mapPath(host, request.getRequestURIRef().getDecodedRequestURIBC(), data);
                    servletRequest.setServletPath(data.wrapperPath.toString());
                    Method m = HttpServletRequestImpl.class.getDeclaredMethod("setPathInfo", String.class);
                    m.setAccessible(true);
                    m.invoke(servletRequest, data.pathInfo.toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

        } // END G2WebSocket

    } // END Grizzly2WebSocketApplication

}
