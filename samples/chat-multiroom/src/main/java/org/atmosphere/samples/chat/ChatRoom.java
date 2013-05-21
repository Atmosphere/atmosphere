/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.MetaBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple annotated class that demonstrate the power of Atmosphere. This class supports all transports, support
 * message length garantee, heart beat, message cache thanks to the @managedAService.
 */
@ManagedService(path = "{room: [a-zA-Z][a-zA-Z_0-9]*}")
public class ChatRoom {
    private final Logger logger = LoggerFactory.getLogger(ChatRoom.class);

    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<String, String>();
    private String chatroomName;
    private String mappedPath;
    private BroadcasterFactory factory;

    /**
     * Invoked when the connection as been fully established and suspended, e.g ready for receiving messages.
     *
     * @param r
     */
    @Ready(value = Ready.DELIVER_TO.ALL, encoders = {JacksonEncoder.class})
    public ChatProtocol onReady(final AtmosphereResource r) {
        logger.info("Browser {} connected.", r.uuid());
        if (chatroomName == null) {
            mappedPath = r.getBroadcaster().getID();
            // Get rid of the AtmosphereFramework mapped path.
            chatroomName = mappedPath.split("/")[2];
            factory = r.getAtmosphereConfig().getBroadcasterFactory();
        }

        return new ChatProtocol(users.keySet(), factory.lookupAll());
    }

    /**
     * Invoked when the client disconnect or when an unexpected closing of the underlying connection happens.
     *
     * @param event
     */
    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        if (event.isCancelled()) {
            // We didn't get notified, so we remove the user.
            users.values().remove(event.getResource().uuid());
            logger.info("Browser {} unexpectedly disconnected", event.getResource().uuid());
        } else if (event.isClosedByClient()) {
            logger.info("Browser {} closed the connection", event.getResource().uuid());
        }
    }

    /**
     * Simple annotated class that demonstrate how {@link org.atmosphere.config.managed.Encoder} and {@link org.atmosphere.config.managed.Decoder
     * can be used.
     *
     * @param message an instance of {@link ChatProtocol }
     * @return
     * @throws IOException
     */
    @Message(encoders = {JacksonEncoder.class}, decoders = {ProtocolDecoder.class})
    public ChatProtocol onMessage(ChatProtocol message) throws IOException {

        if (!users.containsKey(message.getAuthor())) {
            users.put(message.getAuthor(), message.getUuid());
            return new ChatProtocol(message.getAuthor(), " entered room " + chatroomName, users.keySet(), factory.lookupAll());
        }

        if (message.getMessage().contains("disconnecting")) {
            users.remove(message.getAuthor());
            return new ChatProtocol(message.getAuthor(), " disconnected from room " + chatroomName, users.keySet(), factory.lookupAll());
        }

        message.setUsers(users.keySet());
        logger.info("{} just send {}", message.getAuthor(), message.getMessage());
        return new ChatProtocol(message.getAuthor(), message.getMessage(), users.keySet(), factory.lookupAll());
    }

    @Message(decoders = {UserDecoder.class})
    public void onPrivateMessage(UserMessage user) throws IOException {
        String userUUID = users.get(user.getUser());
        if (userUUID != null) {
            AtmosphereResource r = AtmosphereResourceFactory.getDefault().find(userUUID);

            if (r != null) {
                ChatProtocol m = new ChatProtocol(user.getUser(), " sent you a private message: " + user.getMessage().split(":")[1], users.keySet(), factory.lookupAll());
                if (!user.getUser().equalsIgnoreCase("all")) {
                    factory.lookup(mappedPath).broadcast(m, r);
                }
            }
        } else {
            ChatProtocol m = new ChatProtocol(user.getUser(), " sent a message to all chatroom: " + user.getMessage().split(":")[1], users.keySet(), factory.lookupAll());
            MetaBroadcaster.getDefault().broadcastTo("/*", m);
        }
    }

}