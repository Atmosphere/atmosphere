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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The id a room shows its members by, in place of their tracking id.
 *
 * <p>A connection's tracking id (its {@code AtmosphereResource} uuid) is a
 * capability: a close message or a request naming it acts on that connection.
 * What a room broadcasts about a member — the {@code from} of a
 * {@code broadcastReply} room's {@code prompt} frame, a presence frame — names
 * it by this id instead: the first 128 bits of
 * {@code SHA-256("atmosphere.room.member:" + trackingId)}, in lowercase hex.</p>
 *
 * <p>The id cannot be turned back into the tracking id: finding a tracking id
 * that hashes to it is no easier than guessing the tracking id itself, a random
 * UUID by default. The client that holds the tracking id computes the same id
 * (atmosphere.js {@code roomMemberId}), which is how a tab tells its own prompt
 * from another member's without the server telling anyone a tracking id.</p>
 */
public final class RoomMemberIds {

    private static final String PREFIX = "atmosphere.room.member:";
    private static final int BYTES = 16;

    private RoomMemberIds() {
    }

    /**
     * The room member id of the connection whose tracking id is {@code trackingId}.
     *
     * @param trackingId the connection's tracking id
     * @return 32 lowercase hex characters, or {@code null} for a {@code null} tracking id
     */
    public static String of(String trackingId) {
        if (trackingId == null) {
            return null;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((PREFIX + trackingId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, BYTES);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform provides SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
