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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * with an empty list (all channels allowed), and — critically — that the
 * {@code allowedChannels} restriction is <em>enforced at dispatch time</em>:
 * an inbound message on a channel outside an agent's allow-list never reaches
 * that agent's {@code CommandRouter} (Correctness Invariant #6 — a channel is a
 * mutating surface; confinement must hold on the dispatch path, not just in the
 * stored binding).
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

    // ── Dispatch-time enforcement: the skip actually fires, not just stored ──
    //
    // The cases above prove the binding *stores* the right allowedChannels. These
    // drive the private dispatch path (routeCommandOrAi) so a regression that drops
    // the channelId guard — letting an agent answer on a channel it was confined
    // away from — fails the build, even though the stored binding still looks right.

    @Test
    void dispatchSkipsCommandRouterForChannelOutsideAllowList() {
        var router = new StubRouter();
        ChannelAiBridge.registerAgent("slack-only", router, null, "prompt", null, List.of("slack"));

        dispatch(incoming(ChannelType.TELEGRAM, "hello"));

        assertEquals(0, router.callCount(),
                "router must NOT be invoked for an inbound channel outside the agent's allowedChannels");
    }

    @Test
    void dispatchInvokesCommandRouterForAllowedChannel() {
        var router = new StubRouter();
        ChannelAiBridge.registerAgent("slack-only", router, null, "prompt", null, List.of("slack"));

        dispatch(incoming(ChannelType.SLACK, "hello"));

        assertEquals(1, router.callCount(),
                "router MUST be invoked when the inbound channel is in the agent's allowedChannels");
    }

    @Test
    void dispatchInvokesCommandRouterWhenAllowListEmpty() {
        var router = new StubRouter();
        ChannelAiBridge.registerAgent("all-channels", router, null, "prompt", null, List.of());

        dispatch(incoming(ChannelType.DISCORD, "hello"));

        assertEquals(1, router.callCount(),
                "an empty allow-list means every channel dispatches to the agent");
    }

    @Test
    void dispatchRoutesOnlyToTheAgentAllowedOnTheInboundChannel() {
        var slackRouter = new StubRouter();
        var telegramRouter = new StubRouter();
        ChannelAiBridge.registerAgent("slack-agent", slackRouter, null, "prompt", null, List.of("slack"));
        ChannelAiBridge.registerAgent("telegram-agent", telegramRouter, null, "prompt", null, List.of("telegram"));

        dispatch(incoming(ChannelType.TELEGRAM, "hello"));

        assertEquals(0, slackRouter.callCount(),
                "the slack-only agent must be skipped on a telegram message");
        assertEquals(1, telegramRouter.callCount(),
                "the telegram agent must still receive the telegram message (skip is per-binding, not global)");
    }

    /** An inbound message on the given channel. */
    private static IncomingMessage incoming(ChannelType channel, String text) {
        return new IncomingMessage(channel, "user-1", Optional.of("Alice"), text,
                "conv-1", "msg-1", Instant.now());
    }

    /**
     * Drives the bridge's private {@code routeCommandOrAi} dispatch path directly,
     * exercising the dispatch-time channel skip without a live channel adapter,
     * webhook, or AI key. {@code agentBindings} is static, so any bridge instance
     * dispatches against the agents registered in the current test.
     */
    private static void dispatch(IncomingMessage incoming) {
        try {
            var bridge = new ChannelAiBridge(List.of(), new ChannelFilterChain(List.of()));
            var route = ChannelAiBridge.class.getDeclaredMethod("routeCommandOrAi", IncomingMessage.class);
            route.setAccessible(true);
            route.invoke(bridge, incoming);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke routeCommandOrAi", e);
        }
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
