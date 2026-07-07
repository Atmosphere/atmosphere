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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyDecisionPreferTest {

    @Test
    void preferFactoryCarriesAlternativeAndReason() {
        var decision = PolicyDecision.prefer("scoped credential", "least-privilege");
        var prefer = assertInstanceOf(PolicyDecision.Prefer.class, decision);
        assertEquals("scoped credential", prefer.preferred());
        assertEquals("least-privilege", prefer.reason());
    }

    @Test
    void preferRejectsBlankArguments() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyDecision.Prefer("  ", "why"));
        assertThrows(IllegalArgumentException.class, () -> new PolicyDecision.Prefer("alt", null));
    }

    @Test
    void preferEntryStampsPreferredKeyAndDecision() {
        var req = new AiRequest("standing admin please", "sys", "gpt-4o",
                "user-1", "sess-1", "agent-1", "conv-1", java.util.Map.of(), java.util.List.of());
        var ctx = PolicyContext.preAdmission(req);

        var entry = GovernanceDecisionLog.preferEntry(
                new FakePolicy(), ctx, "scoped credential", "least-privilege", 0.4);

        assertEquals("prefer", entry.decision());
        assertEquals("least-privilege", entry.reason());
        assertEquals("scoped credential",
                entry.contextSnapshot().get(GovernanceDecisionLog.PREFERRED_KEY));
        // The subject identity is still captured so the feedback interceptor can scope it.
        assertEquals("conv-1", entry.contextSnapshot().get("conversation_id"));
    }

    private record FakePolicy() implements GovernancePolicy {
        @Override public String name() { return "advisor"; }
        @Override public String source() { return "code:test"; }
        @Override public String version() { return "1"; }
        @Override public PolicyDecision evaluate(PolicyContext context) {
            return PolicyDecision.admit();
        }
    }
}
