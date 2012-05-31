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
package org.atmosphere.socketio.cpr;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.socketio.SocketIOSessionOutbound;
import org.atmosphere.socketio.transport.DisconnectReason;
import org.atmosphere.socketio.transport.SocketIOPacketImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public abstract class SocketIOAtmosphereHandler implements AtmosphereHandler {

    private final Logger logger = LoggerFactory.getLogger(SocketIOAtmosphereHandler.class);
    public static final String SOCKETIO_SESSION_OUTBOUND = "SocketIOSessionOutbound";
    public static final String SOCKETIO_SESSION_ID = SocketIOAtmosphereHandler.class.getPackage().getName() + ".sessionid";

    /**
     * Called when the connection is established.
     *
     * @param handler The SocketOutbound associated with the connection
     */
    abstract public void onConnect(AtmosphereResource event, SocketIOSessionOutbound handler) throws IOException;

    /**
     * Called when the socket connection is disconnected.
     *
     * @param event   AtmosphereResource
     * @param handler outbound handler to broadcast response
     * @param reason  The reason for the disconnect.
     */
    abstract public void onDisconnect(AtmosphereResource event, SocketIOSessionOutbound handler, DisconnectReason reason);

    /**
     * Called for each message received.
     *
     * @param event   AtmosphereResource
     * @param handler outbound handler to broadcast response
     * @param message message received
     */
    abstract public void onMessage(AtmosphereResource event, SocketIOSessionOutbound handler, String message);

    /**
     * {@inheritDoc}
     */
    public final void onRequest(AtmosphereResource event) throws IOException {
        logger.trace("onRequest");
    }

    /**
     * {@inheritDoc}
     */
    public final void onStateChange(AtmosphereResourceEvent event) throws IOException {

        if (event.isResuming() || event.isResumedOnTimeout()) {
            return;
        }

        AtmosphereRequest request = event.getResource().getRequest();
        logger.trace("onStateChange on SessionID=" + request.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID) + "  Method=" + request.getMethod());
        SocketIOSessionOutbound outbound = (org.atmosphere.socketio.SocketIOSessionOutbound) request.getAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_OUTBOUND);

        if (outbound != null && event.getMessage() != null) {
            try {

                if (event.getMessage().getClass().isArray()) {
                    List<Object> list = Arrays.asList(event.getMessage());

                    for (Object object : list) {
                        List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(object.toString());
                        outbound.sendMessage(messages);
                    }

                } else if (event.getMessage() instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = List.class.cast(event.getMessage());

                    for (Object object : list) {
                        List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(object.toString());
                        outbound.sendMessage(messages);
                    }
                } else if (event.getMessage() instanceof String) {

                    logger.trace("onStateChange Sending message on resume : message = " + event.getMessage().toString());

                    List<SocketIOPacketImpl> messages = SocketIOPacketImpl.parse(event.getMessage().toString());
                    outbound.sendMessage(messages);
                }

            } catch (Exception e) {
                logger.warn("", e);
                outbound.disconnect();
            }
        }
    }

    public void destroy() {
        logger.trace("destroy");
    }
}
