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
import static org.testng.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class RoomTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private RoomManager roomManager;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        roomManager = RoomManager.create(config.framework());
    }

    @AfterMethod
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
        assertEquals(room.name(), "lobby");
        assertTrue(room.isEmpty());
        assertEquals(room.size(), 0);
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
        assertEquals(room.size(), 1);
        assertTrue(room.contains(ar));

        room.leave(ar);
        assertEquals(room.size(), 0);
        assertFalse(room.contains(ar));
    }

    @Test
    public void testMultipleMembers() throws Exception {
        Room room = roomManager.room("test");
        var ar1 = createResource();
        var ar2 = createResource();

        room.join(ar1).join(ar2);
        assertEquals(room.size(), 2);
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

        assertEquals(received.size(), 2, "Both resources should have received the message");
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

        assertEquals(received.size(), 1, "Only receiver should get the message");
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

        assertEquals(received.size(), 1, "Only target should receive direct message");
    }

    // --- Presence events ---

    @Test
    public void testPresenceJoinEvent() throws Exception {
        Room room = roomManager.room("test");
        var events = new ArrayList<PresenceEvent>();
        room.onPresence(events::add);

        var ar = createResource();
        room.join(ar);

        assertEquals(events.size(), 1);
        assertEquals(events.getFirst().type(), PresenceEvent.Type.JOIN);
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

        assertEquals(events.size(), 1);
        assertEquals(events.getFirst().type(), PresenceEvent.Type.LEAVE);
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
        assertEquals(roomManager.all().size(), 3);
        assertEquals(roomManager.count(), 3);
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
        assertEquals(roomManager.count(), 0);
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void testJoinDestroyedRoomThrows() throws Exception {
        Room room = roomManager.room("temp");
        room.destroy();
        room.join(createResource());
    }

    // --- PresenceEvent record ---

    @Test
    public void testPresenceEventRecord() throws Exception {
        Room room = roomManager.room("test");
        var ar = createResource();
        var event = new PresenceEvent(PresenceEvent.Type.JOIN, room, ar);

        assertEquals(event.type(), PresenceEvent.Type.JOIN);
        assertSame(event.room(), room);
        assertSame(event.member(), ar);
    }

    // --- Helpers ---

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
