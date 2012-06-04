/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.socketio.cpr;

import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.socketio.SocketIOSession;
import org.atmosphere.socketio.SocketIOSessionManager;
import org.atmosphere.socketio.SocketIOSessionOutbound;
import org.atmosphere.socketio.transport.JSONPPollingTransport;
import org.atmosphere.socketio.transport.SocketIOPacketImpl;
import org.atmosphere.socketio.transport.SocketIOSessionManagerImpl;
import org.atmosphere.socketio.transport.Transport;
import org.atmosphere.socketio.transport.WebSocketTransport;
import org.atmosphere.socketio.transport.XHRPollingTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.atmosphere.socketio.transport.SocketIOSessionManagerImpl.mapper;

/**
 * SocketIO implementation.
 *
 * @author Sebastien Dionne
 * @@author Jeanfrancois Arcand
 */
@AtmosphereInterceptorService
public class SocketIOAtmosphereInterceptor implements AtmosphereInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SocketIOAtmosphereInterceptor.class);
    private static final int BUFFER_SIZE_DEFAULT = 8192;
    private SocketIOSessionManager sessionManager = null;
    private static int heartbeatInterval = 15000;
    private static int timeout = 25000;
    private static int suspendTime = 20000;
    private final Map<String, Transport> transports = new HashMap<String, Transport>();
    private String availableTransports = "websocket,xhr-polling,jsonp-polling";

    public static final String BUFFER_SIZE_INIT_PARAM = "socketio-bufferSize";
    public static final String SOCKETIO_TRANSPORT = "socketio-transport";
    public static final String SOCKETIO_TIMEOUT = "socketio-timeout";
    public static final String SOCKETIO_HEARTBEAT = "socketio-heartbeat";
    public static final String SOCKETIO_SUSPEND = "socketio-suspendTime";


    private SocketIOSessionManager getSessionManager(String version) {
        if (version.equals("1")) {
            return sessionManager;
        }
        return null;
    }

    @Override
    public String toString() {
        return "SocketIO-Support";
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();

        final AtmosphereHandler atmosphereHandler = (AtmosphereHandler) request.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
        try {
            // find the transport
            String path = request.getPathInfo();
            if (path == null || path.length() == 0 || "/".equals(path)) {
                logger.debug("Not a SocketIO client");
                return Action.CONTINUE;
            }

            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            String[] parts = path.split("/");

            String protocol = null;
            String version = null;

            // find protocol's version
            if (parts.length == 0) {
                logger.debug("Not a SocketIO protocol supported");
                return Action.CONTINUE;
            } else if (parts.length == 1) {

                // is protocol's version ?
                if (parts[0].length() == 1) {
                    version = parts[0];
                    // must be a digit
                    if (!Character.isDigit(version.charAt(0))) {
                        version = null;
                    }
                } else {
                    protocol = parts[0];
                }

            } else {
                // ex  :[1, xhr-polling, 7589995670715459]
                version = parts[0];
                protocol = parts[1];

                // must be a digit
                if (!Character.isDigit(version.charAt(0))) {
                    version = null;
                    protocol = null;
                }

            }

            if (protocol == null && version == null) {
                logger.debug("Not a SocketIO protocol supported");
                return Action.CONTINUE;
            } else if (protocol == null && version != null) {
                // create a session and send the available transports to the client
                response.setStatus(200);

                SocketIOSession session = getSessionManager(version).createSession((AtmosphereResourceImpl) r, atmosphereHandler);
                response.getWriter().print(session.getSessionId() + ":" + heartbeatInterval + ":" + timeout + ":" + availableTransports);

                return Action.CANCELLED;
            } else if (protocol != null && version == null) {
                version = "0";
            }

            final Transport transport = transports.get(protocol + "-" + version);
            if (transport != null) {
                if (!SocketIOAtmosphereHandler.class.isAssignableFrom(atmosphereHandler.getClass())) {
                    response.asyncIOWriter(new AsyncIOWriterAdapter() {
                        @Override
                        public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
                            SocketIOSessionOutbound outbound = (SocketIOSessionOutbound)
                                    request.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_OUTBOUND);
                            SocketIOSessionManagerImpl.SocketIOProtocol p = (SocketIOSessionManagerImpl.SocketIOProtocol)
                                    r.request().getAttribute(SocketIOSessionManagerImpl.SocketIOProtocol.class.getName());

                            String msg = p == null ? data : mapper.writeValueAsString(p.clearArgs().addArgs(data));

                            if (outbound != null) {
                                outbound.sendMessage(new SocketIOPacketImpl(SocketIOPacketImpl.PacketType.EVENT, msg));
                            } else {
                                r.getResponse().getOutputStream().write(msg.getBytes(r.getCharacterEncoding()));
                            }
                            return this;
                        }

                        @Override
                        public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
                            SocketIOSessionManagerImpl.SocketIOProtocol p = (SocketIOSessionManagerImpl.SocketIOProtocol)
                                    r.request().getAttribute(SocketIOSessionManagerImpl.SocketIOProtocol.class.getName());
                            if (p == null) {
                                r.getResponse().getOutputStream().write(data);
                            } else {
                                write(new String(data, r.request().getCharacterEncoding()));
                            }
                            return this;
                        }

                        @Override
                        public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
                            SocketIOSessionManagerImpl.SocketIOProtocol p = (SocketIOSessionManagerImpl.SocketIOProtocol)
                                    r.request().getAttribute(SocketIOSessionManagerImpl.SocketIOProtocol.class.getName());
                            if (p == null) {
                                r.getResponse().getOutputStream().write(data, offset, length);
                            } else {
                                write(new String(data, offset, length, r.request().getCharacterEncoding()));
                            }
                            return this;
                        }

                        @Override
                        public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
                            try {
                                r.getResponse().getOutputStream().flush();
                            } catch (IllegalStateException ex) {
                                r.getResponse().getWriter().flush();
                            }
                            return this;
                        }

                        @Override
                        public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
                            ((HttpServletResponse) r.getResponse()).sendError(errorCode, message);
                            return this;
                        }

                        @Override
                        public void close(AtmosphereResponse r) throws IOException {
                            try {
                                r.getResponse().getOutputStream().close();
                            } catch (IllegalStateException ex) {
                                r.getResponse().getWriter().close();
                            }
                        }
                    });
                }
                transport.handle((AtmosphereResourceImpl) r, atmosphereHandler, getSessionManager(version));
            } else {
                logger.error("Protocol not supported : " + protocol);
            }
        } catch (Exception e) {
            logger.error("", e);
        }
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(SOCKETIO_TRANSPORT);
        availableTransports = s;

        String timeoutWebXML = config.getInitParameter(SOCKETIO_TIMEOUT);
        if (timeoutWebXML != null) {
            timeout = Integer.parseInt(timeoutWebXML);
        }

        String heartbeatWebXML = config.getInitParameter(SOCKETIO_HEARTBEAT);
        if (heartbeatWebXML != null) {
            heartbeatInterval = Integer.parseInt(heartbeatWebXML);
        }

        String suspendWebXML = config.getInitParameter(SOCKETIO_SUSPEND);
        if (suspendWebXML != null) {
            suspendTime = Integer.parseInt(suspendWebXML);
        }

        // VERSION 1
        WebSocketTransport websocketTransport1 = new WebSocketTransport();
        XHRPollingTransport xhrPollingTransport1 = new XHRPollingTransport(BUFFER_SIZE_DEFAULT);
        JSONPPollingTransport jsonpPollingTransport1 = new JSONPPollingTransport(BUFFER_SIZE_DEFAULT);
        transports.put(websocketTransport1.getName() + "-1", websocketTransport1);
        transports.put(xhrPollingTransport1.getName() + "-1", xhrPollingTransport1);
        transports.put(jsonpPollingTransport1.getName() + "-1", jsonpPollingTransport1);

        sessionManager = new SocketIOSessionManagerImpl();
        sessionManager.setTimeout(timeout);
        sessionManager.setHeartbeatInterval(heartbeatInterval);
        sessionManager.setRequestSuspendTime(suspendTime);

    }
}
