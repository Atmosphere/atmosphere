package org.atmosphere.protocol.socketio;

import org.atmosphere.websocket.WebSocket;

public interface SocketIOWebSocketSessionWrapper extends SocketIOSessionOutbound {
	
	SocketIOSession getSession();
	
	void onDisconnect();
	
	void onMessage(byte frame, String message);
	
	void onMessage(byte frame, byte[] data, int offset, int length);
	
	boolean initiated();
	
	WebSocket webSocket();
	 
	void setWebSocket(WebSocket websocket);

	void initiated(boolean initialed);
}
