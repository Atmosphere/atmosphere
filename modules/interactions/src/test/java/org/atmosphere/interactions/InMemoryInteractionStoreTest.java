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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for the in-memory store: round-trip, incremental append, ordering, and the size bound. */
class InMemoryInteractionStoreTest {

    private final InteractionStore store = new InMemoryInteractionStore();

    private Interaction make(String id, String user, Instant createdAt) {
        return new Interaction(id, null, "conv", "agent", user, "gpt",
                InteractionStatus.RUNNING, false, true, List.of(), null, null, null,
                createdAt, createdAt);
    }

    @Test
    void saveLoadDelete() {
        var i = make("int-a", "alice", Instant.now());
        store.save(i);
        assertEquals("alice", store.load("int-a").orElseThrow().userId());
        assertTrue(store.delete("int-a"));
        assertTrue(store.load("int-a").isEmpty());
        assertFalse(store.delete("int-a"), "deleting unknown returns false");
    }

    @Test
    void appendStepGrowsLogAndIsVisibleOnLoad() {
        store.save(make("int-b", "alice", Instant.now()));
        store.appendStep("int-b", new InteractionStep(0, "text", "hello", null, null, null, Instant.now()));
        store.appendStep("int-b", new InteractionStep(1, "tool-call", null, "weather", null, null, Instant.now()));
        var loaded = store.load("int-b").orElseThrow();
        assertEquals(2, loaded.steps().size());
        assertEquals("weather", loaded.steps().get(1).toolName());
    }

    @Test
    void appendToUnknownInteractionIsNoOp() {
        store.appendStep("int-missing", new InteractionStep(0, "text", "x", null, null, null, Instant.now()));
        assertTrue(store.load("int-missing").isEmpty());
    }

    @Test
    void listFiltersByUserAndOrdersNewestFirst() {
        var t0 = Instant.parse("2026-06-01T00:00:00Z");
        store.save(make("int-old", "alice", t0));
        store.save(make("int-new", "alice", t0.plusSeconds(60)));
        store.save(make("int-bob", "bob", t0.plusSeconds(30)));

        var aliceList = store.list(InteractionQuery.forUser("alice"));
        assertEquals(List.of("int-new", "int-old"),
                aliceList.stream().map(Interaction::id).toList());
        assertEquals(1, store.list(InteractionQuery.forUser("bob")).size());
    }

    @Test
    void enforcesSizeBoundByEvictingOldest() {
        var clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        var bounded = new InMemoryInteractionStore(2, clock);
        var base = Instant.parse("2026-06-01T00:00:00Z");
        bounded.save(make("int-1", "u", base));
        bounded.save(make("int-2", "u", base.plus(Duration.ofSeconds(1))));
        bounded.save(make("int-3", "u", base.plus(Duration.ofSeconds(2))));
        assertEquals(2, bounded.size(), "cap enforced");
        assertTrue(bounded.load("int-1").isEmpty(), "oldest evicted");
        assertTrue(bounded.load("int-3").isPresent(), "newest retained");
    }
}
