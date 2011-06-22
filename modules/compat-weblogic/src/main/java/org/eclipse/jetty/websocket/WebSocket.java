/*
 * Copyright 2011 Jeanfrancois Arcand
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
/*
*
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
*
* Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
*
* The contents of this file are subject to the terms of either the GNU
* General Public License Version 2 only ("GPL") or the Common Development
* and Distribution License("CDDL") (collectively, the "License").  You
* may not use this file except in compliance with the License. You can obtain
* a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
* or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
* language governing permissions and limitations under the License.
*
* When distributing the software, include this License Header Notice in each
* file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
* Sun designates this particular file as subject to the "Classpath" exception
* as provided by Sun in the GPL Version 2 section of the License file that
* accompanied this code.  If applicable, add the following below the License
* Header, with the fields enclosed by brackets [] replaced by your own
* identifying information: "Portions Copyrighted [year]
* [name of copyright owner]"
*
* Contributor(s):
*
* If you wish your version of this file to be governed by only the CDDL or
* only the GPL Version 2, indicate your decision by adding "[Contributor]
* elects to include this software in this distribution under the [CDDL or GPL
* Version 2] license."  If you don't indicate a single choice of license, a
* recipient has the option to distribute your version of this file under
* either the CDDL, the GPL Version 2 or to extend the choice of license to
* its licensees as provided above.  However, if you add GPL Version 2 code
* and therefore, elected the GPL Version 2 license, then the option applies
* only if the new code is made subject to such option by the copyright
* holder.
*
*/
package org.eclipse.jetty.websocket;

import java.io.IOException;

/**
 * Fake class for portability across servers.
 */
public interface WebSocket {
    public final byte LENGTH_FRAME = (byte) 0x80;
    public final byte SENTINEL_FRAME = (byte) 0x00;

    void onConnect(Outbound outbound);

    void onMessage(byte frame, String data);

    void onMessage(byte frame, byte[] data, int offset, int length);

    void onFragment(boolean more, byte opcode, byte[] data, int offset, int length);

    void onDisconnect();

    public interface Outbound {
        void sendMessage(byte frame, String data) throws IOException;

        void sendMessage(byte frame, byte[] data) throws IOException;

        void sendMessage(byte frame, byte[] data, int offset, int length) throws IOException;

        void disconnect();

        boolean isOpen();
    }

    /**
     * Called when a new websocket connection is accepted.
     *
     * @param connection The Connection object to use to send messages.
     */
    void onOpen(Connection connection);

    /**
     * Called when an established websocket connection closes
     *
     * @param closeCode
     * @param message
     */
    void onClose(int closeCode, String message);

    /*
    * A nested WebSocket interface for receiving text messages
    */
    interface OnTextMessage extends WebSocket {
        /**
         * Called with a complete text message when all fragments have been received.
         * The maximum size of text message that may be aggregated from multiple frames is set with {@link Connection#setMaxTextMessageSize(int)}.
         *
         * @param data The message
         */
        void onMessage(String data);
    }

    /**
     * A nested WebSocket interface for receiving binary messages
     */
    interface OnBinaryMessage extends WebSocket {
        /**
         * Called with a complete binary message when all fragments have been received.
         * The maximum size of binary message that may be aggregated from multiple frames is set with {@link Connection#setMaxBinaryMessageSize(int)}.
         *
         * @param data
         * @param offset
         * @param length
         */
        void onMessage(byte[] data, int offset, int length);
    }

    /**
     * A nested WebSocket interface for receiving control messages
     */
    interface OnControl extends WebSocket {
        /**
         * Called when a control message has been received.
         *
         * @param controlCode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the control message and no further processing is needed.
         */
        boolean onControl(byte controlCode, byte[] data, int offset, int length);
    }

    /**
     * A nested WebSocket interface for receiving any websocket frame
     */
    interface OnFrame extends WebSocket {
        /**
         * Called when any websocket frame is received.
         *
         * @param flags
         * @param opcode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the frame and no further processing is needed (including aggregation and/or message delivery)
         */
        boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length);

        void onHandshake(FrameConnection connection);
    }

    /**
     * A  Connection interface is passed to a WebSocket instance via the {@link WebSocket#onOpen(Connection)} to
     * give the application access to the specifics of the current connection.   This includes methods
     * for sending frames and messages as well as methods for interpreting the flags and opcodes of the connection.
     */
    public interface Connection {
        String getProtocol();

        void sendMessage(String data) throws IOException;

        void sendMessage(byte[] data, int offset, int length) throws IOException;

        void disconnect();

        boolean isOpen();

        /**
         * @param size size<0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
         */
        void setMaxTextMessageSize(int size);

        /**
         * @param size size<0 no aggregation of binary frames, >=0 size of binary frame aggregation buffer
         */
        void setMaxBinaryMessageSize(int size);

        /**
         * Size in characters of the maximum text message to be received
         *
         * @return size <0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
         */
        int getMaxTextMessageSize();

        /**
         * Size in bytes of the maximum binary message to be received
         *
         * @return size <0 no aggregation of binary frames, >=0 size of binary frame aggregation buffer
         */
        int getMaxBinaryMessageSize();
    }

    /**
     * Frame Level Connection
     * <p>The Connection interface at the level of sending/receiving frames rather than messages.
     */
    public interface FrameConnection extends Connection {
        boolean isMessageComplete(byte flags);

        void close(int closeCode, String message);

        byte binaryOpcode();

        byte textOpcode();

        byte continuationOpcode();

        byte finMask();

        boolean isControl(byte opcode);

        boolean isText(byte opcode);

        boolean isBinary(byte opcode);

        boolean isContinuation(byte opcode);

        boolean isClose(byte opcode);

        boolean isPing(byte opcode);

        boolean isPong(byte opcode);

        void sendControl(byte control, byte[] data, int offset, int length) throws IOException;

        void sendFrame(byte flags, byte opcode, byte[] data, int offset, int length) throws IOException;
    }
}
