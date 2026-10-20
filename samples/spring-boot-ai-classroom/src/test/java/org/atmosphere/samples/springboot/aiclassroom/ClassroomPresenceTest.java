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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.RoomMemberIds;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member that leaves must leave the room, presence frame included, even when
 * its request is already out of scope by the time {@code @Disconnect} runs.
 *
 * <p>The re-test of 2026-10-20 reloaded a page mid-answer and got
 * {@code IllegalStateException: Request object no longer valid} from
 * {@code AiClassroom.roomOf} inside {@code onDisconnect}: the member never
 * logged "left room" and no leave frame went out.</p>
 */
class ClassroomPresenceTest {

    @Test
    void aMemberWhoseRequestIsGoneStillLeavesItsRoom() {
        var members = new ArrayList<AtmosphereResource>();
        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("/atmosphere/classroom/math");
        when(broadcaster.getAtmosphereResources()).thenReturn(members);

        var request = mock(AtmosphereRequest.class);
        when(request.getAttribute(AiEndpointHandler.PATH_PARAM_ATTRIBUTE_PREFIX + "room"))
                .thenReturn("math");
        var student = mock(AtmosphereResource.class);
        when(student.uuid()).thenReturn("3f9c2a7b-e41d-4c0a-9e15-000000000001");
        when(student.getBroadcaster()).thenReturn(broadcaster);
        when(student.getRequest()).thenReturn(request);
        var classmate = mock(AtmosphereResource.class);
        members.add(classmate);
        members.add(student);

        var classroom = new AiClassroom();
        classroom.onReady(student);

        // The connection is torn down while @Disconnect runs.
        when(student.getRequest()).thenThrow(new IllegalStateException(
                "Request object no longer valid. This object has been cancelled"));
        var event = mock(AtmosphereResourceEvent.class);
        when(event.getResource()).thenReturn(student);

        assertDoesNotThrow(() -> classroom.onDisconnect(event));

        var frames = ArgumentCaptor.forClass(Object.class);
        verify(broadcaster, atLeastOnce()).broadcast(frames.capture());
        List<String> sent = frames.getAllValues().stream()
                .map(f -> String.valueOf(((RawMessage) f).message()))
                .toList();
        assertEquals(String.format(
                        "{\"type\":\"presence\",\"action\":\"leave\",\"memberId\":\"%s\",\"count\":1}",
                        RoomMemberIds.of(student.uuid())),
                sent.get(sent.size() - 1),
                "the room is told the member left");
    }
}
