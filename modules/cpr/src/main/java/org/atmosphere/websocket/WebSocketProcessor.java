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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Atmosphere's WebSocket Support implementation. The default behavior is implemented in {@link DefaultWebSocketProcessor}.
 * This class is targeted at framework developer as it requires Atmosphere's internal knowledge.
 * <br/>
 * This class can also be used to implement the JSR 345 recommendation.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketProcessor {

    /**
     * Configure, or post construct a WebSocketProcessor
     * @param config an {@link org.atmosphere.cpr.AtmosphereConfig}
     * @return this
     */
    WebSocketProcessor configure(AtmosphereConfig config);

    /**
     * Determine if the WebSocket's handshake data can be processed, or if the request be cancelled. Since it's container
     * related native API, the {@link HttpServletRequest} might be null, so implementation must check for null.
     *
     * @param request {@link HttpServletRequest}
     * @return true if the processing can continue, false if not.
     */
    boolean handshake(HttpServletRequest request);

    /**
     * Register a {@link WebSocketHandler}
     *
     * @param path             the URI mapping the WebSocketHandler
     * @param webSockethandler an instance of {@link org.atmosphere.websocket.WebSocketProcessor.WebSocketHandlerProxy}
     * @return this
     */
    WebSocketProcessor registerWebSocketHandler(String path, WebSocketHandlerProxy webSockethandler);

    /**
     * Invoked when a WebSocket gets opened by the underlying container
     *
     * @param request
     * @throws IOException
     */
    void open(WebSocket webSocket, AtmosphereRequest request, AtmosphereResponse response) throws IOException;

    /**
     * Invoked when a WebSocket message gets received from the underlying container
     *
     * @param webSocketMessage
     */
    void invokeWebSocketProtocol(WebSocket webSocket, String webSocketMessage);

    /**
     * Invoked when a WebSocket message gets received from the underlying container
     *
     * @param data
     */
    void invokeWebSocketProtocol(WebSocket webSocket, byte[] data, int offset, int length);

    /**
     * Invoked when a WebSocket message gets received from the underlying container
     *
     * @param stream
     */
    void invokeWebSocketProtocol(WebSocket webSocket, InputStream stream);

    /**
     * Invoked when a WebSocket message gets received from the underlying container
     *
     * @param reader
     */
    void invokeWebSocketProtocol(WebSocket webSocket, Reader reader) throws IOException;

    /**
     * Invked when the WebServer is closing the native WebSocket
     *
     * @param closeCode
     */
    public void close(WebSocket webSocket, int closeCode);

    /**
     * Notify all {@link WebSocketEventListener}
     *
     * @param webSocketEvent
     */
    void notifyListener(WebSocket webSocket, WebSocketEvent webSocketEvent);


    /**
     * Destroy all resources associated with this class.
     */
    void destroy();

    /**
     * An exception that can be used to flag problems with the WebSocket processing.
     */
    public final static class WebSocketException extends Exception {

        private final AtmosphereResponse r;

        public WebSocketException(String s, AtmosphereResponse r) {
            super(s);
            this.r = r;
        }

        public WebSocketException(Throwable throwable, AtmosphereResponse r) {
            super(throwable);
            this.r = r;
        }

        public AtmosphereResponse response() {
            return r;
        }
    }

    public final static class WebSocketHandlerProxy implements WebSocketHandler {

        final Class<? extends Broadcaster> broadcasterClazz;
        final WebSocketHandler proxied;
        private String path;

        public WebSocketHandlerProxy(Class<? extends Broadcaster> broadcasterClazz, WebSocketHandler proxied) {
            this.broadcasterClazz = broadcasterClazz;
            this.proxied = proxied;
        }

        public WebSocketHandlerProxy(WebSocketHandler proxied) {
            this.broadcasterClazz = SimpleBroadcaster.class;
            this.proxied = proxied;
        }

        public String path() {
            return path;
        }

        public WebSocketHandlerProxy path(String path) {
            this.path = path;
            return this;
        }

        public WebSocketHandler proxied(){
            return proxied;
        }

        @Override
        public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException {
            proxied.onByteMessage(webSocket,data,offset,length);
        }

        @Override
        public void onTextMessage(WebSocket webSocket, String data) throws IOException {
            proxied.onTextMessage(webSocket, data);
        }

        @Override
        public void onOpen(WebSocket webSocket) throws IOException {
            proxied.onOpen(webSocket);
        }

        @Override
        public void onClose(WebSocket webSocket) {
            proxied.onClose(webSocket);
        }

        @Override
        public void onError(WebSocket webSocket, WebSocketException t) {
            proxied.onError(webSocket,t);
        }
    }
}
