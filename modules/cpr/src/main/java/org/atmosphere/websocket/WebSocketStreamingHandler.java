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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * A Streaming Interface for WebSocket. When implemented, the {@link WebSocketProcessor} will invoke this class
 * instead of reading bytes in memory.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketStreamingHandler extends WebSocketHandler {

    /**
     * Invoked when a byte message is received.
     *
     * @param webSocket   a {@link WebSocket}
     * @param inputStream
     */
    void onBinaryStream(WebSocket webSocket, InputStream inputStream) throws IOException;

    /**
     * Invoked when a String message is received
     *
     * @param webSocket a {@link WebSocket}
     * @param reader
     */
    void onTextStream(WebSocket webSocket, Reader reader) throws IOException;

}
