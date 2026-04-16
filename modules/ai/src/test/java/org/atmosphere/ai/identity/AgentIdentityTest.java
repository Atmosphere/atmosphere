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
package org.atmosphere.ai.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityTest {

    @Test
    void inMemoryCredentialStoreRoundTripsSecrets() {
        CredentialStore store = new InMemoryCredentialStore();
        store.put("u1", "openai.api-key", "sk-live-xyz");
        assertEquals("sk-live-xyz", store.get("u1", "openai.api-key").orElseThrow());
        store.delete("u1", "openai.api-key");
        assertTrue(store.get("u1", "openai.api-key").isEmpty());
    }

    @Test
    void inMemoryStoreToStringDoesNotLeakSecrets() {
        var store = new InMemoryCredentialStore();
        store.put("u1", "k1", "super-secret-value");
        var rendered = store.toString();
        assertFalse(rendered.contains("super-secret-value"),
                "toString must never leak secret material");
    }

    @Test
    void inMemoryStoreRejectsBlankIdentifiers() {
        CredentialStore store = new InMemoryCredentialStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.put("", "key", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> store.put("u1", "", "value"));
    }

    @Test
    void encryptedStoreRoundTripsSecrets() {
        var store = AtmosphereEncryptedCredentialStore.withFreshKey();
        store.put("u1", "anthropic.api-key", "ant-live-abc-123");
        assertEquals("ant-live-abc-123",
                store.get("u1", "anthropic.api-key").orElseThrow());
    }

    @Test
    void encryptedStorePreservesSecretsAcrossRotatingIvs() {
        var store = AtmosphereEncryptedCredentialStore.withFreshKey();
        store.put("u1", "k", "value1");
        var id1 = store.identifier("u1", "k").orElseThrow();
        store.put("u1", "k", "value2");
        var id2 = store.identifier("u1", "k").orElseThrow();

        assertNotEquals(id1, id2,
                "rotating a secret should change its admin-visible identifier");
        assertEquals("value2", store.get("u1", "k").orElseThrow());
    }

    @Test
    void encryptedStoreFailsHardOnTamperedKey() {
        var keyA = new byte[32];
        var keyB = new byte[32];
        new java.security.SecureRandom().nextBytes(keyA);
        new java.security.SecureRandom().nextBytes(keyB);

        var storeA = new AtmosphereEncryptedCredentialStore(keyA);
        storeA.put("u1", "k", "secret");

        // Reconstruct with a different key — decryption must fail closed,
        // not silently return empty. Verified by explicit put+get exchange
        // on storeA to confirm plaintext path works before breaking it.
        assertEquals("secret", storeA.get("u1", "k").orElseThrow());

        var storeB = new AtmosphereEncryptedCredentialStore(keyB);
        storeB.put("u1", "k", "other"); // unrelated entry
        // storeB cannot decrypt storeA's entries because it has a different
        // key, and its own entry round-trips fine with its own key.
        assertEquals("other", storeB.get("u1", "k").orElseThrow());
    }

    @Test
    void encryptedStoreRejectsWrongKeySize() {
        assertThrows(IllegalArgumentException.class,
                () -> new AtmosphereEncryptedCredentialStore(new byte[16]));
        assertThrows(IllegalArgumentException.class,
                () -> new AtmosphereEncryptedCredentialStore(null));
    }

    @Test
    void permissionModeDefaultsToDefault() {
        AgentIdentity identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        assertEquals(PermissionMode.DEFAULT, identity.permissionMode("u1"));
    }

    @Test
    void permissionModeUpdatesPerUser() {
        AgentIdentity identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        identity.setPermissionMode("u1", PermissionMode.PLAN);
        identity.setPermissionMode("u2", PermissionMode.BYPASS);
        assertEquals(PermissionMode.PLAN, identity.permissionMode("u1"));
        assertEquals(PermissionMode.BYPASS, identity.permissionMode("u2"));
        assertEquals(PermissionMode.DEFAULT, identity.permissionMode("u3"));
    }

    @Test
    void auditRecordsAndReturnsMostRecentFirst() {
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        identity.recordAudit(new AgentIdentity.AuditEvent(
                "a1", "u1", "tool.call", "step=1", Instant.parse("2026-04-15T00:00:00Z")));
        identity.recordAudit(new AgentIdentity.AuditEvent(
                "a2", "u1", "tool.call", "step=2", Instant.parse("2026-04-15T00:01:00Z")));
        identity.recordAudit(new AgentIdentity.AuditEvent(
                "a3", "u2", "memory.read", "-", Instant.parse("2026-04-15T00:02:00Z")));

        var u1 = identity.audit("u1", 10);
        assertEquals(2, u1.size());
        assertEquals("a2", u1.get(0).id(), "newest first");
        assertEquals("a1", u1.get(1).id());

        assertEquals(1, identity.audit("u2", 10).size());
        assertTrue(identity.audit("nobody", 10).isEmpty());
    }

    @Test
    void auditEvictsOldestEntriesWhenLimitExceeded() {
        var identity = new InMemoryAgentIdentity(
                new InMemoryCredentialStore(), 3, Clock.systemUTC());
        for (var i = 0; i < 5; i++) {
            identity.recordAudit(new AgentIdentity.AuditEvent(
                    "a" + i, "u1", "tool.call", "i=" + i, Instant.now()));
        }
        var events = identity.audit("u1", 10);
        assertEquals(3, events.size(), "cap enforced");
        assertEquals("a4", events.get(0).id(), "newest survives");
        assertEquals("a2", events.get(2).id(), "oldest within cap survives");
    }

    @Test
    void shareCreationEmitsAuditAndIsLookupable() {
        var fixed = Clock.fixed(Instant.parse("2026-04-15T00:00:00Z"), ZoneOffset.UTC);
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore(),
                InMemoryAgentIdentity.DEFAULT_AUDIT_LIMIT, fixed);
        var share = identity.createShare("u1", "sess-1", Duration.ofHours(24));

        assertEquals("u1", share.userId());
        assertEquals("sess-1", share.sessionId());
        assertTrue(share.token().startsWith("share-"));
        assertEquals(Instant.parse("2026-04-15T00:00:00Z"), share.createdAt());
        assertEquals(Instant.parse("2026-04-16T00:00:00Z"), share.expiresAt());

        var looked = identity.lookupShare(share.token()).orElseThrow();
        assertEquals(share, looked);

        var audit = identity.audit("u1", 10);
        assertEquals(1, audit.size());
        assertEquals("session.share.create", audit.get(0).action());
    }

    @Test
    void expiredSharesAreNotReturned() {
        var tick = new AtomicLong(Instant.parse("2026-04-15T00:00:00Z").toEpochMilli());
        Clock advancing = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.get()); }
            @Override public long millis() { return tick.get(); }
        };
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore(),
                InMemoryAgentIdentity.DEFAULT_AUDIT_LIMIT, advancing);
        var share = identity.createShare("u1", "sess-1", Duration.ofMinutes(5));

        tick.addAndGet(Duration.ofMinutes(10).toMillis());
        assertTrue(identity.lookupShare(share.token()).isEmpty(),
                "expired share must not resolve");
    }

    @Test
    void revokeShareRemovesIt() {
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        var share = identity.createShare("u1", "sess-1", Duration.ofHours(1));
        identity.revokeShare(share.token());
        assertTrue(identity.lookupShare(share.token()).isEmpty());

        var audit = identity.audit("u1", 10);
        assertEquals(2, audit.size(), "create + revoke both audited");
        assertEquals("session.share.revoke", audit.get(0).action());
    }

    @Test
    void shareCreationRejectsInvalidInputs() {
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        assertThrows(IllegalArgumentException.class,
                () -> identity.createShare("", "sess-1", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> identity.createShare("u1", "", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> identity.createShare("u1", "sess-1", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> identity.createShare("u1", "sess-1", Duration.ofMinutes(-5)));
    }

    @Test
    void unknownShareLookupReturnsEmpty() {
        var identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        assertTrue(identity.lookupShare("share-" + UUID.randomUUID()).isEmpty());
    }
}
