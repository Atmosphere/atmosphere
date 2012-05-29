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

import org.atmosphere.websocket.WebSocket;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public interface SocketIOWebSocketSessionWrapper extends SocketIOSessionOutbound {
	
	/**
	 * 
	 * @return
	 */
	SocketIOSession getSession();
	
	/**
	 * 
	 */
	void onDisconnect();
	
	/**
	 * 
	 * @param frame
	 * @param message
	 */
	void onMessage(byte frame, String message);
	
	/**
	 * 
	 * @param frame
	 * @param data
	 * @param offset
	 * @param length
	 */
	void onMessage(byte frame, byte[] data, int offset, int length);
	
	/**
	 * 
	 * @return
	 */
	boolean isInitiated();
	
	/**
	 * 
	 * @return
	 */
	WebSocket webSocket();
	 
	/**
	 * 
	 * @param websocket
	 */
	void setWebSocket(WebSocket websocket);

	/**
	 * 
	 * @param initialed
	 */
	void initiated(boolean initialed);
}
