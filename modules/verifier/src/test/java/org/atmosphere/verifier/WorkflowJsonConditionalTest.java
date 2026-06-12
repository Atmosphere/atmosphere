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

import org.atmosphere.verifier.ast.ConditionalNode;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowJsonConditionalTest {

    private final WorkflowJsonParser parser = new WorkflowJsonParser();

    @Test
    void parsesConditionalStepWithBothArms() {
        String json = """
                {
                  "goal": "branch",
                  "steps": [
                    {
                      "label": "decide",
                      "condition": "score >= 80",
                      "then": [
                        { "label": "hi", "toolName": "send_email",
                          "arguments": { "body": "hi" } }
                      ],
                      "otherwise": [
                        { "label": "bye", "toolName": "summarize", "arguments": {} }
                      ]
                    }
                  ]
                }""";

        Workflow wf = parser.parse(json);
        assertInstanceOf(ConditionalNode.class, wf.steps().get(0).node());
        ConditionalNode cond = (ConditionalNode) wf.steps().get(0).node();

        assertEquals("score >= 80", cond.predicate());
        assertEquals(1, cond.thenSteps().size());
        assertEquals(1, cond.elseSteps().size());
        assertInstanceOf(ToolCallNode.class, cond.thenSteps().get(0).node());
        assertEquals("send_email",
                ((ToolCallNode) cond.thenSteps().get(0).node()).toolName());
        assertEquals("summarize",
                ((ToolCallNode) cond.elseSteps().get(0).node()).toolName());
    }

    @Test
    void conditionalWithoutOtherwiseHasEmptyElseArm() {
        String json = """
                {
                  "goal": "branch",
                  "steps": [
                    {
                      "label": "decide",
                      "condition": "status == approved",
                      "then": [
                        { "label": "go", "toolName": "fetch_emails", "arguments": {} }
                      ]
                    }
                  ]
                }""";

        Workflow wf = parser.parse(json);
        ConditionalNode cond = (ConditionalNode) wf.steps().get(0).node();
        assertEquals(1, cond.thenSteps().size());
        assertTrue(cond.elseSteps().isEmpty());
    }
}
