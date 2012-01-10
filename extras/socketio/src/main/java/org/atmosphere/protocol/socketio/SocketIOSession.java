package org.atmosphere.protocol.socketio;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;

public interface SocketIOSession {
	String generateRandomString(int length);
	
	String getSessionId();

	ConnectionState getConnectionState();
	
	SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> getSocketIOAtmosphereHandler();
	
	AtmosphereResourceImpl getAtmosphereResourceImpl();
	
	void setAtmosphereResourceImpl(AtmosphereResourceImpl resource);

	SocketIOSessionOutbound getTransportHandler();
	
	void setHeartbeat(long delay);
	long getHeartbeat();
	
	void sendHeartBeat();
	
	void setTimeout(long timeout);
	long getTimeout();
	
	void timeout();

	void startTimeoutTimer();
	void clearTimeoutTimer();

	void startHeartbeatTimer();
	void clearHeartbeatTimer();
	
	void setRequestSuspendTime(long suspendTime);
	long getRequestSuspendTime();

	/**
	 * Initiate close.
	 */
	void startClose();

	void onClose(String data);

	/**
	 * @param handler The handler or null if the connection failed.
	 */
	void onConnect(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler);
	
	/**
	 * Pass message through to contained SocketIOInbound
	 * If a timeout timer is set, then it will be reset.
	 * @param message
	 */
	void onMessage(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler, String message);
	
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
