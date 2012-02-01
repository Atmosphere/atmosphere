/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.protocol.socketio;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOWebSocketProcessor extends WebSocketProcessor {
	private static final long serialVersionUID = 1565529569653072539L;
	private static final Logger logger = LoggerFactory.getLogger(SocketIOWebSocketProcessor.class);

    public SocketIOWebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocket webSocket, WebSocketProtocol webSocketProtocol) {
        super(atmosphereServlet, webSocket, webSocketProtocol);
    }

    public void parseMessage(String data) {
    	logger.error("calling from " + this.getClass().getName() + " : " + "parseMessage = " + data);
        //resource().getBroadcaster().broadcast(data);
    }

    public void parseMessage(byte[] data, int offset, int length) {
    	logger.error("calling from " + this.getClass().getName() + " : " + "parseMessage byte");
        byte[] b = new byte[length];
        System.arraycopy(data, offset, b, 0, length);
        //resource().getBroadcaster().broadcast(b);
    }
}
