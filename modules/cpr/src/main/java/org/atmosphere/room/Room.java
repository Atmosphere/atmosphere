/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.room;

import org.atmosphere.cpr.AtmosphereResource;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * A named group of {@link AtmosphereResource} connections. Rooms provide
 * a higher-level abstraction over {@link org.atmosphere.cpr.Broadcaster}
 * for managing groups of connected clients with presence tracking.
 *
 * <pre>{@code
 * RoomManager rooms = RoomManager.create(framework);
 * Room lobby = rooms.room("lobby");
 * lobby.join(resource);
 * lobby.broadcast("Hello everyone!");
 * lobby.onPresence(event -> log.info(event.member() + " " + event.type()));
 * }</pre>
 *
 * @author Jeanfrancois Arcand
 * @since 4.0
 */
public interface Room {

    /**
     * @return the room name (unique identifier)
     */
    String name();

    /**
     * Add a resource to this room.
     *
     * @param resource the resource to add
     * @return this room for chaining
     */
    Room join(AtmosphereResource resource);

    /**
     * Remove a resource from this room.
     *
     * @param resource the resource to remove
     * @return this room for chaining
     */
    Room leave(AtmosphereResource resource);

    /**
     * Broadcast a message to all members of this room.
     *
     * @param message the message to broadcast
     * @return a future that completes when the broadcast is delivered
     */
    Future<Object> broadcast(Object message);

    /**
     * Broadcast a message to all members except the sender.
     *
     * @param message the message to broadcast
     * @param sender  the resource to exclude
     * @return a future that completes when the broadcast is delivered
     */
    Future<Object> broadcast(Object message, AtmosphereResource sender);

    /**
     * Send a direct message to a specific member by UUID.
     *
     * @param message the message to send
     * @param uuid    the target resource's UUID
     * @return a future that completes when the message is delivered
     */
    Future<Object> sendTo(Object message, String uuid);

    /**
     * @return an unmodifiable set of all current members
     */
    Set<AtmosphereResource> members();

    /**
     * @return the number of members in this room
     */
    int size();

    /**
     * @return true if this room has no members
     */
    boolean isEmpty();

    /**
     * Check if a resource is a member of this room.
     *
     * @param resource the resource to check
     * @return true if the resource is in this room
     */
    boolean contains(AtmosphereResource resource);

    /**
     * Register a presence listener for join/leave events.
     *
     * @param listener the presence event listener
     * @return this room for chaining
     */
    Room onPresence(Consumer<PresenceEvent> listener);

    /**
     * Destroy this room, removing all members and releasing resources.
     */
    void destroy();

    /**
     * @return true if this room has been destroyed
     */
    boolean isDestroyed();
}
