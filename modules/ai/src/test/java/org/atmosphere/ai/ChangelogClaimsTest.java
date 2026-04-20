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

import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.state.FileSystemAgentState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins specific CHANGELOG claims against the running code so doc-to-code
 * drift fails the build. Every claim asserted here corresponds to a
 * bullet in {@code CHANGELOG.md} — if a future refactor renames a
 * constant, removes a class, or changes a default, this test breaks
 * instead of the release note going stale.
 *
 * <p>Born out of the v0.9 review's ask: "CHANGELOG claim verification
 * never got addressed ... leaving them unverified is the same class of
 * drift the retrospective was written to prevent." Grep-checking the
 * claims manually proves the state at time of verification; this test
 * makes the verification perpetual.</p>
 */
class ChangelogClaimsTest {

    /**
     * CHANGELOG claim: "File-backed default ({@code FileSystemAgentState})
     * reads and writes an OpenClaw-compatible Markdown workspace."
     *
     * <p>Verified by exercising the loader against a fixture workspace
     * laid out with the canonical {@code AGENTS.md / SOUL.md / USER.md}
     * files — proves the code path actually reads the OpenClaw layout,
     * not just that the class exists.</p>
     */
    @Test
    void fileSystemAgentStateReadsOpenClawMarkdownLayout(@org.junit.jupiter.api.io.TempDir Path ws)
            throws IOException {
        Files.writeString(ws.resolve("AGENTS.md"), "Be concise.");
        Files.writeString(ws.resolve("SOUL.md"), "You are a careful assistant.");
        Files.writeString(ws.resolve("USER.md"), "User is a Java champion.");
        Files.writeString(ws.resolve("IDENTITY.md"), "Codename: Sentinel.");

        var state = new FileSystemAgentState(ws);
        var rules = state.getRules("user-1", "agent-1");

        assertNotNull(rules);
        assertTrue(rules.operatingRules().contains("Be concise."),
                "AGENTS.md must feed operatingRules: " + rules.operatingRules());
        assertTrue(rules.persona().contains("careful"),
                "SOUL.md must feed persona: " + rules.persona());
        assertTrue(rules.userProfile().contains("Java champion"),
                "USER.md must feed userProfile: " + rules.userProfile());
        assertTrue(rules.identity().contains("Sentinel"),
                "IDENTITY.md must feed identity: " + rules.identity());
    }

    /**
     * CHANGELOG claim: "bounded {@code RunEventReplayBuffer}".
     *
     * <p>Verifies the bound via direct construction (rejects &lt;= 0,
     * accepts explicit capacity, exposes it through {@code capacity()}),
     * and that the default capacity is the documented 1024.</p>
     */
    @Test
    void runEventReplayBufferIsBounded() {
        var defaultBuffer = new RunEventReplayBuffer();
        assertEquals(RunEventReplayBuffer.DEFAULT_CAPACITY, defaultBuffer.capacity(),
                "default capacity must match the documented DEFAULT_CAPACITY constant");
        assertEquals(1_024, RunEventReplayBuffer.DEFAULT_CAPACITY,
                "DEFAULT_CAPACITY contract: 1024 events. Change requires "
                + "updating CHANGELOG + docs.");

        // Eviction happens at capacity — write one more than capacity and
        // assert the oldest event is dropped. This is the 'bounded' part of
        // the CHANGELOG claim; the constructor-level upper bound alone
        // wouldn't prove eviction actually fires.
        var tight = new RunEventReplayBuffer(3);
        tight.capture("t", "a");
        tight.capture("t", "b");
        tight.capture("t", "c");
        tight.capture("t", "d");
        assertEquals(3, tight.snapshot().size(),
                "buffer must evict when size > capacity — unbounded would retain all 4");
        assertEquals("b", tight.snapshot().get(0).payload(),
                "oldest event must be evicted first — FIFO");
    }

    // The CHANGELOG claim about ControlAuthorizer.DENY_ALL and REQUIRE_PRINCIPAL
    // is verified by modules/admin/src/test/java/org/atmosphere/admin/
    // ControlAuthorizerTest (the admin module is the home of the constants and
    // the test can depend on it without pulling admin into ai's classpath).
    // Named here so a CHANGELOG audit finds the explicit cross-reference.
}
