package org.atmosphere.protocol.socketio;


public interface SocketIOSessionManager extends SocketIOSessionFactory {
	
	void setTimeout(long timeout);
	long getTimeout();
	
	void setHeartbeatInterval(long interval);
	long getHeartbeatInterval();
	
	void setRequestSuspendTime(long suspendTime);
	long getRequestSuspendTime();
}
