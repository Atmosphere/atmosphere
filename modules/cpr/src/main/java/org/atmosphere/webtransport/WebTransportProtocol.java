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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.inject.AtmosphereConfigAware;

import java.util.List;

/**
 * Protocol for converting WebTransport messages into {@link AtmosphereRequest}
 * objects that can be dispatched through the Atmosphere framework. Mirrors
 * {@link org.atmosphere.websocket.WebSocketProtocol}.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebTransportProtocol extends AtmosphereConfigAware {

    /**
     * Parse a text message received on a WebTransport bidirectional stream
     * and convert it to a list of {@link AtmosphereRequest} for dispatch.
     *
     * @param session the {@link WebTransportSession}
     * @param data    the text message
     * @return a list of requests to dispatch, or null to handle dispatch manually
     */
    List<AtmosphereRequest> onMessage(WebTransportSession session, String data);

    /**
     * Parse a binary message received on a WebTransport bidirectional stream
     * and convert it to a list of {@link AtmosphereRequest} for dispatch.
     *
     * @param session the {@link WebTransportSession}
     * @param data    the raw bytes
     * @param offset  start offset
     * @param length  number of bytes
     * @return a list of requests to dispatch, or null to handle dispatch manually
     */
    List<AtmosphereRequest> onMessage(WebTransportSession session, byte[] data, int offset, int length);

    /**
     * Invoked when a WebTransport session is opened.
     *
     * @param session the {@link WebTransportSession}
     */
    void onOpen(WebTransportSession session);

    /**
     * Invoked when a WebTransport session is closed.
     *
     * @param session the {@link WebTransportSession}
     */
    void onClose(WebTransportSession session);

    /**
     * Invoked when an error occurs on the WebTransport session.
     *
     * @param session the {@link WebTransportSession}
     * @param t       the exception
     */
    void onError(WebTransportSession session, WebTransportProcessor.WebTransportException t);
}
