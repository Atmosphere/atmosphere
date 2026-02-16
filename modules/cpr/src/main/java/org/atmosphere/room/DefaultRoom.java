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
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Default {@link Room} implementation backed by a {@link Broadcaster}.
 * <p>
 * Each room wraps a dedicated Broadcaster identified by the room name.
 * Presence events are tracked via a {@link BroadcasterListenerAdapter}
 * that listens for resource additions and removals.
 *
 * @since 4.0
 */
public class DefaultRoom implements Room {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRoom.class);

    private final String name;
    private final Broadcaster broadcaster;
    private final List<Consumer<PresenceEvent>> presenceListeners = new CopyOnWriteArrayList<>();
    private volatile boolean destroyed;

    /**
     * Create a room backed by the given broadcaster.
     *
     * @param name        the room name
     * @param broadcaster the backing broadcaster
     */
    public DefaultRoom(String name, Broadcaster broadcaster) {
        this.name = name;
        this.broadcaster = broadcaster;

        // Track resource removal (disconnect) to fire LEAVE events
        broadcaster.addBroadcasterListener(new BroadcasterListenerAdapter() {
            @Override
            public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                firePresence(PresenceEvent.Type.LEAVE, r);
            }
        });
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Room join(AtmosphereResource resource) {
        if (destroyed) throw new IllegalStateException("Room '" + name + "' is destroyed");

        broadcaster.addAtmosphereResource(resource);

        // Auto-leave on disconnect
        resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onDisconnect(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                leave(event.getResource());
            }

            @Override
            public void onClose(org.atmosphere.cpr.AtmosphereResourceEvent event) {
                leave(event.getResource());
            }
        });

        firePresence(PresenceEvent.Type.JOIN, resource);
        logger.debug("Resource {} joined room '{}'", resource.uuid(), name);
        return this;
    }

    @Override
    public Room leave(AtmosphereResource resource) {
        broadcaster.removeAtmosphereResource(resource);
        logger.debug("Resource {} left room '{}'", resource.uuid(), name);
        return this;
    }

    @Override
    public Future<Object> broadcast(Object message) {
        return broadcaster.broadcast(message);
    }

    @Override
    public Future<Object> broadcast(Object message, AtmosphereResource sender) {
        // Broadcast to everyone except sender
        Set<AtmosphereResource> targets = new java.util.HashSet<>(broadcaster.getAtmosphereResources());
        targets.remove(sender);
        if (targets.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(message);
        }
        return broadcaster.broadcast(message, targets);
    }

    @Override
    public Future<Object> sendTo(Object message, String uuid) {
        for (AtmosphereResource r : broadcaster.getAtmosphereResources()) {
            if (r.uuid().equals(uuid)) {
                return broadcaster.broadcast(message, r);
            }
        }
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override
    public Set<AtmosphereResource> members() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(broadcaster.getAtmosphereResources()));
    }

    @Override
    public int size() {
        return broadcaster.getAtmosphereResources().size();
    }

    @Override
    public boolean isEmpty() {
        return broadcaster.getAtmosphereResources().isEmpty();
    }

    @Override
    public boolean contains(AtmosphereResource resource) {
        return broadcaster.getAtmosphereResources().contains(resource);
    }

    @Override
    public Room onPresence(Consumer<PresenceEvent> listener) {
        presenceListeners.add(listener);
        return this;
    }

    @Override
    public void destroy() {
        destroyed = true;
        broadcaster.destroy();
        presenceListeners.clear();
        logger.info("Room '{}' destroyed", name);
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * @return the underlying broadcaster for advanced use cases
     */
    public Broadcaster broadcaster() {
        return broadcaster;
    }

    private void firePresence(PresenceEvent.Type type, AtmosphereResource resource) {
        var event = new PresenceEvent(type, this, resource);
        for (var listener : presenceListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Presence listener error in room '{}'", name, e);
            }
        }
    }
}
