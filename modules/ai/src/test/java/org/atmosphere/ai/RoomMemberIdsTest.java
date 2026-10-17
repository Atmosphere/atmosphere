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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server's room member id matches atmosphere.js {@code roomMemberId}
 * (room-member-id.test.ts pins the same vectors): a client tells its own
 * prompt from another member's by computing it from its own tracking id.
 */
class RoomMemberIdsTest {

    @Test
    void matchesTheClientsVectors() {
        assertEquals("e634ac1f6f9dcf74f80c3fac3161a9d6", RoomMemberIds.of("a34c8569-22e2-4ede-939b-8172cd7a5e31"));
        assertEquals("08f7d7455edbd74a611646548809f1e8", RoomMemberIds.of("me"));
        assertEquals("0eb6fc24c06d907e085d77ae982004a8", RoomMemberIds.of("\u00e9".repeat(40)));
    }

    @Test
    void aNullTrackingIdHasNoMemberId() {
        assertNull(RoomMemberIds.of(null));
    }
}
