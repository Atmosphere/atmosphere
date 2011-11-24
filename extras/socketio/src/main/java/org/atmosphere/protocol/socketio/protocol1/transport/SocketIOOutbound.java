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

import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOException;


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

    ConnectionState getConnectionState();

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
     * Send a message.
     *
     * @param messageType
     * @param message
     * @throws IllegalStateException if the socket is not CONNECTED.
     * @throws SocketIOException
     */
    void sendMessage(int messageType, String message) throws SocketIOException;
}
