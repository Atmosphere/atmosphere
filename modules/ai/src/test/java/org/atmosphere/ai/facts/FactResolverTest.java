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
package org.atmosphere.ai.facts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactResolverTest {

    @AfterEach
    void resetHolder() {
        FactResolverHolder.reset();
    }

    @Test
    void defaultResolverFillsTimeAndTimezone() {
        var clock = Clock.fixed(Instant.parse("2026-04-18T21:00:00Z"), ZoneId.of("UTC"));
        var resolver = new DefaultFactResolver(clock, ZoneId.of("America/Montreal"));
        var bundle = resolver.resolve(new FactResolver.FactRequest(
                "alice", "sess-1", "agent-1",
                Set.of(FactKeys.TIME_NOW, FactKeys.TIME_TIMEZONE)));

        assertEquals("2026-04-18T21:00:00Z", bundle.get(FactKeys.TIME_NOW).orElse(null));
        assertEquals("America/Montreal", bundle.get(FactKeys.TIME_TIMEZONE).orElse(null));
    }

    @Test
    void defaultResolverEchoesUserIdWhenRequested() {
        var bundle = new DefaultFactResolver().resolve(new FactResolver.FactRequest(
                "alice", "sess-1", "agent-1", Set.of(FactKeys.USER_ID)));
        assertEquals("alice", bundle.get(FactKeys.USER_ID).orElse(null));
    }

    @Test
    void defaultResolverOmitsUnknownKeys() {
        var bundle = new DefaultFactResolver().resolve(new FactResolver.FactRequest(
                "alice", "sess-1", "agent-1",
                Set.of("app.order.id", FactKeys.USER_PLAN_TIER)));
        assertFalse(bundle.get("app.order.id").isPresent(),
                "unknown keys must be silently omitted so a richer resolver can chain");
        assertFalse(bundle.get(FactKeys.USER_PLAN_TIER).isPresent());
    }

    @Test
    void asSystemPromptBlockRendersNonEmptyMap() {
        var bundle = new FactResolver.FactBundle(Map.of(
                FactKeys.USER_LOCALE, "en-CA",
                FactKeys.TIME_NOW, "2026-04-18T21:00:00Z"));
        var block = bundle.asSystemPromptBlock();
        assertTrue(block.startsWith("Grounded facts"),
                "block must lead with the grounded-facts header so the LLM understands");
        assertTrue(block.contains("user.locale: en-CA"));
        assertTrue(block.contains("time.now: 2026-04-18T21:00:00Z"));
    }

    @Test
    void asSystemPromptBlockReturnsEmptyForEmptyMap() {
        assertEquals("", FactResolver.FactBundle.empty().asSystemPromptBlock());
    }

    @Test
    void holderInstallsAndResets() {
        var custom = new DefaultFactResolver();
        FactResolverHolder.install(custom);
        assertSame(custom, FactResolverHolder.get());
        FactResolverHolder.reset();
        assertNotNull(FactResolverHolder.get());
    }
}
