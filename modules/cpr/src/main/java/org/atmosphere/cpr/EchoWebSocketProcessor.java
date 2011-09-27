package org.atmosphere.cpr;

import org.atmosphere.websocket.WebSocketSupport;


public class EchoWebSocketProcessor extends WebSocketProcessor {

    public EchoWebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocketSupport webSocketSupport) {
        super(atmosphereServlet, webSocketSupport);
    }

    public void broadcast(String data) {
        resource().getBroadcaster().broadcast(data);
    }

    public void broadcast(byte[] data, int offset, int length) {
        byte[] b = new byte[length];
        System.arraycopy(data, offset, b, 0, length);
        resource().getBroadcaster().broadcast(b);
    }
}
