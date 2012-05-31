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
import org.atmosphere.socketio.transport.JSONPPollingTransport;
import org.atmosphere.socketio.transport.SocketIOSessionManagerImpl;
import org.atmosphere.socketio.transport.Transport;
import org.atmosphere.socketio.transport.WebSocketTransport;
import org.atmosphere.socketio.transport.XHRPollingTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * SocketIO implementation.
 *
 * @author Sebastien Dionne
 */
@AtmosphereInterceptorService
public class SocketIOAtmosphereInterceptor implements AtmosphereInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SocketIOAtmosphereInterceptor.class);

    public static final int BUFFER_SIZE_DEFAULT = 8192;

    private static SocketIOSessionManager sessionManager1 = null;
    private static Map<String, Transport> transports = new HashMap<String, Transport>();

    public static final String BUFFER_SIZE_INIT_PARAM = "socketio-bufferSize";
    public static final String SOCKETIO_TRANSPORT = "socketio-transport";
    public static final String SOCKETIO_TIMEOUT = "socketio-timeout";
    public static final String SOCKETIO_HEARTBEAT = "socketio-heartbeat";
    public static final String SOCKETIO_SUSPEND = "socketio-suspendTime";

    public int bufferSize = BUFFER_SIZE_DEFAULT;

    private static int heartbeatInterval = 15000;
    private static int timeout = 25000;
    private static int suspendTime = 20000;

    private String availableTransports = "websocket,xhr-polling,jsonp-polling";

    private SocketIOSessionManager getSessionManager(String version) {

        if (version.equals("1")) {
            return sessionManager1;
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
        final AtmosphereResourceImpl resource = (AtmosphereResourceImpl) request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

        if (atmosphereHandler instanceof SocketIOAtmosphereHandler) {

            try {
                // find the transport
                String path = request.getPathInfo();
                if (path == null || path.length() == 0 || "/".equals(path)) {
                    response.sendError(AtmosphereResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
                    return null;
                }

                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                String[] parts = path.split("/");

                String protocol = null;
                String version = null;

                // find protocol's version
                if (parts.length == 0) {
                    return null;
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
                    return null;
                } else if (protocol == null && version != null) {
                    // create a session and send the available transports to the client
                    response.setStatus(200);

                    SocketIOSession session = getSessionManager(version).createSession(resource, (SocketIOAtmosphereHandler) atmosphereHandler);
                    response.getWriter().print(session.getSessionId() + ":" + heartbeatInterval + ":" + timeout + ":" + availableTransports);

                    return Action.CANCELLED;
                } else if (protocol != null && version == null) {
                    version = "0";
                }

                Transport transport = transports.get(protocol + "-" + version);

                if (transport != null) {
                    return transport.handle(resource, (SocketIOAtmosphereHandler) atmosphereHandler, getSessionManager(version));
                } else {
                    logger.error("Protocol not supported : " + protocol);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }


        return Action.CANCELLED;
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

        sessionManager1 = new SocketIOSessionManagerImpl();
        sessionManager1.setTimeout(timeout);
        sessionManager1.setHeartbeatInterval(heartbeatInterval);
        sessionManager1.setRequestSuspendTime(suspendTime);

    }

}
