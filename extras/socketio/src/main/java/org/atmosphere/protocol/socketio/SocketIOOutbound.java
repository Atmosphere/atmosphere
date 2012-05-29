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

import java.util.List;

import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public interface SocketIOOutbound {
    
	/**
     * disconnect the current connection
     */
    void disconnect();

    /**
     * force close connection
     */
    void close();


    /**
     * Send a message to the client. If the session is still active, the message will be cached if the connection is closed.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(String message) throws SocketIOException;
    
    /**
     * Send a message to the client formatted is SocketIO format. If the session is still active, the message will be cached if the connection is closed.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(SocketIOPacket packet) throws SocketIOException;
    
    /**
     * Send messages to the client. If the session is still active, the messages will be cached if the connection is closed.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(List<SocketIOPacketImpl> messages) throws SocketIOException;
    
}
