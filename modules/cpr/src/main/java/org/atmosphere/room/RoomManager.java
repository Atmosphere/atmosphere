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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages the lifecycle of {@link Room} instances. Each room is backed by
 * a dedicated {@link Broadcaster} obtained from the framework's
 * {@link BroadcasterFactory}.
 *
 * <pre>{@code
 * RoomManager rooms = RoomManager.create(framework);
 *
 * // Get or create a room
 * Room lobby = rooms.room("lobby");
 * lobby.join(resource);
 * lobby.broadcast("Welcome!");
 *
 * // List all rooms
 * rooms.all().forEach(r -> log.info("Room: " + r.name()));
 *
 * // Destroy a room
 * rooms.destroy("lobby");
 * }</pre>
 *
 * @author Jeanfrancois Arcand
 * @since 4.0
 */
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final String ROOM_PREFIX = "/atmosphere/room/";

    private final BroadcasterFactory broadcasterFactory;
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

    private RoomManager(BroadcasterFactory broadcasterFactory) {
        this.broadcasterFactory = broadcasterFactory;
    }

    /**
     * Create a RoomManager from an AtmosphereFramework.
     *
     * @param framework the Atmosphere framework instance
     * @return a new RoomManager
     */
    public static RoomManager create(AtmosphereFramework framework) {
        return new RoomManager(framework.getBroadcasterFactory());
    }

    /**
     * Get or create a room by name. The room will be lazily created
     * on first access and backed by a broadcaster with ID
     * {@code /atmosphere/room/<name>}.
     *
     * @param name the room name
     * @return the room
     */
    public Room room(String name) {
        return rooms.computeIfAbsent(name, n -> {
            Broadcaster broadcaster = broadcasterFactory.lookup(ROOM_PREFIX + n, true);
            logger.info("Created room '{}'", n);
            return new DefaultRoom(n, broadcaster);
        });
    }

    /**
     * Check if a room exists.
     *
     * @param name the room name
     * @return true if the room exists
     */
    public boolean exists(String name) {
        return rooms.containsKey(name);
    }

    /**
     * Get all active rooms.
     *
     * @return an unmodifiable collection of rooms
     */
    public Collection<Room> all() {
        return Collections.unmodifiableCollection(rooms.values());
    }

    /**
     * @return the number of active rooms
     */
    public int count() {
        return rooms.size();
    }

    /**
     * Destroy a room by name, removing all members.
     *
     * @param name the room name
     * @return true if the room existed and was destroyed
     */
    public boolean destroy(String name) {
        Room room = rooms.remove(name);
        if (room != null) {
            room.destroy();
            return true;
        }
        return false;
    }

    /**
     * Destroy all rooms.
     */
    public void destroyAll() {
        rooms.values().forEach(Room::destroy);
        rooms.clear();
        logger.info("All rooms destroyed");
    }
}
