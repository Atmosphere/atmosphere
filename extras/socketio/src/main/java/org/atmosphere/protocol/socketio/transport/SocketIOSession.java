/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.atmosphere.protocol.socketio.transport;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOFrame;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOOutbound;

public interface SocketIOSession {
	interface Factory {
		SocketIOSession createSession(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> inbound);
		SocketIOSession getSession(String sessionId);
	}

	interface SessionTransportHandler extends SocketIOOutbound {
		void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException;
		void sendMessage(SocketIOFrame message) throws SocketIOException;
		void disconnectWhenEmpty();
		/**
		 * Cause connection and all activity to be aborted and all resources to be released.
		 * The handler is expected to call the session's onShutdown() when it is finished.
		 * The only session method that the handler can legally call after this is onShutdown();
		 */
		void abort();
	}

	interface SessionTask {
		/**
		 * @return True if task was or was already canceled, false if the task is executing or has executed.
		 */
		boolean cancel();
	}
	
	String generateRandomString(int length);
	
	String getSessionId();

	ConnectionState getConnectionState();
	
	SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> getInbound();
	
	AtmosphereResourceImpl getAtmosphereResourceImpl();
	
	void setAtmosphereResourceImpl(AtmosphereResourceImpl resource);

	SessionTransportHandler getTransportHandler();
	
	void setHeartbeat(long delay);
	long getHeartbeat();
	void setTimeout(long timeout);
	long getTimeout();

	void startTimeoutTimer();
	void clearTimeoutTimer();

	void startHeartbeatTimer();
	void clearHeartbeatTimer();

	/**
	 * Initiate close.
	 */
	void startClose();

	void onMessage(AtmosphereResourceImpl resource, SessionTransportHandler handler, SocketIOFrame message);
	void onPing(String data);
	void onPong(String data);
	void onClose(String data);

	/**
	 * Schedule a task (e.g. timeout timer)
	 * @param task The task to execute after specified delay.
	 * @param delay Delay in milliseconds.
	 * @return
	 */
	SessionTask scheduleTask(Runnable task, long delay);
	
	/**
	 * @param handler The handler or null if the connection failed.
	 */
	void onConnect(AtmosphereResourceImpl resource, SessionTransportHandler handler);
	
	/**
	 * Pass message through to contained SocketIOInbound
	 * If a timeout timer is set, then it will be reset.
	 * @param message
	 */
	void onMessage(AtmosphereResourceImpl resource, SessionTransportHandler handler, String message);
	
	/**
	 * Pass disconnect through to contained SocketIOInbound and update any internal state.
	 * @param reason
	 */
	void onDisconnect(DisconnectReason reason);

	/**
	 * Called by handler to report that it is done and the session can be cleaned up.
	 * If onDisconnect has not been called yet, then it will be called with DisconnectReason.ERROR.
	 */
	void onShutdown();
}
