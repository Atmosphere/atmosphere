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
package org.atmosphere.samples.springboot.lowlevel;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.room.RoomAction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OncallRoomAuthorizer} must fail closed on writes. An authorizer that returns true
 * when it cannot identify the caller is worse than none — it reads as protection.
 */
class OncallRoomAuthorizerTest {

    private final OncallRoomAuthorizer authorizer = new OncallRoomAuthorizer();

    private static AtmosphereResource resourceFor(String user) {
        AtmosphereRequest request = Mockito.mock(AtmosphereRequest.class);
        Mockito.when(request.getHeader(OncallRoomAuthorizer.USER_HEADER)).thenReturn(user);
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        Mockito.when(resource.getRequest()).thenReturn(request);
        return resource;
    }

    @Test
    void oncallMayBroadcast() {
        assertTrue(authorizer.authorize(resourceFor("alice"), "incident-42", RoomAction.BROADCAST));
        assertTrue(authorizer.authorize(resourceFor("bob"), "incident-42", RoomAction.SEND_TO));
    }

    @Test
    void offRotaMayNotBroadcast() {
        assertFalse(authorizer.authorize(resourceFor("mallory"), "incident-42", RoomAction.BROADCAST),
                "only the on-call rota may publish to an incident room");
        assertFalse(authorizer.authorize(resourceFor("mallory"), "incident-42", RoomAction.SEND_TO));
    }

    @Test
    void anAbsentIdentityFailsClosedOnWrites() {
        assertFalse(authorizer.authorize(resourceFor(null), "incident-42", RoomAction.BROADCAST),
                "a missing identity must never be treated as authorised to publish");
    }

    @Test
    void readingIsOpenToEveryone() {
        assertTrue(authorizer.authorize(resourceFor(null), "incident-42", RoomAction.JOIN));
        assertTrue(authorizer.authorize(resourceFor("mallory"), "incident-42", RoomAction.LEAVE));
    }
}
