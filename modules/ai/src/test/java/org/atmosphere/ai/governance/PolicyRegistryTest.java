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
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail;
import org.atmosphere.ai.guardrails.PiiRedactionGuardrail;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRegistryTest {

    @Test
    void registersBuiltInTypes() {
        var registry = new PolicyRegistry();
        assertTrue(registry.has("pii-redaction"));
        assertTrue(registry.has("cost-ceiling"));
        assertTrue(registry.has("output-length-zscore"));
        assertTrue(registry.has("PII-REDACTION"), "type lookup is case-insensitive");
        assertFalse(registry.has("unknown-type"));
    }

    @Test
    void buildsPiiRedactionWithDefaultMode() {
        var policy = new PolicyRegistry().build(new PolicyRegistry.PolicyDescriptor(
                "my-pii", "pii-redaction", "1.0", "yaml:test", Map.of()));
        var adapter = assertInstanceOf(GuardrailAsPolicy.class, policy);
        assertInstanceOf(PiiRedactionGuardrail.class, adapter.guardrail());
        assertEquals("my-pii", policy.name());
        assertEquals("yaml:test", policy.source());
        assertEquals("1.0", policy.version());
    }

    @Test
    void buildsPiiRedactionInBlockMode() {
        var policy = new PolicyRegistry().build(new PolicyRegistry.PolicyDescriptor(
                "strict-pii", "pii-redaction", "1.0", "yaml:test",
                Map.of("mode", "block")));
        // Block mode produces Deny on the request path where redact would emit Transform.
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("call me at 555-867-5309")));
        assertInstanceOf(PolicyDecision.Deny.class, decision);
    }

    @Test
    void buildsCostCeilingWithBudget() {
        var policy = new PolicyRegistry().build(new PolicyRegistry.PolicyDescriptor(
                "my-budget", "cost-ceiling", "1.0", "yaml:test",
                Map.of("budget-usd", 42.5)));
        var adapter = assertInstanceOf(GuardrailAsPolicy.class, policy);
        assertInstanceOf(CostCeilingGuardrail.class, adapter.guardrail());
    }

    @Test
    void buildsOutputLengthZScoreWithConfig() {
        var policy = new PolicyRegistry().build(new PolicyRegistry.PolicyDescriptor(
                "my-drift", "output-length-zscore", "1.0", "yaml:test",
                Map.of("window-size", 20, "z-threshold", 2.5, "min-samples", 5)));
        var adapter = assertInstanceOf(GuardrailAsPolicy.class, policy);
        assertInstanceOf(OutputLengthZScoreGuardrail.class, adapter.guardrail());
    }

    @Test
    void rejectsUnknownType() {
        var registry = new PolicyRegistry();
        var descriptor = new PolicyRegistry.PolicyDescriptor(
                "x", "bogus-type", "1.0", "yaml:test", Map.of());
        var error = assertThrows(IllegalArgumentException.class, () -> registry.build(descriptor));
        assertTrue(error.getMessage().contains("unknown policy type"));
    }

    @Test
    void allowsCustomTypeRegistration() {
        var registry = new PolicyRegistry();
        registry.register("always-deny", d -> new GovernancePolicy() {
            @Override public String name() { return d.name(); }
            @Override public String source() { return d.source(); }
            @Override public String version() { return d.version(); }
            @Override public PolicyDecision evaluate(PolicyContext ctx) {
                return PolicyDecision.deny("custom");
            }
        });

        var policy = registry.build(new PolicyRegistry.PolicyDescriptor(
                "x", "always-deny", "1.0", "yaml:test", Map.of()));
        var decision = policy.evaluate(PolicyContext.preAdmission(new AiRequest("hi")));
        assertInstanceOf(PolicyDecision.Deny.class, decision);
    }

    @Test
    void descriptorValidatesBlankFields() {
        assertThrows(IllegalArgumentException.class, () ->
                new PolicyRegistry.PolicyDescriptor("", "t", "v", "s", Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new PolicyRegistry.PolicyDescriptor("n", " ", "v", "s", Map.of()));
    }

    @Test
    void descriptorSuppliesDefaultsForBlankVersionAndSource() {
        var d = new PolicyRegistry.PolicyDescriptor("n", "t", null, null, null);
        assertEquals("embedded", d.version());
        assertEquals("yaml:unknown", d.source());
        assertEquals(Map.of(), d.config());
    }
}
