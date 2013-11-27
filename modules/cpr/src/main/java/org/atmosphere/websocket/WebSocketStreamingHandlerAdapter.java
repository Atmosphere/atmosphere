/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * An Adapter for {@link WebSocketStreamingHandlerAdapter}
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketStreamingHandlerAdapter implements WebSocketStreamingHandler {

    private final Logger logger = LoggerFactory.getLogger(WebSocketStreamingHandlerAdapter.class);
    @Override
    public void onBinaryStream(WebSocket webSocket, InputStream inputStream) throws IOException {
        logger.trace("onBinaryStream {}", webSocket);
    }

    @Override
    public void onTextStream(WebSocket webSocket, Reader reader) throws IOException {
        logger.trace("onTextStream {}", webSocket);
    }

    @Override
    public void onByteMessage(WebSocket webSocket, byte[] data, int offset, int length) throws IOException {
        logger.trace("onByteMessage {}", webSocket);
    }

    @Override
    public void onTextMessage(WebSocket webSocket, String data) throws IOException {
        logger.trace("onTextMessage {}", webSocket);
    }

    @Override
    public void onOpen(WebSocket webSocket) throws IOException {
        logger.trace("onOpen {}", webSocket);
    }

    @Override
    public void onClose(WebSocket webSocket) {
        logger.trace("onClose {}", webSocket);
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.trace("onError {}", webSocket, t);
    }
}
