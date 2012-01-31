package org.atmosphere.protocol.socketio;

import com.ning.http.client.websocket.WebSocket;

public class WebSocketWrapper {
	
	WebSocket websocket;
	WebSocketResponseListener listener;
	
	public WebSocket getWebsocket() {
		return websocket;
	}
	public void setWebsocket(WebSocket websocket) {
		this.websocket = websocket;
	}
	public WebSocketResponseListener getListener() {
		return listener;
	}
	public void setListener(WebSocketResponseListener listener) {
		this.listener = listener;
	} 
	
	
}
