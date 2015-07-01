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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;

import java.io.InputStream;
import java.io.Reader;
import java.util.List;

/**
 * A streaming API for WebServer that support WebSocket streaming. When a {@link WebSocketProtocol} implements this interface,
 * bytes/text will ve streamed instead of read in memory.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketProtocolStream extends WebSocketProtocol {

    /**
     * Parse the WebSocket stream, and delegate the processing to the {@link org.atmosphere.cpr.AtmosphereFramework#asyncSupport} or
     * to any existing technology. Invoking  {@link org.atmosphere.cpr.AtmosphereFramework#asyncSupport} will delegate the request processing
     * to the {@link org.atmosphere.cpr.AtmosphereHandler} implementation. Returning null means this implementation will
     * handle itself the processing/dispatching of the WebSocket's request;
     * <br>
     * As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     * <br>
     *
     * @param webSocket The {@link org.atmosphere.websocket.WebSocket} connection
     * @param r         a {@link java.io.Reader}
     * @return a List of {@link AtmosphereRequest}
     */
    List<AtmosphereRequest> onTextStream(WebSocket webSocket, Reader r);

    /**
     * Parse the WebSocket stream, and delegate the processing to the {@link org.atmosphere.cpr.AtmosphereFramework#asyncSupport} or
     * to any existing technology. Invoking  {@link org.atmosphere.cpr.AtmosphereFramework#asyncSupport} will delegate the request processing
     * to the {@link org.atmosphere.cpr.AtmosphereHandler} implementation. Returning null means this implementation will
     * handle itself the processing/dispatching of the WebSocket's request;
     * <br>
     * As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     * <br>
     *
     * @param webSocket The {@link WebSocket} connection
     * @param stream    a {@link Reader}
     * @return a List of {@link AtmosphereRequest}
     */
    List<AtmosphereRequest> onBinaryStream(WebSocket webSocket, InputStream stream);
}
