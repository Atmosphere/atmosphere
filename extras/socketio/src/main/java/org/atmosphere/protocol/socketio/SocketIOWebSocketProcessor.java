package org.atmosphere.protocol.socketio;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sebastien Dionne
 */
public class SocketIOWebSocketProcessor extends WebSocketProcessor {
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
