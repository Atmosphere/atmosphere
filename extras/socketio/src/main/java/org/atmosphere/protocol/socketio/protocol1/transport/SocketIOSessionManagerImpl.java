package org.atmosphere.protocol.socketio.protocol1.transport;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.HeartBeatSessionMonitor;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionFactory;
import org.atmosphere.protocol.socketio.SocketIOSessionManager;
import org.atmosphere.protocol.socketio.SocketIOSessionOutbound;
import org.atmosphere.protocol.socketio.TimeoutSessionMonitor;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketIOSessionManagerImpl implements SocketIOSessionManager, SocketIOSessionFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOSessionManagerImpl.class);
	
	private static final char[] BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
	private static final int SESSION_ID_LENGTH = 20;

	private static Random random = new SecureRandom();
	private ConcurrentMap<String, SocketIOSession> socketIOSessions = new ConcurrentHashMap<String, SocketIOSession>();
	private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	
	private long heartbeatInterval = 15;
	private long timeout = 2500;
	private long requestSuspendTime = 20000; // 20 sec.

	private static String generateRandomString(int length) {
	    StringBuilder result = new StringBuilder(length);
	    byte[] bytes = new byte[length];
	    random.nextBytes(bytes);
	    for (int i = 0; i < bytes.length; i++) {
	      result.append(BASE64_ALPHABET[bytes[i] & 0x3F]);
	    }
	    return result.toString();
	}
	
	private String generateSessionId() {
		return generateRandomString(SESSION_ID_LENGTH);
	}

	@Override
	public SocketIOSession createSession(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> inbound) {
		SessionImpl impl = new SessionImpl(generateSessionId(), resource, inbound, getTimeout(), getHeartbeatInterval(), getRequestSuspendTime());
		socketIOSessions.put(impl.getSessionId(), impl);
		return impl;
	}

	@Override
	public SocketIOSession getSession(String sessionId) {
		return socketIOSessions.get(sessionId);
	}

	@Override
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	@Override
	public long getTimeout() {
		return timeout;
	}

	@Override
	public void setHeartbeatInterval(long interval) {
		this.heartbeatInterval = interval;
	}

	@Override
	public long getHeartbeatInterval() {
		return heartbeatInterval;
	}
	
	@Override
	public void setRequestSuspendTime(long suspendTime) {
		this.requestSuspendTime = suspendTime;
	}

	@Override
	public long getRequestSuspendTime() {
		return requestSuspendTime;
	}

	private class SessionImpl implements SocketIOSession {
		private final String sessionId;
		
		private AtmosphereResourceImpl resource = null;
		private SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler;
		private SocketIOSessionOutbound handler = null;
		private ConnectionState state = ConnectionState.CONNECTING;
		private long heartBeatInterval = 0;
		private long timeout = 0;
		private long requestSuspendTime = 0;
		private HeartBeatSessionMonitor heartBeatSessionMonitor = new HeartBeatSessionMonitor(this, executor);
		private TimeoutSessionMonitor timeoutSessionMonitor = new TimeoutSessionMonitor(this, executor);
		private boolean timedout = false;
		private AtomicLong messageId = new AtomicLong(0);
		private String closeId = null;

		SessionImpl(String sessionId, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, long timeout, long heartBeatInterval, long requestSuspendTime) {
			this.sessionId = sessionId;
			this.atmosphereHandler = atmosphereHandler;
			this.resource = resource;
			this.timeout = timeout;
			this.heartBeatInterval = heartBeatInterval;
			this.requestSuspendTime = requestSuspendTime;
			
			heartBeatSessionMonitor.setDelay(heartBeatInterval);
			timeoutSessionMonitor.setDelay(timeout);
		}

		@Override
		public String generateRandomString(int length) {
			return SocketIOSessionManagerImpl.generateRandomString(length);
		}
		
		@Override
		public String getSessionId() {
			return sessionId;
		}

		@Override
		public ConnectionState getConnectionState() {
			return state;
		}

		@Override
		public SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> getSocketIOAtmosphereHandler() {
			return atmosphereHandler;
		}

		@Override
		public SocketIOSessionOutbound getTransportHandler() {
			return handler;
		}

		@Override
		public void startTimeoutTimer() {
			logger.error("startTimeoutTimer");
			clearTimeoutTimer();
			if (!timedout && timeout > 0) {
				timeoutSessionMonitor.start();
			}
		}

		@Override
		public void clearTimeoutTimer() {
			logger.error("clearTimeoutTimer");
			if (timeoutSessionMonitor != null) {
				timeoutSessionMonitor.cancel();
			}
		}
		
		@Override
		public void sendHeartBeat() {
			String data = "" + messageId.incrementAndGet();
			logger.error("Session["+sessionId+"]: sendPing " + data);
			try {
				handler.sendMessage("2::");
			} catch (Exception e) {
				logger.error("handler.sendMessage failed: ", e);
				handler.abort();
			} 
			logger.error("calling from " + this.getClass().getName() + " : " + "sendPing");
			startTimeoutTimer();
		}

		@Override
		public void timeout() {
			logger.error("Session["+sessionId+"]: onTimeout");
			if (!timedout) {
				timedout = true;
				state = ConnectionState.CLOSED;
				onDisconnect(DisconnectReason.TIMEOUT);
				handler.abort();
			}
		}
		
		@Override
		public void startHeartbeatTimer() {
			logger.error("startHeartbeatTimer");
			clearHeartbeatTimer();
			clearTimeoutTimer();
			if (!timedout && heartBeatInterval > 0) {
				heartBeatSessionMonitor.start();
			}
		}

		@Override
		public void clearHeartbeatTimer() {
			logger.error("clearHeartbeatTimer : Clear previous Timer");
			if (heartBeatSessionMonitor != null) {
				heartBeatSessionMonitor.cancel();
			}
		}

		@Override
		public void setHeartbeat(long delay) {
			heartBeatInterval = delay;
			heartBeatSessionMonitor.setDelay(delay);
		}

		@Override
		public long getHeartbeat() {
			return heartBeatInterval;
		}
		
		@Override
		public void setTimeout(long timeout) {
			this.timeout = timeout;
			timeoutSessionMonitor.setDelay(timeout);
		}

		@Override
		public long getTimeout() {
			return timeout;
		}
		
		@Override
		public void setRequestSuspendTime(long suspendTime) {
			this.requestSuspendTime = suspendTime;
		}

		@Override
		public long getRequestSuspendTime() {
			return requestSuspendTime;
		}

		@Override
		public void startClose() {
			logger.error("startClose");
			state = ConnectionState.CLOSING;
			closeId = "server";
		}
		
		@Override
		public void onClose(String data) {
			if (state == ConnectionState.CLOSING) {
				if (closeId != null && closeId.equals(data)) {
					state = ConnectionState.CLOSED;
					onDisconnect(DisconnectReason.CLOSED);
					handler.abort();
				} else {
					try {
						handler.sendMessage(data);
					} catch (SocketIOException e) {
						logger.error("handler.sendMessage failed: ", e);
						handler.abort();
					}
				}
			} else {
				clearTimeoutTimer();
				clearHeartbeatTimer();
				state = ConnectionState.CLOSING;
				try {
					handler.sendMessage(data);
				} catch (SocketIOException e) {
					logger.error("handler.sendMessage failed: ", e);
					handler.abort();
				}
			}
		}

		@Override
		public void onConnect(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler) {
			if (handler == null) {
				state = ConnectionState.CLOSED;
				atmosphereHandler = null;
				socketIOSessions.remove(sessionId);
			} else if (this.handler == null) {
				this.handler = handler;
				try {
					state = ConnectionState.CONNECTED;
					
					// pour le broadcast
					resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, sessionId);
					resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, handler);
					
					startHeartbeatTimer();
					
					atmosphereHandler.onConnect(resource, handler);
				} catch (Throwable e) {
					logger.error("Session["+sessionId+"]: Exception thrown by SocketIOInbound.onConnect()", e);
					state = ConnectionState.CLOSED;
					handler.abort();
				}
			} else {
				handler.abort();
			}
		}

		@Override
		public void onMessage(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler, String message) {
			startHeartbeatTimer();
			
			if (atmosphereHandler != null && message!=null) {
				try {
					atmosphereHandler.onMessage(resource, handler, message);
				} catch (Throwable e) {
					logger.error("Session["+sessionId+"]: Exception thrown by SocketIOInbound.onMessage()", e);
				}
			}
		}

		@Override
		public void onDisconnect(DisconnectReason reason) {
			logger.error("Session["+sessionId+"]: onDisconnect: " + reason);
			clearTimeoutTimer();
			clearHeartbeatTimer();
			if (atmosphereHandler != null) {
				state = ConnectionState.CLOSED;
				try {
					atmosphereHandler.onDisconnect(resource, handler, reason);
				} catch (Throwable e) {
					logger.error("Session["+sessionId+"]: Exception thrown by SocketIOInbound.onDisconnect()", e);
				}
				atmosphereHandler = null;
			}
		}
		
		@Override
		public void onShutdown() {
			logger.error("Session["+sessionId+"]: onShutdown");
			if (atmosphereHandler != null) {
				if (state == ConnectionState.CLOSING) {
					if (closeId != null) {
						onDisconnect(DisconnectReason.CLOSE_FAILED);
					} else {
						onDisconnect(DisconnectReason.CLOSED_REMOTELY);
					}
				} else {
					onDisconnect(DisconnectReason.ERROR);
				}
			}
			socketIOSessions.remove(sessionId);
		}

		@Override
		public AtmosphereResourceImpl getAtmosphereResourceImpl() {
			return resource;
		}
		
		@Override
		public void setAtmosphereResourceImpl(AtmosphereResourceImpl resource) {
			this.resource = resource;
		}

	}

}
