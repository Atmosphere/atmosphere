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

import static org.junit.jupiter.api.Assertions.*;

class ApprovalRegistryTest {

    private PendingApproval pending(String id, long timeoutSeconds) {
        return new PendingApproval(id, "test_tool", Map.of("arg", "val"),
                "Approve?", "conv-1",
                Instant.now().plusSeconds(timeoutSeconds));
    }

    @Test
    void registerAndResolveApproved() {
        var registry = new ApprovalRegistry();
        var approval = pending("apr_1", 60);
        registry.register(approval);

        assertTrue(registry.tryResolve("/__approval/apr_1/approve"));
    }

    @Test
    void registerAndResolveDenied() {
        var registry = new ApprovalRegistry();
        registry.register(pending("apr_2", 60));

        assertTrue(registry.tryResolve("/__approval/apr_2/deny"));
    }

    @Test
    void tryResolveReturnsFalseForNonApprovalMessage() {
        var registry = new ApprovalRegistry();
        assertFalse(registry.tryResolve("hello"));
        assertFalse(registry.tryResolve(null));
        assertFalse(registry.tryResolve(""));
    }

    @Test
    void tryResolveReturnsTrueForExpiredApproval() {
        var registry = new ApprovalRegistry();
        // Message matches the prefix but no pending entry — still consumed
        assertTrue(registry.tryResolve("/__approval/nonexistent/approve"));
    }

    @Test
    void awaitApprovalReturnsTrue() throws Exception {
        var registry = new ApprovalRegistry();
        var approval = pending("apr_3", 60);
        var future = registry.register(approval);

        // Simulate client approval on another thread
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            registry.tryResolve("/__approval/apr_3/approve");
        });

        assertTrue(registry.awaitApproval(approval, future));
    }

    @Test
    void awaitApprovalReturnsFalse() throws Exception {
        var registry = new ApprovalRegistry();
        var approval = pending("apr_4", 60);
        var future = registry.register(approval);

        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            registry.tryResolve("/__approval/apr_4/deny");
        });

        assertFalse(registry.awaitApproval(approval, future));
    }

    @Test
    void awaitApprovalThrowsOnTimeout() {
        var registry = new ApprovalRegistry();
        var approval = new PendingApproval("apr_5", "tool", Map.of(), "msg", "conv",
                Instant.now().minusSeconds(1)); // already expired
        var future = registry.register(approval);

        assertThrows(ApprovalRegistry.ApprovalTimeoutException.class,
                () -> registry.awaitApproval(approval, future));
    }

    @Test
    void concurrentApprovalsResolveIndependently() throws Exception {
        var registry = new ApprovalRegistry();
        var a1 = pending("apr_a", 60);
        var a2 = pending("apr_b", 60);
        var f1 = registry.register(a1);
        var f2 = registry.register(a2);

        registry.tryResolve("/__approval/apr_a/approve");
        registry.tryResolve("/__approval/apr_b/deny");

        assertTrue(f1.get());
        assertFalse(f2.get());
    }

    @Test
    void isApprovalMessageDetectsPrefix() {
        assertTrue(ApprovalRegistry.isApprovalMessage("/__approval/id/approve"));
        assertFalse(ApprovalRegistry.isApprovalMessage("hello"));
        assertFalse(ApprovalRegistry.isApprovalMessage(null));
    }

    @Test
    void generateIdProducesUniqueValues() {
        var id1 = ApprovalRegistry.generateId();
        var id2 = ApprovalRegistry.generateId();
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("apr_"));
    }

    @Test
    void pendingApprovalExpiry() {
        var expired = new PendingApproval("e1", "tool", Map.of(), "msg", "conv",
                Instant.now().minusSeconds(10));
        assertTrue(expired.isExpired());

        var fresh = pending("f1", 300);
        assertFalse(fresh.isExpired());
    }
}
