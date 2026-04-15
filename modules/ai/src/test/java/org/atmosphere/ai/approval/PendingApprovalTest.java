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
package org.atmosphere.ai.approval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingApprovalTest {

    @Test
    void creationWithAllFields() {
        var now = Instant.now();
        var args = Map.<String, Object>of("key", "value");
        var approval = new PendingApproval("ap-1", "sendEmail", args,
                "Approve sending email?", "conv-1", now.plusSeconds(60));

        assertEquals("ap-1", approval.approvalId());
        assertEquals("sendEmail", approval.toolName());
        assertEquals("value", approval.arguments().get("key"));
        assertEquals("Approve sending email?", approval.message());
        assertEquals("conv-1", approval.conversationId());
    }

    @Test
    void argumentsAreDefensivelyCopied() {
        var args = new HashMap<String, Object>();
        args.put("k", "v");
        var approval = new PendingApproval("ap-1", "tool", args,
                "msg", "conv", Instant.now().plusSeconds(60));

        // mutate original map — record should be unaffected
        args.put("k2", "v2");
        assertEquals(1, approval.arguments().size());
    }

    @Test
    void nullArgumentsDefaultToEmptyMap() {
        var approval = new PendingApproval("ap-1", "tool", null,
                "msg", "conv", Instant.now().plusSeconds(60));

        assertNotNull(approval.arguments());
        assertTrue(approval.arguments().isEmpty());
    }

    @Test
    void isExpiredReturnsTrueWhenPastExpiry() {
        var pastExpiry = Instant.now().minusSeconds(60);
        var approval = new PendingApproval("ap-1", "tool", Map.of(),
                "msg", "conv", pastExpiry);

        assertTrue(approval.isExpired());
    }

    @Test
    void isExpiredReturnsFalseWhenBeforeExpiry() {
        var futureExpiry = Instant.now().plusSeconds(3600);
        var approval = new PendingApproval("ap-1", "tool", Map.of(),
                "msg", "conv", futureExpiry);

        assertFalse(approval.isExpired());
    }

    @Test
    void argumentsMapIsUnmodifiable() {
        var approval = new PendingApproval("ap-1", "tool",
                Map.of("k", "v"), "msg", "conv", Instant.now().plusSeconds(60));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> approval.arguments().put("new", "val"));
        assertNotNull(ex);
    }
}
