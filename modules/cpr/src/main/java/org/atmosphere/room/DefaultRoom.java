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
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.atmosphere.cache.UUIDBroadcasterCache;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.List;
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
    private final ConcurrentHashMap<String, RoomMember> memberRegistry = new ConcurrentHashMap<>();
    private final Set<VirtualRoomMember> virtualMembers = ConcurrentHashMap.newKeySet();
    private volatile boolean destroyed;
    private volatile int historySize;

    /**
     * Monotonic id sequence for broadcast messages. Assigned at broadcast
     * time so clients can drive history-sync on reconnect via {@code sinceId}
     * in the next {@code join} frame.
     */
    private final java.util.concurrent.atomic.AtomicLong messageIdSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Ring-buffer of recent {@link RoomHistoryEntry} for {@code sinceId}-driven
     * replay. Sized by {@link #enableHistory(int)} — entries beyond the cap
     * fall off the front. {@link java.util.concurrent.ConcurrentLinkedDeque
     * ConcurrentLinkedDeque} is thread-safe for the add/poll/iteration patterns
     * used by {@link #recordHistory(long, String, Object)} and
     * {@link #historySince(long)}.
     */
    private final java.util.concurrent.ConcurrentLinkedDeque<RoomHistoryEntry> historyBuffer =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * One entry in the {@link #historyBuffer}. Captured at broadcast time so
     * a later {@code historySince} call can re-encode the message for a
     * reconnecting client without depending on the broadcaster cache's
     * resource-bucketing.
     */
    public record RoomHistoryEntry(long id, String fromMemberId, Object data, long timestamp) { }

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
        return join(resource, (RoomMember) null);
    }

    @Override
    public Room join(AtmosphereResource resource, RoomMember member) {
        if (destroyed) throw new IllegalStateException("Room '" + name + "' is destroyed");

        broadcaster.addAtmosphereResource(resource);

        if (member != null) {
            memberRegistry.put(resource.uuid(), member);
        }

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
        memberRegistry.remove(resource.uuid());
        broadcaster.removeAtmosphereResource(resource);
        logger.debug("Resource {} left room '{}'", resource.uuid(), name);
        return this;
    }

    @Override
    public Future<Object> broadcast(Object message) {
        dispatchToVirtualMembers(message, null);
        // Wrap in RawMessage so @ManagedService handlers pass it through without decoding
        return broadcaster.broadcast(new RawMessage(message));
    }

    @Override
    public Future<Object> broadcast(Object message, AtmosphereResource sender) {
        // Broadcast to everyone except sender
        dispatchToVirtualMembers(message, sender.uuid());
        var subset = new java.util.HashSet<>(broadcaster.getAtmosphereResources());
        subset.remove(sender);
        if (subset.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(message);
        }
        return broadcaster.broadcast(new RawMessage(message), subset);
    }

    @Override
    public Future<Object> sendTo(Object message, String uuid) {
        for (AtmosphereResource r : broadcaster.getAtmosphereResources()) {
            if (r.uuid().equals(uuid)) {
                return broadcaster.broadcast(new RawMessage(message), r);
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
    public Map<String, RoomMember> memberInfo() {
        return Collections.unmodifiableMap(memberRegistry);
    }

    @Override
    public Optional<RoomMember> memberOf(AtmosphereResource resource) {
        return Optional.ofNullable(memberRegistry.get(resource.uuid()));
    }

    @Override
    public Room enableHistory(int maxMessages) {
        this.historySize = maxMessages;
        var cache = new UUIDBroadcasterCache();
        cache.configure(broadcaster.getBroadcasterConfig().getAtmosphereConfig());
        cache.setMaxPerClient(maxMessages);
        broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
        logger.debug("Enabled history ({} max) for room '{}'", maxMessages, name);
        return this;
    }

    /**
     * @return the configured history size, or 0 if history is disabled
     */
    public int historySize() {
        return historySize;
    }

    /**
     * Allocate the next monotonic message id for this room. Called from the
     * {@code RoomProtocolInterceptor} broadcast handler so every wire
     * message carries a server-assigned id usable for history-sync.
     *
     * @return a strictly increasing positive long unique within this room
     */
    public long nextMessageId() {
        return messageIdSeq.incrementAndGet();
    }

    /**
     * Append a broadcast to the in-memory history ring buffer (no-op when
     * {@link #enableHistory(int)} has not been called). The buffer trims
     * itself to {@link #historySize}; the oldest entries are evicted.
     *
     * @param id          the server-assigned message id from {@link #nextMessageId()}
     * @param fromMemberId the broadcast originator's member id, may be null
     * @param data        the payload as decoded by the protocol layer
     */
    public void recordHistory(long id, String fromMemberId, Object data) {
        if (historySize <= 0) {
            return;
        }
        historyBuffer.addLast(new RoomHistoryEntry(id, fromMemberId, data, System.currentTimeMillis()));
        while (historyBuffer.size() > historySize) {
            historyBuffer.pollFirst();
        }
    }

    /**
     * Return the history entries strictly newer than {@code sinceId}, oldest first.
     * Used by the {@code RoomProtocolInterceptor} join handler when a client
     * carries a {@code sinceId} cursor — only the messages it missed are
     * replayed instead of the full buffer.
     *
     * @param sinceId the last id the client already has; pass 0 to replay all
     * @return an immutable in-order list of newer entries
     */
    public java.util.List<RoomHistoryEntry> historySince(long sinceId) {
        java.util.List<RoomHistoryEntry> out = new java.util.ArrayList<>();
        for (var entry : historyBuffer) {
            if (entry.id() > sinceId) {
                out.add(entry);
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    @Override
    public Room joinVirtual(VirtualRoomMember member) {
        if (destroyed) throw new IllegalStateException("Room '" + name + "' is destroyed");
        virtualMembers.add(member);
        fireVirtualPresence(PresenceEvent.Type.JOIN, member);
        logger.debug("Virtual member '{}' joined room '{}'", member.id(), name);
        return this;
    }

    @Override
    public Room leaveVirtual(VirtualRoomMember member) {
        virtualMembers.remove(member);
        fireVirtualPresence(PresenceEvent.Type.LEAVE, member);
        logger.debug("Virtual member '{}' left room '{}'", member.id(), name);
        return this;
    }

    @Override
    public Set<VirtualRoomMember> virtualMembers() {
        return Collections.unmodifiableSet(virtualMembers);
    }

    @Override
    public void destroy() {
        destroyed = true;
        virtualMembers.clear();
        memberRegistry.clear();
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
        var member = memberRegistry.get(resource.uuid());
        var event = new PresenceEvent(type, this, resource, member);
        for (var listener : presenceListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Presence listener error in room '{}'", name, e);
            }
        }
    }

    private void fireVirtualPresence(PresenceEvent.Type type, VirtualRoomMember member) {
        var roomMember = new RoomMember(member.id(), member.metadata());
        var event = new PresenceEvent(type, this, roomMember);
        for (var listener : presenceListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Presence listener error in room '{}'", name, e);
            }
        }
    }

    private void dispatchToVirtualMembers(Object message, String excludeId) {
        for (var vm : virtualMembers) {
            if (vm.id().equals(excludeId)) continue;
            try {
                vm.onMessage(this, excludeId != null ? excludeId : "room", message);
            } catch (Exception e) {
                logger.warn("Virtual member '{}' error in room '{}'", vm.id(), name, e);
            }
        }
    }
}
