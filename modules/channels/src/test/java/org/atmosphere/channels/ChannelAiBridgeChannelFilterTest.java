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
package org.atmosphere.channels;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for channel-level routing in {@link ChannelAiBridge}.
 * <p>
 * Verifies that the 6-parameter {@code registerAgent()} properly filters
 * agents by allowed channel list and that the 5-parameter overload delegates
 * with an empty list (all channels allowed).
 */
class ChannelAiBridgeChannelFilterTest {

    /**
     * Minimal CommandRouter stub. The {@code route(String, String)} method is
     * required by {@link ChannelAiBridge#registerAgent} for reflective lookup.
     */
    public static class StubRouter {
        private String lastClientId;
        private String lastText;
        private int callCount;

        @SuppressWarnings("unused")
        public NotACommand route(String clientId, String text) {
            lastClientId = clientId;
            lastText = text;
            callCount++;
            return new NotACommand();
        }

        int callCount() { return callCount; }
        String lastClientId() { return lastClientId; }
        String lastText() { return lastText; }
    }

    /** Stub result type whose simpleName is "NotACommand". */
    public static class NotACommand {}

    @BeforeEach
    void setUp() {
        ChannelAiBridge.reset();
    }

    @AfterEach
    void tearDown() {
        ChannelAiBridge.reset();
    }

    // ── Test 1: Empty channel list means all channels allowed ──────────

    @Test
    void agentWithEmptyChannelListReceivesAllChannels() {
        ChannelAiBridge.registerAgent("all-channels", new StubRouter(), null,
                "prompt", null, List.of());

        // Verify the binding was created with an empty allowedChannels list
        var bindings = getBindings();
        assertEquals(1, bindings.size());
        assertTrue(bindings.getFirst().allowedChannels().isEmpty(),
                "Empty channel list should allow all channels");
    }

    // ── Test 2: Slack-only agent does NOT match telegram ─────────────

    @Test
    void agentWithSlackOnlySkipsTelegramChannel() {
        ChannelAiBridge.registerAgent("slack-only", new StubRouter(), null,
                "prompt", null, List.of("slack"));

        var bindings = getBindings();
        assertEquals(1, bindings.size());
        var binding = bindings.getFirst();

        // Allowed channels should contain "slack" (normalized to lowercase)
        assertTrue(binding.allowedChannels().contains("slack"));
        // Should NOT contain "telegram"
        assertFalse(binding.allowedChannels().contains("telegram"),
                "Slack-only agent should not have telegram in allowed channels");
    }

    // ── Test 3: Multi-channel agent receives listed channels ─────────

    @Test
    void agentWithMultipleChannelsContainsAllListed() {
        ChannelAiBridge.registerAgent("multi", new StubRouter(), null,
                "prompt", null, List.of("slack", "telegram"));

        var bindings = getBindings();
        assertEquals(1, bindings.size());
        var binding = bindings.getFirst();

        assertTrue(binding.allowedChannels().contains("slack"));
        assertTrue(binding.allowedChannels().contains("telegram"));
        assertEquals(2, binding.allowedChannels().size());
    }

    // ── Test 4: Two agents with different channel lists ──────────────

    @Test
    void twoAgentsWithDifferentChannelListsRouteCorrectly() {
        ChannelAiBridge.registerAgent("slack-agent", new StubRouter(), null,
                "slack prompt", null, List.of("slack"));
        ChannelAiBridge.registerAgent("telegram-agent", new StubRouter(), null,
                "telegram prompt", null, List.of("telegram"));

        var bindings = getBindings();
        assertEquals(2, bindings.size());

        // First agent: slack only
        var slackBinding = bindings.get(0);
        assertEquals("slack-agent", slackBinding.name());
        assertTrue(slackBinding.allowedChannels().contains("slack"));
        assertFalse(slackBinding.allowedChannels().contains("telegram"));

        // Second agent: telegram only
        var telegramBinding = bindings.get(1);
        assertEquals("telegram-agent", telegramBinding.name());
        assertTrue(telegramBinding.allowedChannels().contains("telegram"));
        assertFalse(telegramBinding.allowedChannels().contains("slack"));
    }

    // ── Test 5: Channel names are case-insensitive ───────────────────

    @Test
    void channelNamesAreNormalizedToLowercase() {
        ChannelAiBridge.registerAgent("mixed-case", new StubRouter(), null,
                "prompt", null, List.of("Slack", "TELEGRAM", "Discord"));

        var bindings = getBindings();
        var allowed = bindings.getFirst().allowedChannels();

        assertTrue(allowed.contains("slack"),
                "'Slack' should be normalized to 'slack'");
        assertTrue(allowed.contains("telegram"),
                "'TELEGRAM' should be normalized to 'telegram'");
        assertTrue(allowed.contains("discord"),
                "'Discord' should be normalized to 'discord'");
        // Original mixed-case should NOT be present
        assertFalse(allowed.contains("Slack"));
        assertFalse(allowed.contains("TELEGRAM"));
    }

    // ── Test 6: Backward compatibility — 5-param registerAgent ───────

    @Test
    void fiveParamRegisterAgentAllowsAllChannels() {
        ChannelAiBridge.registerAgent("legacy", new StubRouter(), null,
                "prompt", null);

        var bindings = getBindings();
        assertEquals(1, bindings.size());
        assertTrue(bindings.getFirst().allowedChannels().isEmpty(),
                "5-param registerAgent should delegate with empty channel list (all allowed)");
    }

    // ── Test: null allowedChannels treated as empty ──────────────────

    @Test
    void nullAllowedChannelsTreatedAsEmpty() {
        ChannelAiBridge.registerAgent("null-channels", new StubRouter(), null,
                "prompt", null, null);

        var bindings = getBindings();
        assertEquals(1, bindings.size());
        assertTrue(bindings.getFirst().allowedChannels().isEmpty(),
                "null allowedChannels should be normalized to empty list");
    }

    // ── Test: registration order is preserved ────────────────────────

    @Test
    void registrationOrderIsPreserved() {
        ChannelAiBridge.registerAgent("first", new StubRouter(), null, null, null);
        ChannelAiBridge.registerAgent("second", new StubRouter(), null, null, null);
        ChannelAiBridge.registerAgent("third", new StubRouter(), null, null, null);

        var bindings = getBindings();
        assertEquals(3, bindings.size());
        assertEquals("first", bindings.get(0).name());
        assertEquals("second", bindings.get(1).name());
        assertEquals("third", bindings.get(2).name());
    }

    // ── Test: reset clears all bindings ──────────────────────────────

    @Test
    void resetClearsAllBindings() {
        ChannelAiBridge.registerAgent("agent", new StubRouter(), null, null, null);
        assertFalse(getBindings().isEmpty());

        ChannelAiBridge.reset();
        assertTrue(getBindings().isEmpty());
    }

    /**
     * Access the package-private agentBindings list via the AgentBinding record.
     * The test class is in the same package as ChannelAiBridge.
     */
    @SuppressWarnings("unchecked")
    private List<ChannelAiBridge.AgentBinding> getBindings() {
        try {
            var field = ChannelAiBridge.class.getDeclaredField("agentBindings");
            field.setAccessible(true);
            return List.copyOf((List<ChannelAiBridge.AgentBinding>) field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to access agentBindings", e);
        }
    }
}
