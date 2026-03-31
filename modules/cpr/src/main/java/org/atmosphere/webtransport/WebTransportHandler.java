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

import java.io.IOException;

/**
 * Application-level handler for WebTransport sessions. Mirrors the
 * {@link org.atmosphere.websocket.WebSocketHandler} contract for WebTransport
 * over HTTP/3.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebTransportHandler {

    /**
     * Invoked when a binary message is received on the bidirectional stream.
     *
     * @param session the {@link WebTransportSession}
     * @param data    the raw bytes
     * @param offset  start offset
     * @param length  number of bytes
     */
    void onByteMessage(WebTransportSession session, byte[] data, int offset, int length) throws IOException;

    /**
     * Invoked when a text message is received on the bidirectional stream.
     *
     * @param session the {@link WebTransportSession}
     * @param data    the decoded text
     */
    void onTextMessage(WebTransportSession session, String data) throws IOException;

    /**
     * Invoked when a WebTransport session is opened.
     *
     * @param session the {@link WebTransportSession}
     */
    void onOpen(WebTransportSession session) throws IOException;

    /**
     * Invoked when a WebTransport session is closed.
     *
     * @param session the {@link WebTransportSession}
     */
    void onClose(WebTransportSession session);

    /**
     * Invoked when a WebTransport session encounters an error.
     *
     * @param session the {@link WebTransportSession}
     * @param t       the exception
     */
    void onError(WebTransportSession session, WebTransportProcessor.WebTransportException t);
}
