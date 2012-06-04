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
package org.atmosphere.socketio.transport;

import org.atmosphere.socketio.SocketIOException;
import org.atmosphere.socketio.SocketIOPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 */
public class SocketIOPacketImpl implements SocketIOPacket {

    public static final char SOCKETIO_MSG_DELIMITER = '\ufffd';

    public final static String POST_RESPONSE = "1";
    private final PacketType packetType;

    private final String id;
    private final String endpoint;
    private final String data;
    private boolean padding = false;

    public SocketIOPacketImpl(PacketType frameType) {
        this(frameType, null, null, null, false);
    }

    public SocketIOPacketImpl(PacketType frameType, String data) {
        this(frameType, null, null, data, false);
    }

    public SocketIOPacketImpl(PacketType frameType, String data, boolean padding) {
        this(frameType, null, null, data, padding);
    }

    public SocketIOPacketImpl(PacketType frameType, String id, String endpoint, String data) {
        this(frameType, id, endpoint, data, false);
    }

    public SocketIOPacketImpl(PacketType frameType, String id, String endpoint, String data, boolean padding) {
        this.packetType = frameType;
        this.id = id;
        this.endpoint = endpoint;
        this.data = data;
        this.padding = padding;
    }

    public PacketType getFrameType() {
        return packetType;
    }

    public String getData() {
        return data;
    }

    public void setPadding(boolean padding) {
        this.padding = padding;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();

        //[message type] ':' [message id ('+')] ':' [message endpoint] (':' [message data])
        sb.append(packetType.value).append(":");

        if (id != null) {
            sb.append(id);
        }

        sb.append(":");

        if (endpoint != null) {
            sb.append(endpoint);
        }

        if (data != null) {
            sb.append(":");
            sb.append(data);
        }

        String msg = sb.toString();

        if (padding) {
            sb = new StringBuilder();
            sb.append(SOCKETIO_MSG_DELIMITER).append(msg.length()).append(SOCKETIO_MSG_DELIMITER).append(msg);
            msg = sb.toString();
        }


        return msg;
    }


    public enum PacketType {
        UNKNOWN(-1),
        DISCONNECT(0),
        CONNECT(1),
        HEARTBEAT(2),
        MESSAGE(3),
        JSON(4),
        EVENT(5),
        ACK(6),
        ERROR(7),
        NOOP(8);

        private int value;

        PacketType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static PacketType fromInt(int val) {
            switch (val) {
                case 0:
                    return DISCONNECT;
                case 1:
                    return CONNECT;
                case 2:
                    return HEARTBEAT;
                case 3:
                    return MESSAGE;
                case 4:
                    return JSON;
                case 5:
                    return EVENT;
                case 6:
                    return ACK;
                case 7:
                    return ERROR;
                case 8:
                    return NOOP;
                default:
                    return UNKNOWN;
            }
        }
    }

    private static SocketIOPacketImpl parse2(String data) throws SocketIOException {

        try {
            String array[] = data.split(":");

            if (array.length < 1) {
                System.err.println("Message invalide=" + data);
                return null;
            }

            String type = null;
            String id = null;
            String endpoint = null;
            String message = null;

            //[message type] ':' [message id ('+')] ':' [message endpoint] (':' [message data])

            // 0::/test
            // 1::/test?my=param
            // 2::
            // 3:1::blabla
            // 4:1::{"a":"b"}
            // 5:::{"args":["user1","user2 ecrit coucou"],"name":"user message"}
            // 6:::4+["A","B"]  ou 6:::4
            // '7::' [endpoint] ':' [reason] '+' [advice]
            // 8 ..

            if (array.length == 1) {
                type = array[0];
            } else if (array.length == 2) {
                type = array[0];
                id = array[1];
            } else if (array.length == 3) {
                type = array[0];
                id = array[1];
                endpoint = array[2];
            } else {

                type = array[0];
                id = array[1];
                endpoint = array[2];

                // skip the first 3 ":"

                int start = data.indexOf(":");

                start = data.indexOf(":", ++start);
                start = data.indexOf(":", ++start);

                if (start > -1) {
                    message = data.substring(start + 1);
                }
            }

            return new SocketIOPacketImpl(PacketType.fromInt(Integer.parseInt(type)), id, endpoint, message);
        } catch (Exception e) {
            throw new SocketIOException("Invalid message = " + data, e);
        }
    }

    public static List<SocketIOPacketImpl> parse(String data) throws SocketIOException {
        List<SocketIOPacketImpl> messages = new ArrayList<SocketIOPacketImpl>();

        if (data == null || data.length() == 0) {
            return messages;
        }


        try {
            // look for delimiter
            if (data.charAt(0) == SocketIOPacketImpl.SOCKETIO_MSG_DELIMITER) {

                int size = data.length();
                int previousDelimiterIndex = 1;
                int nextDelimiterIndex = -1;

                // find next delimiter
                for (int i = 1; i < size; i++) {
                    for (; i < size; i++) {
                        if (data.charAt(i) == SocketIOPacketImpl.SOCKETIO_MSG_DELIMITER) {
                            nextDelimiterIndex = i;
                            break;
                        }
                    }

                    if (nextDelimiterIndex > previousDelimiterIndex) {
                        int length = Integer.parseInt(data.substring(previousDelimiterIndex, nextDelimiterIndex));

                        i = ++nextDelimiterIndex + length;
                        previousDelimiterIndex = i + 1;

                        SocketIOPacketImpl msg = parse2(data.substring(nextDelimiterIndex, i));

                        if (msg != null) {
                            messages.add(msg);
                        }

                    }
                }

            } else {

                SocketIOPacketImpl msg = parse2(data);

                if (msg != null) {
                    messages.add(msg);
                }
            }

            return messages;
        } catch (Exception e) {
            throw new SocketIOException("Invalid message=" + data, e);
        }
    }

}
