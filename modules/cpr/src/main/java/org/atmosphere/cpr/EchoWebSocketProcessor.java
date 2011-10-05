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
package org.atmosphere.cpr;

import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple {@link WebSocketProcessor} that invoke the {@link Broadcaster#broadcast} API when a WebSocket message
 * is received.
 *
 * NOTE: If WebSocket frame are used
 *
 * @author Jeanfrancois Arcand
 */
public class EchoWebSocketProcessor extends WebSocketProcessor {
    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);

    public EchoWebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocket webSocket) {
        super(atmosphereServlet, webSocket);
    }

    public void broadcast(String data) {
        logger.trace("broadcast String");
        resource().getBroadcaster().broadcast(data);
    }

    public void broadcast(byte[] data, int offset, int length) {
        logger.trace("broadcast byte");
        byte[] b = new byte[length];
        System.arraycopy(data, offset, b, 0, length);
        resource().getBroadcaster().broadcast(b);
    }
}
