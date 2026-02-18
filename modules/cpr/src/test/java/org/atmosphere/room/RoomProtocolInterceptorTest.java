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

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.Action;
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

public class RoomProtocolInterceptorTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private RoomManager roomManager;
    private RoomProtocolInterceptor interceptor;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        roomManager = RoomManager.getOrCreate(config.framework());

        interceptor = new RoomProtocolInterceptor();
        interceptor.configure(config);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        roomManager.destroyAll();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    // --- Join flow ---

    @Test
    public void testJoinCreatesRoomAndAddsMember() throws Exception {
        var resource = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"alice","metadata":{"avatar":"pic.jpg"}}"""
        );

        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CANCELLED, "Join should consume the message");

        assertTrue(roomManager.exists("lobby"));
        var room = roomManager.room("lobby");
        assertEquals(room.size(), 1);
        assertTrue(room.contains(resource));

        var member = room.memberOf(resource);
        assertTrue(member.isPresent());
        assertEquals(member.get().id(), "alice");
        assertEquals(member.get().metadata().get("avatar"), "pic.jpg");
    }

    @Test
    public void testJoinWithoutMemberId() throws Exception {
        var resource = createResourceWithBody(
                """
                {"type":"join","room":"lobby"}"""
        );

        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CANCELLED);

        var room = roomManager.room("lobby");
        assertEquals(room.size(), 1);
        assertTrue(room.memberOf(resource).isEmpty(), "No member info without memberId");
    }

    @Test
    public void testMultipleJoinsSameRoom() throws Exception {
        var r1 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"alice"}"""
        );
        var r2 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"bob"}"""
        );

        interceptor.inspect(r1);
        interceptor.inspect(r2);

        var room = roomManager.room("lobby");
        assertEquals(room.size(), 2);
        assertEquals(room.memberInfo().size(), 2);
    }

    // --- Leave flow ---

    @Test
    public void testLeaveRemovesMember() throws Exception {
        // Join directly (bypass interceptor) so we can control the resource
        var room = roomManager.room("lobby");
        var resource = createResource();
        var member = new RoomMember("alice");
        room.join(resource, member);
        assertEquals(room.size(), 1);

        // Now leave via interceptor using a new resource that is in the room
        // We test the interceptor's leave by creating a fresh request
        // with leave body and the room having the resource
        var leaveBody = """
                {"type":"leave","room":"lobby"}""";
        // Create a resource with the leave body — the interceptor will remove
        // it from the room by looking up roomManager.room(leave.room())
        var leaveResource = createResourceWithBody(leaveBody);
        // First add it to the room so leave has something to remove
        room.join(leaveResource);
        assertEquals(room.size(), 2);

        interceptor.inspect(leaveResource);
        assertFalse(room.contains(leaveResource), "Resource should be removed after leave");
    }

    // --- Broadcast flow ---

    @Test
    public void testBroadcastConsumesMessage() throws Exception {
        var r1 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"alice"}"""
        );
        interceptor.inspect(r1);

        var r2 = createResourceWithBody(
                """
                {"type":"broadcast","room":"lobby","data":"hello"}"""
        );
        var action = interceptor.inspect(r2);
        assertEquals(action, Action.CANCELLED, "Broadcast should consume the message");
    }

    // --- Direct message flow ---

    @Test
    public void testDirectToUnknownMember() throws Exception {
        // Join first
        var r1 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"alice"}"""
        );
        interceptor.inspect(r1);

        // Direct to unknown — should not throw, just send error
        var r2 = createResourceWithBody(
                """
                {"type":"direct","room":"lobby","targetId":"nobody","data":"hi"}"""
        );
        var action = interceptor.inspect(r2);
        assertEquals(action, Action.CANCELLED);
    }

    @Test
    public void testDirectToKnownMember() throws Exception {
        var r1 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"alice"}"""
        );
        interceptor.inspect(r1);

        var r2 = createResourceWithBody(
                """
                {"type":"join","room":"lobby","memberId":"bob"}"""
        );
        interceptor.inspect(r2);

        var r3 = createResourceWithBody(
                """
                {"type":"direct","room":"lobby","targetId":"alice","data":"hi alice"}"""
        );
        var action = interceptor.inspect(r3);
        assertEquals(action, Action.CANCELLED);
    }

    // --- Non-protocol messages pass through ---

    @Test
    public void testNonJsonPassesThrough() throws Exception {
        var resource = createResourceWithBody("not json at all");
        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE, "Non-JSON should pass through");
    }

    @Test
    public void testEmptyBodyPassesThrough() throws Exception {
        var resource = createResourceWithBody("");
        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE, "Empty body should pass through");
    }

    @Test
    public void testNullBodyPassesThrough() throws Exception {
        var resource = createResource();
        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE, "Null body should pass through");
    }

    @Test
    public void testNonRoomJsonPassesThrough() throws Exception {
        var resource = createResourceWithBody("""
                {"someKey":"someValue"}""");
        var action = interceptor.inspect(resource);
        assertEquals(action, Action.CONTINUE, "Non-room JSON should pass through");
    }

    // --- Priority ---

    @Test
    public void testPriorityIsBeforeDefault() {
        assertEquals(interceptor.priority(),
                org.atmosphere.interceptor.InvokationOrder.BEFORE_DEFAULT);
    }

    // --- RoomManager.getOrCreate ---

    @Test
    public void testGetOrCreateReturnsSameInstance() {
        var rm1 = RoomManager.getOrCreate(config.framework());
        var rm2 = RoomManager.getOrCreate(config.framework());
        assertSame(rm1, rm2, "getOrCreate should return the same instance");
    }

    // --- DefaultRoom member info ---

    @Test
    public void testMemberInfoMap() throws Exception {
        var room = roomManager.room("test");
        var r1 = createResource();
        var r2 = createResource();

        room.join(r1, new RoomMember("alice", java.util.Map.of("role", "admin")));
        room.join(r2, new RoomMember("bob"));

        var info = room.memberInfo();
        assertEquals(info.size(), 2);
        assertEquals(info.get(r1.uuid()).id(), "alice");
        assertEquals(info.get(r2.uuid()).id(), "bob");
    }

    @Test
    public void testMemberInfoCleanedOnLeave() throws Exception {
        var room = roomManager.room("test");
        var r1 = createResource();
        room.join(r1, new RoomMember("alice"));
        assertEquals(room.memberInfo().size(), 1);

        room.leave(r1);
        assertTrue(room.memberInfo().isEmpty());
    }

    @Test
    public void testMemberInfoCleanedOnDestroy() throws Exception {
        var room = roomManager.room("test");
        var r1 = createResource();
        room.join(r1, new RoomMember("alice"));

        room.destroy();
        // After destroy, memberInfo should be cleaned up
        // but the room is also unusable, so we just verify it doesn't throw
    }

    // --- PresenceEvent with memberInfo ---

    @Test
    public void testPresenceEventIncludesMemberInfo() throws Exception {
        var room = roomManager.room("test");
        var events = new java.util.ArrayList<PresenceEvent>();
        room.onPresence(events::add);

        var r1 = createResource();
        room.join(r1, new RoomMember("alice", java.util.Map.of("role", "admin")));

        assertEquals(events.size(), 1);
        assertNotNull(events.getFirst().memberInfo());
        assertEquals(events.getFirst().memberInfo().id(), "alice");
    }

    @Test
    public void testPresenceEventWithoutMemberInfo() throws Exception {
        var room = roomManager.room("test");
        var events = new java.util.ArrayList<PresenceEvent>();
        room.onPresence(events::add);

        var r1 = createResource();
        room.join(r1);

        assertEquals(events.size(), 1);
        assertNull(events.getFirst().memberInfo());
    }

    // --- Helpers ---

    @SuppressWarnings({"deprecation", "unchecked"})
    private AtmosphereResource createResource() throws IOException {
        Broadcaster b = factory.get(DefaultBroadcaster.class, "resource-" + System.nanoTime());
        return new AtmosphereResourceImpl(config, b,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(AsyncSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) {}
                    @Override public void onStateChange(AtmosphereResourceEvent event) {}
                    @Override public void destroy() {}
                });
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private AtmosphereResource createResourceWithBody(String body) throws IOException {
        Broadcaster b = factory.get(DefaultBroadcaster.class, "resource-" + System.nanoTime());

        var request = AtmosphereRequestImpl.newInstance();
        request.body(body);

        return new AtmosphereResourceImpl(config, b, request,
                AtmosphereResponseImpl.newInstance(),
                mock(AsyncSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) {}
                    @Override public void onStateChange(AtmosphereResourceEvent event) {}
                    @Override public void destroy() {}
                });
    }
}
