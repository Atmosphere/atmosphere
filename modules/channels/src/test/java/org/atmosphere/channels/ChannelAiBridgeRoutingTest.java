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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#34): with several agents bound to one channel, every
 * free-text message went to whichever agent registered first — registration
 * order was effectively the routing policy. Messages can now address an
 * agent explicitly ({@code @name ...} / {@code name: ...}), a configured
 * default agent catches unaddressed traffic, and the first-registered
 * fallback is flagged so the bridge warns instead of silently choosing.
 */
class ChannelAiBridgeRoutingTest {

    private static ChannelAiBridge.AgentBinding binding(String name) {
        return new ChannelAiBridge.AgentBinding(name, null, null, null, null, List.of());
    }

    private final List<ChannelAiBridge.AgentBinding> agents =
            List.of(binding("research"), binding("support"));

    @Test
    void atMentionRoutesToTheNamedAgentAndStripsTheAddress() {
        var route = ChannelAiBridge.routeFreeText("@support my order is late", agents, "");

        assertEquals("support", route.binding().name(),
                "an explicit @mention must beat registration order");
        assertEquals("my order is late", route.text());
        assertFalse(route.ambiguous());
    }

    @Test
    void colonAddressRoutesCaseInsensitively() {
        var route = ChannelAiBridge.routeFreeText("Support: hello there", agents, "");

        assertEquals("support", route.binding().name());
        assertEquals("hello there", route.text());
    }

    @Test
    void mentionMustEndAtAWordBoundary() {
        var researcher = List.of(binding("research"), binding("researcher"));
        var route = ChannelAiBridge.routeFreeText("@researcher dig into this", researcher, "");

        assertEquals("researcher", route.binding().name(),
                "agent 'research' must not claim a message addressed to '@researcher'");
    }

    @Test
    void configuredDefaultAgentCatchesUnaddressedTraffic() {
        var route = ChannelAiBridge.routeFreeText("what's the weather", agents, "support");

        assertEquals("support", route.binding().name(),
                "the configured default agent must receive unaddressed free text");
        assertFalse(route.ambiguous());
    }

    @Test
    void soleEligibleAgentNeedsNoAddressing() {
        var route = ChannelAiBridge.routeFreeText("hello",
                List.of(binding("research")), "");

        assertEquals("research", route.binding().name());
        assertFalse(route.ambiguous());
    }

    @Test
    void unaddressedTrafficWithoutADefaultIsFlaggedAmbiguous() {
        var route = ChannelAiBridge.routeFreeText("hello", agents, "");

        assertEquals("research", route.binding().name(),
                "first-registered stays the fallback for compatibility");
        assertTrue(route.ambiguous(),
                "the silent registration-order policy must at least be flagged "
                + "so the bridge can warn");
    }
}
