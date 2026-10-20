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

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A student whose connection drops misses what the room broadcasts until it
 * reconnects: the classroom's room broadcasters cache it per student, and keep
 * a dropped student for five minutes, so a phone back from the background is
 * written the classmates' turns it missed. Without a cache, a whole turn asked
 * while the student was away never reached it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "atmosphere.admin.enabled=false", "atmosphere.web-transport.enabled=false" })
class ClassroomReconnectCacheTest {

    @Autowired AtmosphereFramework framework;

    @Test
    void aRoomKeepsWhatAStudentAwayMissed() {
        Broadcaster math = framework.getBroadcasterFactory().lookup("/atmosphere/classroom/math", true);
        var cache = assertInstanceOf(UUIDBroadcasterCache.class, math.getBroadcasterConfig().getBroadcasterCache(),
                "the room's broadcaster must cache per student");

        // A student that lost its connection, then a classmate's frame broadcast to the room.
        cache.cacheCandidate(math.getID(), "student-away");
        cache.addToCache(math.getID(), "null", new BroadcastMessage("{\"type\":\"streaming-text\"}"));

        assertEquals(List.<Object>of("{\"type\":\"streaming-text\"}"), cache.retrieveFromCache(math.getID(), "student-away"),
                "the frame broadcast while the student was away is written to it when it comes back");
    }

    @Test
    void aDroppedStudentIsKeptFiveMinutes() {
        assertEquals("300", framework.getAtmosphereConfig()
                .getInitParameter(ApplicationConfig.UUIDBROADCASTERCACHE_CLIENT_IDLETIME));
    }
}
