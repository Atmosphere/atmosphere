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
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.socketio.SocketIOSessionOutbound;
import org.atmosphere.socketio.cpr.SocketIOAtmosphereHandler;
import org.atmosphere.socketio.transport.DisconnectReason;
import org.atmosphere.socketio.transport.SocketIOPacketImpl;
import org.atmosphere.socketio.transport.SocketIOPacketImpl.PacketType;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Simple SocketIOAtmosphereHandler that implements the logic to build a
 * SocketIO Chat application.
 *
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class ChatAtmosphereHandler extends SocketIOAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatAtmosphereHandler.class);
    private final ConcurrentMap<String, String> loggedUserMap = new ConcurrentSkipListMap<String, String>();
    private final ObjectMapper mapper = new ObjectMapper();
    private Broadcaster broadcaster;

    public void onConnect(AtmosphereResource r, SocketIOSessionOutbound outbound) throws IOException {
        logger.debug("onConnect");
        broadcaster = r.getBroadcaster();
    }

    public void onMessage(AtmosphereResource r, SocketIOSessionOutbound outbound, String message) {

        if (outbound == null || message == null || message.length() == 0) {
            return;
        }

        AtmosphereRequest request = r.getRequest();
        try {
            logger.debug("onMessage on SessionID=" + outbound.getSessionId() + "  : Message Received = " + message);
            ChatJSONObject chat = mapper.readValue(message, ChatJSONObject.class);

            if (ChatJSONObject.LOGIN.equalsIgnoreCase(chat.name)) {
                request.getSession().setAttribute("LOGINNAME", chat.getArgs().toArray()[0]);
                String username = (String) chat.getArgs().toArray()[0];

                // username already in use ?
                if (loggedUserMap.containsValue(username)) {
                    outbound.sendMessage(new SocketIOPacketImpl(PacketType.ACK, "1+[true]").toString());
                } else {
                    loggedUserMap.put(outbound.getSessionId(), username);

                    try {
                        ChatJSONObject out = new ChatJSONObject();

                        out.setName(ChatJSONObject.USERCONNECTEDLIST);
                        List list = new ArrayList();

                        list.add(loggedUserMap);

                        out.setArgs(list);

                        List<SocketIOPacketImpl> loginMessagesList = new ArrayList(2);

                        // send login confirmation
                        loginMessagesList.add(new SocketIOPacketImpl(PacketType.ACK, "1+[false]"));

                        // send a list of connected users
                        loginMessagesList.add(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out)));

                        // send the list only for this user (will not be broadcasted)
                        outbound.sendMessage(loginMessagesList);

                        // send the new list of connected users to the users already connected
                        broadcaster.broadcast(mapper.writeValueAsString(out), r);

                        // send a Event to all clients telling that a new user was connected
                        broadcaster.broadcast("{\"args\":[\"" + chat.getArgs().toArray()[0] + " connected\"],\"name\":\"announcement\"}", r);

                    } catch (Exception e) {
                        logger.error("", e);
                        outbound.disconnect();
                    }

                }

            } else if (ChatJSONObject.MESSAGE.equalsIgnoreCase(chat.name)) {

                String username = loggedUserMap.get(outbound.getSessionId());

                List<String> msg = new ArrayList<String>();
                msg.add(username);
                msg.addAll(chat.args);

                ChatJSONObject out = new ChatJSONObject();

                out.setName(ChatJSONObject.MESSAGE);
                out.setArgs(msg);

                // broadcast the message to all other users
                broadcaster.broadcast(mapper.writeValueAsString(out), r);

            }

        } catch (IOException e) {
            logger.error("", e);
        }
    }

    public void onDisconnect(AtmosphereResource r, SocketIOSessionOutbound outbound, DisconnectReason reason) {
        logger.debug("onDisconnect from sessionid = " + outbound.getSessionId() + " username=" + loggedUserMap.get(outbound.getSessionId()));

        String sessionid = outbound.getSessionId();

        String username = loggedUserMap.get(sessionid);

        // broadcast to other user that his user is disconnected
        broadcaster.broadcast("{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}",r);

        // remove the username from the cache
        loggedUserMap.remove(sessionid);

        // regenerate the list of connected users and broadcast it
        ObjectMapper mapper = new ObjectMapper();

        ChatJSONObject out = new ChatJSONObject();
        out.setName(ChatJSONObject.USERCONNECTEDLIST);
        List list = new ArrayList();
        list.add(loggedUserMap);
        out.setArgs(list);

        try {
            broadcaster.broadcast(new SocketIOPacketImpl(PacketType.EVENT, mapper.writeValueAsString(out), false).toString(), r);
        } catch (Exception e) {
            logger.error("", e);
        }

    }

}
