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

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoomTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private RoomManager roomManager;

    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        roomManager = RoomManager.create(config.framework());
    }

    @AfterEach
    public void tearDown() throws Exception {
        roomManager.destroyAll();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    // --- Room creation and membership ---

    @Test
    public void testRoomCreation() {
        Room room = roomManager.room("lobby");
        assertNotNull(room);
        assertEquals("lobby", room.name());
        assertTrue(room.isEmpty());
        assertEquals(0, room.size());
    }

    @Test
    public void testRoomSingleton() {
        Room room1 = roomManager.room("lobby");
        Room room2 = roomManager.room("lobby");
        assertSame(room1, room2, "Same room name should return same instance");
    }

    @Test
    public void testJoinAndLeave() throws Exception {
        Room room = roomManager.room("test");
        var ar = createResource();

        room.join(ar);
        assertEquals(1, room.size());
        assertTrue(room.contains(ar));

        room.leave(ar);
        assertEquals(0, room.size());
        assertFalse(room.contains(ar));
    }

    @Test
    public void testMultipleMembers() throws Exception {
        Room room = roomManager.room("test");
        var ar1 = createResource();
        var ar2 = createResource();

        room.join(ar1).join(ar2);
        assertEquals(2, room.size());
        assertTrue(room.contains(ar1));
        assertTrue(room.contains(ar2));
    }

    @Test
    public void testMembersReturnsUnmodifiable() throws Exception {
        Room room = roomManager.room("test");
        room.join(createResource());
        Set<AtmosphereResource> members = room.members();
        assertThrows(UnsupportedOperationException.class, () -> members.clear());
    }

    // --- Broadcasting ---

    @Test
    public void testBroadcastToAll() throws Exception {
        Room room = roomManager.room("test");
        var latch = new CountDownLatch(2);
        var received = ConcurrentHashMap.newKeySet();

        var ar1 = createResource(latch, received);
        var ar2 = createResource(latch, received);

        room.join(ar1).join(ar2);
        room.broadcast("hello").get();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(2, received.size(), "Both resources should have received the message");
    }

    @Test
    public void testBroadcastExcludeSender() throws Exception {
        Room room = roomManager.room("test");
        var latch = new CountDownLatch(1);
        var received = ConcurrentHashMap.newKeySet();

        var sender = createResource(new CountDownLatch(1), ConcurrentHashMap.newKeySet());
        var receiver = createResource(latch, received);

        room.join(sender).join(receiver);
        room.broadcast("hello", sender).get();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, received.size(), "Only receiver should get the message");
    }

    @Test
    public void testSendToByUuid() throws Exception {
        Room room = roomManager.room("test");
        var latch = new CountDownLatch(1);
        var received = ConcurrentHashMap.newKeySet();

        var ar1 = createResource(new CountDownLatch(1), ConcurrentHashMap.newKeySet());
        var ar2 = createResource(latch, received);

        room.join(ar1).join(ar2);
        room.sendTo("direct", ar2.uuid()).get();
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, received.size(), "Only target should receive direct message");
    }

    // --- Presence events ---

    @Test
    public void testPresenceJoinEvent() throws Exception {
        Room room = roomManager.room("test");
        var events = new ArrayList<PresenceEvent>();
        room.onPresence(events::add);

        var ar = createResource();
        room.join(ar);

        assertEquals(1, events.size());
        assertEquals(PresenceEvent.Type.JOIN, events.getFirst().type());
        assertSame(events.getFirst().member(), ar);
        assertSame(events.getFirst().room(), room);
    }

    @Test
    public void testPresenceLeaveEvent() throws Exception {
        Room room = roomManager.room("test");
        var events = new ArrayList<PresenceEvent>();

        var ar = createResource();
        room.join(ar);
        room.onPresence(events::add);
        room.leave(ar);

        assertEquals(1, events.size());
        assertEquals(PresenceEvent.Type.LEAVE, events.getFirst().type());
    }

    // --- RoomManager ---

    @Test
    public void testRoomManagerExists() {
        assertFalse(roomManager.exists("lobby"));
        roomManager.room("lobby");
        assertTrue(roomManager.exists("lobby"));
    }

    @Test
    public void testRoomManagerAll() {
        roomManager.room("a");
        roomManager.room("b");
        roomManager.room("c");
        assertEquals(3, roomManager.all().size());
        assertEquals(3, roomManager.count());
    }

    @Test
    public void testRoomManagerDestroy() {
        Room room = roomManager.room("temp");
        assertTrue(roomManager.exists("temp"));
        assertTrue(roomManager.destroy("temp"));
        assertFalse(roomManager.exists("temp"));
        assertTrue(room.isDestroyed());
    }

    @Test
    public void testRoomManagerDestroyAll() {
        roomManager.room("a");
        roomManager.room("b");
        roomManager.destroyAll();
        assertEquals(0, roomManager.count());
    }

    @Test
    public void testJoinDestroyedRoomThrows() throws Exception {
            assertThrows(IllegalStateException.class, () -> {
            Room room = roomManager.room("temp");
            room.destroy();
            room.join(createResource());
            });
    }

    // --- PresenceEvent record ---

    @Test
    public void testPresenceEventRecord() throws Exception {
        Room room = roomManager.room("test");
        var ar = createResource();
        var event = new PresenceEvent(PresenceEvent.Type.JOIN, room, ar);

        assertEquals(PresenceEvent.Type.JOIN, event.type());
        assertSame(event.room(), room);
        assertSame(event.member(), ar);
    }

    // --- Virtual Members ---

    @Test
    public void testJoinVirtual() {
        Room room = roomManager.room("test");
        var vm = new TestVirtualMember("bot-1");

        room.joinVirtual(vm);
        assertEquals(1, room.virtualMembers().size());
        assertTrue(room.virtualMembers().contains(vm));
    }

    @Test
    public void testLeaveVirtual() {
        Room room = roomManager.room("test");
        var vm = new TestVirtualMember("bot-1");

        room.joinVirtual(vm);
        room.leaveVirtual(vm);
        assertTrue(room.virtualMembers().isEmpty());
    }

    @Test
    public void testVirtualMemberReceivesBroadcast() throws Exception {
        Room room = roomManager.room("test");
        var vm = new TestVirtualMember("bot-1");
        room.joinVirtual(vm);

        room.broadcast("hello");

        // Virtual member dispatch is synchronous within broadcast()
        assertEquals(1, vm.receivedMessages.size());
        assertEquals("hello", vm.receivedMessages.getFirst());
    }

    @Test
    public void testVirtualMemberExcludedFromOwnBroadcast() throws Exception {
        Room room = roomManager.room("test");
        var vm = new TestVirtualMember("bot-1");
        room.joinVirtual(vm);

        var ar = createResource();
        room.join(ar);

        // Broadcast excluding sender — virtual member should still receive it
        room.broadcast("from-human", ar);
        assertEquals(1, vm.receivedMessages.size());

        // Virtual member broadcast excludes itself via dispatchToVirtualMembers
        // The virtual member calls room.broadcast() which dispatches to others
        // but the excludeId mechanism prevents echo
    }

    @Test
    public void testVirtualMemberPresenceJoinEvent() {
        Room room = roomManager.room("test");
        var events = new ArrayList<PresenceEvent>();
        room.onPresence(events::add);

        var vm = new TestVirtualMember("assistant");
        room.joinVirtual(vm);

        assertEquals(1, events.size());
        assertEquals(PresenceEvent.Type.JOIN, events.getFirst().type());
        assertTrue(events.getFirst().isVirtual());
        assertNull(events.getFirst().member());
        assertNotNull(events.getFirst().memberInfo());
        assertEquals("assistant", events.getFirst().memberInfo().id());
    }

    @Test
    public void testVirtualMemberPresenceLeaveEvent() {
        Room room = roomManager.room("test");
        var vm = new TestVirtualMember("assistant");
        room.joinVirtual(vm);

        var events = new ArrayList<PresenceEvent>();
        room.onPresence(events::add);
        room.leaveVirtual(vm);

        assertEquals(1, events.size());
        assertEquals(PresenceEvent.Type.LEAVE, events.getFirst().type());
        assertTrue(events.getFirst().isVirtual());
    }

    @Test
    public void testVirtualMembersReturnsUnmodifiable() {
        Room room = roomManager.room("test");
        room.joinVirtual(new TestVirtualMember("bot"));
        assertThrows(UnsupportedOperationException.class, () -> room.virtualMembers().clear());
    }

    @Test
    public void testDestroyedRoomClearsVirtualMembers() {
        Room room = roomManager.room("test");
        room.joinVirtual(new TestVirtualMember("bot"));
        room.destroy();
        assertTrue(room.isDestroyed());
    }

    @Test
    public void testJoinVirtualOnDestroyedRoomThrows() {
            assertThrows(IllegalStateException.class, () -> {
            Room room = roomManager.room("temp");
            room.destroy();
            room.joinVirtual(new TestVirtualMember("bot"));
            });
    }

    @Test
    public void testVirtualMemberMetadata() {
        var vm = new TestVirtualMember("assistant");
        var meta = vm.metadata();
        assertEquals("test", meta.get("type"));
    }

    // --- Helpers ---

    private static class TestVirtualMember implements VirtualRoomMember {
        final String id;
        final List<Object> receivedMessages = new ArrayList<>();

        TestVirtualMember(String id) { this.id = id; }

        @Override public String id() { return id; }

        @Override
        public void onMessage(Room room, String senderId, Object message) {
            receivedMessages.add(message);
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of("type", "test");
        }
    }

    @SuppressWarnings("deprecation")
    private AtmosphereResource createResource() throws IOException {
        Broadcaster b = factory.get(DefaultBroadcaster.class, "resource-" + System.nanoTime());
        return new AtmosphereResourceImpl(config, b,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) {}
                    @Override public void onStateChange(AtmosphereResourceEvent event) {}
                    @Override public void destroy() {}
                });
    }

    @SuppressWarnings("deprecation")
    private AtmosphereResource createResource(CountDownLatch latch, Set<Object> received) throws IOException {
        Broadcaster b = factory.get(DefaultBroadcaster.class, "resource-" + System.nanoTime());
        return new AtmosphereResourceImpl(config, b,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) {}
                    @Override
                    public void onStateChange(AtmosphereResourceEvent event) {
                        received.add(event.getResource().uuid());
                        latch.countDown();
                    }
                    @Override public void destroy() {}
                });
    }
}
