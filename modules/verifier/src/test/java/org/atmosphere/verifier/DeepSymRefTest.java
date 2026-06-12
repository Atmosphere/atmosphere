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
package org.atmosphere.verifier;

import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;
import org.atmosphere.verifier.checks.TaintVerifier;
import org.atmosphere.verifier.checks.WellFormednessVerifier;
import org.atmosphere.verifier.execute.RegistryToolDispatcher;
import org.atmosphere.verifier.execute.WorkflowExecutor;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.TaintRule;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.atmosphere.verifier.spi.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link SymRef} buried inside a list or map argument is rebuilt by the
 * parser, scope-checked by well-formedness, taint-tracked, and resolved by
 * the executor exactly like a top-level one — so a flow cannot hide behind
 * a wrapper structure.
 */
class DeepSymRefTest {

    @Test
    void parserConvertsNestedMarkersAtAnyDepth() {
        String json = """
                {
                  "goal": "nested refs",
                  "steps": [
                    {
                      "label": "s",
                      "toolName": "send_email",
                      "arguments": {
                        "body": ["@emails", "literal"],
                        "meta": { "ref": "@summary" }
                      }
                    }
                  ]
                }""";

        Workflow wf = new WorkflowJsonParser().parse(json);
        ToolCallNode call = (ToolCallNode) wf.steps().get(0).node();

        assertInstanceOf(List.class, call.arguments().get("body"));
        List<?> body = (List<?>) call.arguments().get("body");
        assertEquals(new SymRef("emails"), body.get(0));
        assertEquals("literal", body.get(1));

        assertInstanceOf(Map.class, call.arguments().get("meta"));
        Map<?, ?> meta = (Map<?, ?>) call.arguments().get("meta");
        assertEquals(new SymRef("summary"), meta.get("ref"));
    }

    @Test
    void wellFormednessFlagsAnOutOfScopeNestedRef() {
        Workflow wf = new Workflow("nested dangling", List.of(
                new WorkflowStep("s", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("items", List.of(new SymRef("missing"))), null))));

        VerificationResult result = new WellFormednessVerifier().verify(
                wf, PlanFixtures.policyAllowing(PlanFixtures.SEND),
                PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals("steps[0].arguments.items[0]", result.violations().get(0).astPath());
    }

    @Test
    void taintReachesSinkThroughANestedRef() {
        Workflow wf = new Workflow("nested leak", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("body", List.of(new SymRef("emails"))), null))));

        Policy policy = new Policy("p",
                Set.of(PlanFixtures.FETCH, PlanFixtures.SEND),
                List.of(new TaintRule("r", PlanFixtures.FETCH, PlanFixtures.SEND, "body")),
                List.of());

        VerificationResult result = new TaintVerifier().verify(
                wf, policy, PlanFixtures.registryWithFixtureTools());

        assertFalse(result.isOk());
        assertEquals("steps[1].arguments.body[0]", result.violations().get(0).astPath());
    }

    @Test
    void executorResolvesNestedRefs() {
        var captures = new HashMap<String, Map<String, Object>>();
        var executor = new WorkflowExecutor(
                new RegistryToolDispatcher(PlanFixtures.fakeRegistry(captures)));

        Workflow wf = new Workflow("resolve nested", List.of(
                new WorkflowStep("fetch", new ToolCallNode(
                        PlanFixtures.FETCH, Map.of(), "emails")),
                new WorkflowStep("send", new ToolCallNode(
                        PlanFixtures.SEND,
                        Map.of("payload", List.of(new SymRef("emails"))), null))));

        executor.run(wf, Map.of());

        Map<String, Object> sendArgs = captures.get(PlanFixtures.SEND);
        assertInstanceOf(List.class, sendArgs.get("payload"));
        List<?> payload = (List<?>) sendArgs.get("payload");
        assertEquals("result-of-fetch_emails", payload.get(0));
        assertTrue(sendArgs.get("payload") instanceof List<?> l && l.size() == 1);
    }
}
