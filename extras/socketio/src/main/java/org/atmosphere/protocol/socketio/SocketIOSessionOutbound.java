package org.atmosphere.protocol.socketio;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;



public interface SocketIOSessionOutbound extends SocketIOOutbound {
	
	void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException;
	/**
	 * Cause connection and all activity to be aborted and all resources to be released.
	 * The handler is expected to call the session's onShutdown() when it is finished.
	 * The only session method that the handler can legally call after this is onShutdown();
	 */
	void abort();
}
