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
package org.atmosphere.protocol.socketio;

import java.io.IOException;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public interface SocketIOAtmosphereHandler extends AtmosphereHandler {
	
	
	public static final String SOCKETIO_SESSION_OUTBOUND = "SocketIOSessionOutbound";
	
	public static final String SOCKETIO_SESSION_ID = SocketIOAtmosphereHandler.class.getPackage().getName() + ".sessionid";
	
	
	/**
     * Called when the connection is established.
     *
     * @param outbound The SocketOutbound associated with the connection
     */
	void onConnect(AtmosphereResource event, SocketIOSessionOutbound handler) throws IOException;
	
    /**
     * Called when the socket connection is disconnected. 
     *
     * @param event		AtmosphereResource
     * @param handler	outbound handler to broadcast response
     * @param reason    The reason for the disconnect.
     */
    void onDisconnect(AtmosphereResource event, SocketIOSessionOutbound handler, DisconnectReason reason);

    /**
     * Called for each message received.
     *
     * @param event AtmosphereResource
     * @param handler outbound handler to broadcast response
     * @param message message received
     */
    void onMessage(AtmosphereResource event, SocketIOSessionOutbound handler, String message);
	
}
