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
package org.atmosphere.interactions;

/**
 * Filter for {@link InteractionStore#list(InteractionQuery)}. A {@code null}
 * field matches everything; {@link #limit} caps the result count (a
 * non-positive limit applies {@link #DEFAULT_LIMIT} so a list call can never
 * return an unbounded result set — Correctness Invariant #3, Backpressure).
 *
 * @param userId         restrict to this owner, or {@code null} for any
 * @param conversationId restrict to this conversation chain, or {@code null} for any
 * @param status         restrict to this status, or {@code null} for any
 * @param limit          maximum rows to return; clamped to {@code [1, MAX_LIMIT]}
 */
public record InteractionQuery(
        String userId,
        String conversationId,
        InteractionStatus status,
        int limit) {

    /** Applied when a caller passes a non-positive limit. */
    public static final int DEFAULT_LIMIT = 100;

    /** Hard ceiling on a single list call. */
    public static final int MAX_LIMIT = 1000;

    public InteractionQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        } else if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    /** Query all interactions owned by the given principal. */
    public static InteractionQuery forUser(String userId) {
        return new InteractionQuery(userId, null, null, DEFAULT_LIMIT);
    }

    /** Query all interactions in the given conversation chain. */
    public static InteractionQuery forConversation(String conversationId) {
        return new InteractionQuery(null, conversationId, null, DEFAULT_LIMIT);
    }

    /** Whether the given interaction matches this query's non-null fields. */
    public boolean matches(Interaction interaction) {
        if (userId != null && !userId.equals(interaction.userId())) {
            return false;
        }
        if (conversationId != null && !conversationId.equals(interaction.conversationId())) {
            return false;
        }
        return status == null || status == interaction.status();
    }
}
