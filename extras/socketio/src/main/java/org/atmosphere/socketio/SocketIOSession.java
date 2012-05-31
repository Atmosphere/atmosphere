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
package org.atmosphere.socketio;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.socketio.cpr.SocketIOAtmosphereHandler;
import org.atmosphere.socketio.transport.DisconnectReason;

/**
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public interface SocketIOSession {

    /**
     * Generate the session id
     *
     * @param length session id length
     * @return
     */
    String generateRandomString(int length);

    /**
     * @return the session id for this session
     */
    String getSessionId();

    /**
     * @return
     */
    AtmosphereHandler getAtmosphereHandler();

    /**
     * @return
     */
    AtmosphereResourceImpl getAtmosphereResourceImpl();

    /**
     * @param resource
     */
    void setAtmosphereResourceImpl(AtmosphereResourceImpl resource);

    /**
     * @return
     */
    SocketIOSessionOutbound getTransportHandler();

    /**
     * @param delay
     */
    void setHeartbeat(long delay);

    /**
     * @return
     */
    long getHeartbeat();

    /**
     *
     */
    void sendHeartBeat();

    /**
     * @param timeout
     */
    void setTimeout(long timeout);

    /**
     * @return
     */
    long getTimeout();

    /**
     *
     */
    void timeout();

    /**
     *
     */
    void startTimeoutTimer();

    /**
     *
     */
    void clearTimeoutTimer();

    /**
     *
     */
    void startHeartbeatTimer();

    /**
     *
     */
    void clearHeartbeatTimer();

    /**
     * @param suspendTime
     */
    void setRequestSuspendTime(long suspendTime);

    /**
     * @return
     */
    long getRequestSuspendTime();

    /**
     * Initiate close.
     */
    void startClose();

    /**
     * @param data
     */
    void onClose(String data);

    /**
     * @param handler The handler or null if the connection failed.
     */
    void onConnect(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler);

    /**
     * Pass message through to contained SocketIOInbound
     * If a timeout timer is set, then it will be reset.
     *
     * @param message
     */
    void onMessage(AtmosphereResourceImpl resource, SocketIOSessionOutbound handler, String message);

    /**
     * Pass disconnect through to contained SocketIOInbound and update any internal state.
     *
     * @param reason
     */
    void onDisconnect(DisconnectReason reason);

    /**
     * Called by handler to report that it is done and the session can be cleaned up.
     * If onDisconnect has not been called yet, then it will be called with DisconnectReason.ERROR.
     */
    void onShutdown();
}
