/*
 * Copyright 2012 Sebastien Dionne
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.socketio.transport;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.socketio.HeartBeatSessionMonitor;
import org.atmosphere.socketio.SocketIOException;
import org.atmosphere.socketio.SocketIOSession;
import org.atmosphere.socketio.SocketIOSessionFactory;
import org.atmosphere.socketio.SocketIOSessionManager;
import org.atmosphere.socketio.SocketIOSessionOutbound;
import org.atmosphere.socketio.TimeoutSessionMonitor;
import org.atmosphere.socketio.cpr.SocketIOAtmosphereHandler;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public class SocketIOSessionManagerImpl implements SocketIOSessionManager, SocketIOSessionFactory {

    private static final Logger logger = LoggerFactory.getLogger(SocketIOSessionManagerImpl.class);

    private static final int SESSION_ID_LENGTH = 20;
    private static Random random = new SecureRandom();
    private ConcurrentMap<String, SocketIOSession> socketIOSessions = new ConcurrentHashMap<String, SocketIOSession>();
    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    private long heartbeatInterval = 15;
    private long timeout = 2500;
    private long requestSuspendTime = 20000; // 20 sec.
    public static final ObjectMapper mapper = new ObjectMapper();

    private static String generateRandomString(int length) {

        byte[] bytes = new byte[16];

        // Render the result as a String of hexadecimal digits
        StringBuilder buffer = new StringBuilder(length);

        int resultLenBytes = 0;

        while (resultLenBytes < length) {
            random.nextBytes(bytes);
            for (int j = 0;
                 j < bytes.length && resultLenBytes < length;
                 j++) {
                byte b1 = (byte) ((bytes[j] & 0xf0) >> 4);
                byte b2 = (byte) (bytes[j] & 0x0f);
                if (b1 < 10)
                    buffer.append((char) ('0' + b1));
                else
                    buffer.append((char) ('A' + (b1 - 10)));
                if (b2 < 10)
                    buffer.append((char) ('0' + b2));
                else
                    buffer.append((char) ('A' + (b2 - 10)));
                resultLenBytes++;
            }
        }

        return buffer.toString();


    }

    private String generateSessionId() {
        return generateRandomString(SESSION_ID_LENGTH);
    }

    @Override
    public SocketIOSession createSession(AtmosphereResourceImpl resource, AtmosphereHandler inbound) {
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
        private AtmosphereHandler atmosphereHandler;
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
        private AtomicBoolean firstRequest = new AtomicBoolean(true);

        SessionImpl(String sessionId, AtmosphereResourceImpl resource, AtmosphereHandler atmosphereHandler, long timeout, long heartBeatInterval, long requestSuspendTime) {
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
        public AtmosphereHandler getAtmosphereHandler() {
            return atmosphereHandler;
        }

        @Override
        public SocketIOSessionOutbound getTransportHandler() {
            return handler;
        }

        @Override
        public void startTimeoutTimer() {
            logger.trace("startTimeoutTimer for SessionID= " + sessionId);
            clearTimeoutTimer();
            if (!timedout && timeout > 0) {
                timeoutSessionMonitor.start();
            }
        }

        @Override
        public void clearTimeoutTimer() {
            logger.trace("clearTimeoutTimer for SessionID= " + sessionId);
            if (timeoutSessionMonitor != null) {
                timeoutSessionMonitor.cancel();
            }
        }

        @Override
        public void sendHeartBeat() {
            String data = "" + messageId.incrementAndGet();
            logger.trace("Session[" + sessionId + "]: sendPing " + data);
            try {
                handler.sendMessage("2::");
            } catch (Exception e) {
                logger.debug("handler.sendMessage failed: ", e);
                handler.abort();
            }
            logger.trace("calling from " + this.getClass().getName() + " : " + "sendPing");
            startTimeoutTimer();
        }

        @Override
        public void timeout() {
            logger.trace("Session[" + sessionId + "]: onTimeout");
            if (!timedout) {
                timedout = true;
                state = ConnectionState.CLOSED;
                onDisconnect(DisconnectReason.TIMEOUT);
                handler.abort();
            }
        }

        @Override
        public void startHeartbeatTimer() {
            logger.trace("startHeartbeatTimer");
            clearHeartbeatTimer();
            clearTimeoutTimer();
            if (!timedout && heartBeatInterval > 0) {
                heartBeatSessionMonitor.start();
            }
        }

        @Override
        public void clearHeartbeatTimer() {
            logger.trace("clearHeartbeatTimer : Clear previous Timer");
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

                    if (atmosphereHandler == null) {
                        // Something went wrong
                        logger.debug("Invalid state");
                        return;
                    }

                    // for the Broadcaster
                    resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, sessionId);
                    resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_OUTBOUND, handler);

                    startHeartbeatTimer();
                    synchronized (atmosphereHandler) {
                        if (SocketIOAtmosphereHandler.class.isAssignableFrom(atmosphereHandler.getClass())) {
                            SocketIOAtmosphereHandler.class.cast(atmosphereHandler).onConnect(resource, handler);
                        } else {
                            resource.disableSuspend(true);
                            atmosphereHandler.onRequest(resource);
                        }
                    }
                } catch (Throwable e) {
                    logger.error("Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onConnect()", e);
                    state = ConnectionState.CLOSED;
                    handler.abort();
                }
            } else {
                handler.abort();
            }
        }

        @Override
        public void onMessage(AtmosphereResourceImpl resource, SocketIOSessionOutbound outbound, String message) {
            startHeartbeatTimer();

            if (atmosphereHandler != null && message != null) {
                try {
                    synchronized (atmosphereHandler) {
                        if (SocketIOAtmosphereHandler.class.isAssignableFrom(atmosphereHandler.getClass())) {
                            SocketIOAtmosphereHandler.class.cast(atmosphereHandler).onMessage(resource, outbound, message);
                        } else {
                            SocketIOProtocol p = mapper.readValue(message, SocketIOProtocol.class);

                            for (String msg : p.getArgs()) {
                                AtmosphereRequest r = resource.getRequest();
                                r.setAttribute(SocketIOProtocol.class.getName(), p);
                                r.body(msg).method("POST");
                                resource.disableSuspend(true);
                                atmosphereHandler.onRequest(resource);
                            }
                        }
                    }
                } catch (Throwable e) {
                    logger.error("Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onMessage()", e);
                }
            }
        }

        @Override
        public void onDisconnect(DisconnectReason reason) {
            logger.trace("Session[" + sessionId + "]: onDisconnect: " + reason);
            clearTimeoutTimer();
            clearHeartbeatTimer();
            if (atmosphereHandler != null) {
                state = ConnectionState.CLOSED;
                try {
                    synchronized (atmosphereHandler) {
                        if (SocketIOAtmosphereHandler.class.isAssignableFrom(atmosphereHandler.getClass())) {
                            SocketIOAtmosphereHandler.class.cast(atmosphereHandler).onDisconnect(resource, handler, reason);
                        } else {
                            atmosphereHandler.onStateChange(new AtmosphereResourceEventImpl(resource, true, false));
                        }
                    }
                } catch (Throwable e) {
                    logger.error("Session[" + sessionId + "]: Exception thrown by SocketIOInbound.onDisconnect()", e);
                }
                atmosphereHandler = null;
            }
        }

        @Override
        public void onShutdown() {
            logger.trace("Session[" + sessionId + "]: onShutdown");
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

    /**
     * Connection state based on Jetty for the connection's state
     *
     * @author Sebastien Dionne  : sebastien.dionne@gmail.com
     */
    public enum ConnectionState {
        UNKNOWN(-1),
        CONNECTING(0),
        CONNECTED(1),
        CLOSING(2),
        CLOSED(3);

        private int value;

        private ConnectionState(int v) {
            this.value = v;
        }

        public int value() {
            return value;
        }

        public static ConnectionState fromInt(int val) {
            switch (val) {
                case 0:
                    return CONNECTING;
                case 1:
                    return CONNECTED;
                case 2:
                    return CLOSING;
                case 3:
                    return CLOSED;
                default:
                    return UNKNOWN;
            }
        }
    }

    public static final class SocketIOProtocol {
        public String name;
        public Collection<String> args;

        public Collection<String> getArgs() {
            return args;
        }

        public void setArgs(Collection<String> args) {
            this.args = args;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public SocketIOProtocol addArgs(String s) {
            if (args == null) {
                args = new LinkedList<String>();
            }
            args.add(s);
            return this;
        }

        public SocketIOProtocol clearArgs(){
            args.clear();
            return this;
        }

        @Override
        public String toString() {
            return "SocketIOProtocol [name=" + name + ", args=" + args + "]";
        }
    }
}
