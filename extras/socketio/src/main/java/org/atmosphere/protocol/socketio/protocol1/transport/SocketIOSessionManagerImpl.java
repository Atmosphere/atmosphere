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
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOSessionManager;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketIOSessionManagerImpl implements SocketIOSessionManager, SocketIOSession.Factory {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOSessionManagerImpl.class);
	
	private static final char[] BASE64_ALPHABET =
	      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	      .toCharArray();
	private static final int SESSION_ID_LENGTH = 20;

	private static Random random = new SecureRandom();
	private ConcurrentMap<String, SocketIOSession> socketIOSessions = new ConcurrentHashMap<String, SocketIOSession>();
	private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

	private static String generateRandomString(int length) {
	    StringBuilder result = new StringBuilder(length);
	    byte[] bytes = new byte[length];
	    random.nextBytes(bytes);
	    for (int i = 0; i < bytes.length; i++) {
	      result.append(BASE64_ALPHABET[bytes[i] & 0x3F]);
	    }
	    return result.toString();
	}

	private class SessionImpl implements SocketIOSession {
		private final String sessionId;
		
		private AtmosphereResourceImpl resource = null;
		private SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler;
		private SessionTransportHandler handler = null;
		private ConnectionState state = ConnectionState.CONNECTING;
		private long hbDelay = 0;
		private SessionTask hbDelayTask = null;
		private long timeout = 0;
		private SessionTask timeoutTask = null;
		private boolean timedout = false;
		private AtomicLong messageId = new AtomicLong(0);
		private String closeId = null;

		SessionImpl(String sessionId, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler) {
			this.sessionId = sessionId;
			this.atmosphereHandler = atmosphereHandler;
			this.resource = resource;
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
		public SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> getInbound() {
			return atmosphereHandler;
		}

		@Override
		public SessionTransportHandler getTransportHandler() {
			return handler;
		}

		private void onTimeout() {
			logger.error("Session["+sessionId+"]: onTimeout");
			if (!timedout) {
				timedout = true;
				state = ConnectionState.CLOSED;
				onDisconnect(DisconnectReason.TIMEOUT);
				handler.abort();
			}
		}
		
		@Override
		public void startTimeoutTimer() {
			logger.error("startTimeoutTimer");
			clearTimeoutTimer();
			if (!timedout && timeout > 0) {
				timeoutTask = scheduleTask(new Runnable() {
					@Override
					public void run() {
						SessionImpl.this.onTimeout();
					}
				}, timeout);
			}
		}

		@Override
		public void clearTimeoutTimer() {
			logger.error("clearTimeoutTimer");
			if (timeoutTask != null) {
				timeoutTask.cancel();
				timeoutTask = null;
			}
		}
		
		private void sendPing() {
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
		public void startHeartbeatTimer() {
			logger.error("startHeartbeatTimer");
			clearHeartbeatTimer();
			clearTimeoutTimer();
			if (!timedout && hbDelay > 0) {
				hbDelayTask = scheduleTask(new Runnable() {
					@Override
					public void run() {
						sendPing();
					}
				}, hbDelay);
			}
		}

		@Override
		public void clearHeartbeatTimer() {
			logger.error("clearHeartbeatTimer");
			if (hbDelayTask != null) {
				hbDelayTask.cancel();
				hbDelayTask = null;
			}
		}

		@Override
		public void setHeartbeat(long delay) {
			hbDelay = delay;
		}

		@Override
		public long getHeartbeat() {
			return hbDelay;
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
					handler.disconnectWhenEmpty();
				} catch (SocketIOException e) {
					logger.error("handler.sendMessage failed: ", e);
					handler.abort();
				}
			}
		}

		@Override
		public SessionTask scheduleTask(Runnable task, long delay) {
			logger.error("scheduleTask");
			final Future<?> future = executor.schedule(task, delay, TimeUnit.MILLISECONDS);
			return new SessionTask() {
				@Override
				public boolean cancel() {
					return future.cancel(false);
				}
			};
		}
		
		@Override
		public void onConnect(AtmosphereResourceImpl resource, SessionTransportHandler handler) {
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
					resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SessionTransportHandler, handler);
					
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
		public void onMessage(AtmosphereResourceImpl resource, SessionTransportHandler handler, String message) {
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
	
	private String generateSessionId() {
		return generateRandomString(SESSION_ID_LENGTH);
	}

	@Override
	public SocketIOSession createSession(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> inbound) {
		SessionImpl impl = new SessionImpl(generateSessionId(), resource, inbound);
		socketIOSessions.put(impl.getSessionId(), impl);
		return impl;
	}

	@Override
	public SocketIOSession getSession(String sessionId) {
		return socketIOSessions.get(sessionId);
	}
}
