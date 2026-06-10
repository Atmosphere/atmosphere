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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rich-payload approval resolution (P1.1): edited args, structured/free-form respond, fail-safe. */
class ApprovalRegistryRichTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    private CompletableFuture<ApprovalResolution> pending(String id) {
        return registry.registerForResolution(new PendingApproval(
                id, "delete_file", Map.of("path", "/important.txt"),
                "Allow?", "conv-1", Instant.now().plusSeconds(60)));
    }

    private static ApprovalResolution get(CompletableFuture<ApprovalResolution> f)
            throws ExecutionException, InterruptedException {
        return f.get();
    }

    @Test
    void plainApprove() throws Exception {
        var f = pending("apr_a");
        assertEquals(ApprovalRegistry.ResolveResult.RESOLVED, registry.resolve("/__approval/apr_a/approve"));
        var r = get(f);
        assertTrue(r.approved());
        assertFalse(r.hasModifiedArguments());
        assertFalse(r.hasResponsePayload());
        // The legacy boolean view must still see approval.
    }

    @Test
    void approveWithEditedArgumentsWrappedKey() throws Exception {
        var f = pending("apr_b");
        registry.resolve("/__approval/apr_b/approve {\"arguments\":{\"path\":\"/safe.txt\",\"force\":true}}");
        var r = get(f);
        assertTrue(r.approved());
        assertEquals("/safe.txt", r.modifiedArguments().get("path"));
        assertEquals(Boolean.TRUE, r.modifiedArguments().get("force"));
    }

    @Test
    void approveWithEditedArgumentsBareObject() throws Exception {
        var f = pending("apr_c");
        registry.resolve("/__approval/apr_c/approve {\"path\":\"/bare.txt\"}");
        var r = get(f);
        assertEquals("/bare.txt", r.modifiedArguments().get("path"));
    }

    @Test
    void malformedEditedArgsFailsSafeToDeny() throws Exception {
        var f = pending("apr_d");
        registry.resolve("/__approval/apr_d/approve this is not json");
        var r = get(f);
        assertFalse(r.approved(), "a malformed edited-args payload must deny, not run with wrong args");
        assertEquals(ApprovalStrategy.ApprovalOutcome.DENIED, r.outcome());
    }

    @Test
    void respondWithStructuredPayload() throws Exception {
        var f = pending("apr_e");
        registry.resolve("/__approval/apr_e/respond {\"answer\":42}");
        var r = get(f);
        assertTrue(r.approved());
        assertTrue(r.hasResponsePayload());
        assertEquals(42, ((Map<?, ?>) r.responsePayload()).get("answer"));
    }

    @Test
    void respondWithFreeFormText() throws Exception {
        var f = pending("apr_f");
        registry.resolve("/__approval/apr_f/respond the database is read-only today");
        var r = get(f);
        assertTrue(r.approved());
        assertEquals("the database is read-only today", r.responsePayload());
    }

    @Test
    void respondWithoutPayloadFailsSafeToDeny() throws Exception {
        var f = pending("apr_g");
        registry.resolve("/__approval/apr_g/respond");
        var r = get(f);
        assertFalse(r.approved());
    }

    @Test
    void denyHasNoPayloads() throws Exception {
        var f = pending("apr_h");
        registry.resolve("/__approval/apr_h/deny");
        var r = get(f);
        assertFalse(r.approved());
        assertNull(r.modifiedArguments());
        assertNull(r.responsePayload());
    }

    @Test
    void legacyBooleanViewStillWorks() throws Exception {
        var approval = new PendingApproval("apr_i", "t", Map.of(), "m", "c",
                Instant.now().plusSeconds(60));
        var booleanFuture = registry.register(approval);
        registry.resolve("/__approval/apr_i/approve {\"x\":1}");
        assertTrue(booleanFuture.get(), "the boolean register() view must still resolve true on approve");
    }
}
