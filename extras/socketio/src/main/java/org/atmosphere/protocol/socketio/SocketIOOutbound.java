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
     * Terminate the connection. This method may return before the connection disconnect
     * completes. The onDisconnect() method of the associated SocketInbound will be called
     * when the disconnect is completed. The onDisconnect() method may be called during the
     * invocation of this method.
     */
    void disconnect();

    /**
     * Initiate an orderly close of the connection. The state will be changed to CLOSING so no
     * new messages can be sent, but messages may still arrive until the distant end has
     * acknowledged the close.
     */
    void close();


    /**
     * Send a message to the client. This method will block if the message will not fit in the
     * outbound buffer.
     * If the socket is closed, becomes closed, or times out, while trying to send the message,
     * the SocketClosedException will be thrown.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(String message) throws SocketIOException;
    
    /**
     * Send a message to the client. This method will block if the message will not fit in the
     * outbound buffer.
     * If the socket is closed, becomes closed, or times out, while trying to send the message,
     * the SocketClosedException will be thrown.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(SocketIOPacket packet) throws SocketIOException;
    
    /**
     * Send a message to the client. This method will block if the message will not fit in the
     * outbound buffer.
     * If the socket is closed, becomes closed, or times out, while trying to send the message,
     * the SocketClosedException will be thrown.
     *
     * @param message The message to send
     * @throws SocketIOException
     */
    void sendMessage(List<SocketIOPacketImpl> messages) throws SocketIOException;
    
}
