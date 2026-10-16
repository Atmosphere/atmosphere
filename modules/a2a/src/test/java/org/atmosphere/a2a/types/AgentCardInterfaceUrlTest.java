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
package org.atmosphere.a2a.types;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A2A v1.0.0 requires {@code AgentInterface.url} to be an absolute URL, while
 * the processors build the card with the endpoint path they know. The served
 * card resolves that path against the serving origin.
 */
class AgentCardInterfaceUrlTest {

    private static AgentCard card(List<AgentCardSignature> signatures, String... urls) {
        var interfaces = java.util.Arrays.stream(urls)
                .map(url -> new AgentInterface(url, AgentInterface.JSONRPC, "1.0"))
                .toList();
        return new AgentCard("agent", "desc", interfaces, null, "1.0", null,
                new AgentCapabilities(true, false, null, false),
                null, null, null, null, List.of(), signatures, null);
    }

    @Test
    void aRelativeInterfaceUrlBecomesAbsolute() {
        var served = card(null, "/atmosphere/a2a").withAbsoluteInterfaceUrls("http://localhost:9117");

        assertEquals("http://localhost:9117/atmosphere/a2a", served.supportedInterfaces().get(0).url());
        assertEquals(AgentInterface.JSONRPC, served.supportedInterfaces().get(0).protocolBinding());
        assertEquals("1.0", served.supportedInterfaces().get(0).protocolVersion());
    }

    @Test
    void anAbsoluteUrlIsKeptAndOnlyRelativeOnesChange() {
        var served = card(null, "https://agents.example.com/a2a", "/atmosphere/a2a")
                .withAbsoluteInterfaceUrls("http://localhost:8080");

        assertEquals("https://agents.example.com/a2a", served.supportedInterfaces().get(0).url());
        assertEquals("http://localhost:8080/atmosphere/a2a", served.supportedInterfaces().get(1).url());
    }

    @Test
    void aSignedCardIsServedAsSigned() {
        var signed = card(List.of(new AgentCardSignature("e30", "sig", Map.of())), "/atmosphere/a2a");

        assertSame(signed, signed.withAbsoluteInterfaceUrls("http://localhost:8080"),
                "rewriting a signed card's URLs would break its signature");
    }

    @Test
    void noOriginOrAProtocolRelativeUrlLeavesTheCardAsIs() {
        var relative = card(null, "/atmosphere/a2a");
        assertSame(relative, relative.withAbsoluteInterfaceUrls(null));
        assertSame(relative, relative.withAbsoluteInterfaceUrls(" "));

        var protocolRelative = card(null, "//cdn.example.com/a2a");
        assertSame(protocolRelative, protocolRelative.withAbsoluteInterfaceUrls("http://localhost"));
    }
}
