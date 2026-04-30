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

import org.atmosphere.ai.StructuredOutputParser;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowJsonParserTest {

    private final WorkflowJsonParser parser = new WorkflowJsonParser();

    @Test
    void parsesCanonicalFetchSummarizePlan() {
        String json = """
                {
                  "goal": "Fetch and summarize",
                  "steps": [
                    {
                      "label": "fetch",
                      "toolName": "fetch_emails",
                      "arguments": { "folder": "inbox" },
                      "resultBinding": "emails"
                    },
                    {
                      "label": "summarize",
                      "toolName": "summarize",
                      "arguments": { "input": "@emails" },
                      "resultBinding": "summary"
                    }
                  ]
                }
                """;
        Workflow wf = parser.parse(json);
        assertEquals("Fetch and summarize", wf.goal());
        assertEquals(2, wf.steps().size());

        ToolCallNode fetch = (ToolCallNode) wf.steps().get(0).node();
        assertEquals("fetch_emails", fetch.toolName());
        assertEquals("inbox", fetch.arguments().get("folder"));
        assertEquals("emails", fetch.resultBinding());

        ToolCallNode summarize = (ToolCallNode) wf.steps().get(1).node();
        // The "@emails" string was converted into a SymRef
        Object input = summarize.arguments().get("input");
        SymRef ref = assertInstanceOf(SymRef.class, input);
        assertEquals("emails", ref.ref());
    }

    @Test
    void doubleAtIsUnescapedToLiteralAt() {
        String json = """
                {
                  "goal": "literal preservation",
                  "steps": [
                    {
                      "label": "echo",
                      "toolName": "say",
                      "arguments": { "msg": "@@hello" },
                      "resultBinding": null
                    }
                  ]
                }
                """;
        Workflow wf = parser.parse(json);
        Object msg = ((ToolCallNode) wf.steps().get(0).node()).arguments().get("msg");
        assertEquals("@hello", msg, "@@ prefix should escape to literal @");
        assertFalse(msg instanceof SymRef);
    }

    @Test
    void emptyStepsListIsAccepted() {
        Workflow wf = parser.parse("{ \"goal\": \"do nothing\", \"steps\": [] }");
        assertEquals("do nothing", wf.goal());
        assertTrue(wf.steps().isEmpty());
    }

    @Test
    void missingGoalThrows() {
        assertThrows(StructuredOutputParser.StructuredOutputException.class,
                () -> parser.parse("{ \"steps\": [] }"));
    }

    @Test
    void missingToolNameThrows() {
        String json = """
                {
                  "goal": "g",
                  "steps": [
                    { "label": "x", "arguments": {}, "resultBinding": "y" }
                  ]
                }
                """;
        assertThrows(StructuredOutputParser.StructuredOutputException.class,
                () -> parser.parse(json));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(StructuredOutputParser.StructuredOutputException.class,
                () -> parser.parse("{ not json"));
    }

    @Test
    void schemaInstructionsAreNonEmpty() {
        String schema = parser.schemaInstructions();
        assertNotNull(schema);
        assertFalse(schema.isBlank());
        // The schema is generated from PlanJsonRecord; we just assert
        // it mentions our wire-format field names so any drift
        // (e.g. renaming "steps") fails this test loudly.
        assertTrue(schema.contains("goal"), () -> "schema lacks 'goal' field: " + schema);
        assertTrue(schema.contains("steps"), () -> "schema lacks 'steps' field: " + schema);
    }

    @Test
    void resultBindingMayBeOmitted() {
        String json = """
                {
                  "goal": "fire and forget",
                  "steps": [
                    {
                      "label": "send",
                      "toolName": "send_email",
                      "arguments": { "to": "alice@x" }
                    }
                  ]
                }
                """;
        Workflow wf = parser.parse(json);
        ToolCallNode call = (ToolCallNode) wf.steps().get(0).node();
        assertFalse(call.hasResultBinding());
    }
}
