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
import org.atmosphere.verifier.spi.PlanVerifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAstRoundtripTest {

    @Test
    void recordsCarryTheirComponents() {
        ToolCallNode node = new ToolCallNode(
                "fetch",
                Map.of("folder", "inbox", "input", new SymRef("emails")),
                "result");
        assertEquals("fetch", node.toolName());
        assertEquals("result", node.resultBinding());
        assertEquals(new SymRef("emails"), node.arguments().get("input"));
        assertTrue(node.hasResultBinding());
    }

    @Test
    void argumentsMapIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("k", "v");
        ToolCallNode node = new ToolCallNode("t", mutable, "out");

        // Mutate the source — record's view should be unaffected
        mutable.put("k2", "v2");
        assertEquals(1, node.arguments().size(),
                "arguments map was not defensively copied");
    }

    @Test
    void stepsListIsDefensivelyCopied() {
        List<WorkflowStep> mutable = new ArrayList<>();
        mutable.add(new WorkflowStep("a", new ToolCallNode("t", Map.of(), null)));
        Workflow wf = new Workflow("g", mutable);

        mutable.add(new WorkflowStep("b", new ToolCallNode("t", Map.of(), null)));
        assertEquals(1, wf.steps().size(),
                "steps list was not defensively copied");
    }

    @Test
    void blankFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SymRef(""));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolCallNode("", Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowStep("", new ToolCallNode("t", Map.of(), null)));
        assertThrows(IllegalArgumentException.class,
                () -> new Workflow(" ", List.of()));
    }

    @Test
    void hasResultBindingDistinguishesFireAndForget() {
        ToolCallNode bound = new ToolCallNode("t", Map.of(), "x");
        ToolCallNode nullBinding = new ToolCallNode("t", Map.of(), null);
        ToolCallNode blankBinding = new ToolCallNode("t", Map.of(), "  ");
        assertTrue(bound.hasResultBinding());
        assertNotEquals(true, nullBinding.hasResultBinding());
        assertNotEquals(true, blankBinding.hasResultBinding());
    }

    @Test
    void serviceLoaderDiscoversBuiltInVerifiers() {
        // META-INF/services drift is the cheapest defect class to prevent;
        // assert both Phase 1 verifiers actually load via ServiceLoader.
        var found = new java.util.HashSet<String>();
        for (PlanVerifier v : ServiceLoader.load(PlanVerifier.class)) {
            found.add(v.name());
            assertNotNull(v.name(), "Verifier returned null name");
        }
        assertTrue(found.contains("allowlist"),
                () -> "AllowlistVerifier missing from ServiceLoader; found: " + found);
        assertTrue(found.contains("well-formed"),
                () -> "WellFormednessVerifier missing from ServiceLoader; found: " + found);
    }
}
