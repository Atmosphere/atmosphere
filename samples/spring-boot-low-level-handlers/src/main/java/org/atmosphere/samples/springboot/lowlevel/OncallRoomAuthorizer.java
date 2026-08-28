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

import java.util.Set;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.room.RoomAction;
import org.atmosphere.room.auth.RoomAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may do what in an incident room. Only the on-call rota may broadcast; anyone may
 * read.
 *
 * <p>The identity here comes from a request header purely so the sample runs without an
 * identity provider. In production this is where your real principal lookup goes — the
 * point of the sample is <em>where</em> the framework asks the question, not how the
 * answer is computed.</p>
 */
public class OncallRoomAuthorizer implements RoomAuthorizer {

    private static final Logger logger = LoggerFactory.getLogger(OncallRoomAuthorizer.class);

    /** Header carrying the caller identity in this sample. */
    public static final String USER_HEADER = "X-Ops-User";

    /** The on-call rota. Anyone outside it is read-only. */
    private static final Set<String> ONCALL = Set.of("alice", "bob");

    @Override
    public boolean authorize(AtmosphereResource resource, String roomName, RoomAction action) {
        String user = resource.getRequest().getHeader(USER_HEADER);

        // Default deny for writes: an absent or unknown identity may read, never publish.
        boolean write = action == RoomAction.BROADCAST || action == RoomAction.SEND_TO;
        boolean allowed = !write || (user != null && ONCALL.contains(user));

        if (!allowed) {
            logger.info("denied {} on room {} for user {}", action, roomName, user);
        }
        return allowed;
    }
}
