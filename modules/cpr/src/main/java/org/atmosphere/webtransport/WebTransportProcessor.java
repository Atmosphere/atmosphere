/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.SimpleBroadcaster;

import java.io.IOException;

/**
 * WebTransport processing SPI. Mirrors {@link org.atmosphere.websocket.WebSocketProcessor}
 * but without servlet handshake validation (WebTransport uses HTTP/3 extended CONNECT,
 * validated at the Reactor Netty level) and without {@code InputStream}/{@code Reader}
 * overloads (QUIC streams are natively byte-oriented).
 *
 * <p>The default implementation lives in the Spring Boot starter module since it
 * requires Reactor Netty. The SPI here is transport-agnostic.</p>
 *
 * @author Jeanfrancois Arcand
 */
public interface WebTransportProcessor {

    /**
     * Configure this processor.
     *
     * @param config the {@link AtmosphereConfig}
     * @return this
     */
    WebTransportProcessor configure(AtmosphereConfig config);

    /**
     * Register a {@link WebTransportHandler} for a given path.
     *
     * @param path    the URI mapping
     * @param handler a {@link WebTransportHandlerProxy}
     * @return this
     */
    WebTransportProcessor registerWebTransportHandler(String path, WebTransportHandlerProxy handler);

    /**
     * Invoked when a WebTransport session is opened.
     *
     * @param session  the {@link WebTransportSession}
     * @param request  the initial {@link AtmosphereRequest}
     * @param response the {@link AtmosphereResponse}
     */
    void open(WebTransportSession session, AtmosphereRequest request, AtmosphereResponse response) throws IOException;

    /**
     * Invoked when a text message is received on a bidirectional stream.
     *
     * @param session the {@link WebTransportSession}
     * @param message the text message
     */
    void invokeWebTransportProtocol(WebTransportSession session, String message);

    /**
     * Invoked when a binary message is received on a bidirectional stream.
     *
     * @param session the {@link WebTransportSession}
     * @param data    the raw bytes
     * @param offset  start offset
     * @param length  number of bytes
     */
    void invokeWebTransportProtocol(WebTransportSession session, byte[] data, int offset, int length);

    /**
     * Invoked when the WebTransport session is closed.
     *
     * @param session   the {@link WebTransportSession}
     * @param closeCode the HTTP/3 error code (0-255)
     */
    void close(WebTransportSession session, int closeCode);

    /**
     * Notify all {@link WebTransportEventListener}s of an event.
     *
     * @param session the {@link WebTransportSession}
     * @param event   the event
     */
    void notifyListener(WebTransportSession session, WebTransportEventListener.WebTransportEvent<?> event);

    /**
     * Destroy all resources associated with this processor.
     */
    void destroy();

    /**
     * Exception type for WebTransport processing errors.
     */
    final class WebTransportException extends Exception {

        private final AtmosphereResponse r;

        public WebTransportException(String s, AtmosphereResponse r) {
            super(s);
            this.r = r;
        }

        public WebTransportException(Throwable throwable, AtmosphereResponse r) {
            super(throwable);
            this.r = r;
        }

        public AtmosphereResponse response() {
            return r;
        }
    }

    /**
     * Proxy that associates a {@link WebTransportHandler} with a
     * {@link Broadcaster} class, mirroring
     * {@link org.atmosphere.websocket.WebSocketProcessor.WebSocketHandlerProxy}.
     */
    final class WebTransportHandlerProxy implements WebTransportHandler {

        final Class<? extends Broadcaster> broadcasterClazz;
        final WebTransportHandler proxied;
        private String path;

        public WebTransportHandlerProxy(Class<? extends Broadcaster> broadcasterClazz, WebTransportHandler proxied) {
            this.broadcasterClazz = broadcasterClazz;
            this.proxied = proxied;
        }

        public WebTransportHandlerProxy(WebTransportHandler proxied) {
            this.broadcasterClazz = SimpleBroadcaster.class;
            this.proxied = proxied;
        }

        public String path() {
            return path;
        }

        public WebTransportHandlerProxy path(String path) {
            this.path = path;
            return this;
        }

        public WebTransportHandler proxied() {
            return proxied;
        }

        @Override
        public void onByteMessage(WebTransportSession session, byte[] data, int offset, int length) throws IOException {
            proxied.onByteMessage(session, data, offset, length);
        }

        @Override
        public void onTextMessage(WebTransportSession session, String data) throws IOException {
            proxied.onTextMessage(session, data);
        }

        @Override
        public void onOpen(WebTransportSession session) throws IOException {
            proxied.onOpen(session);
        }

        @Override
        public void onClose(WebTransportSession session) {
            proxied.onClose(session);
        }

        @Override
        public void onError(WebTransportSession session, WebTransportException t) {
            proxied.onError(session, t);
        }
    }
}
