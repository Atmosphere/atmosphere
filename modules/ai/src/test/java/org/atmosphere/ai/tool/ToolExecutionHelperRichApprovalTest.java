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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalResolution;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rich HITL payloads through {@link ToolExecutionHelper#executeWithApproval}
 * (P1.1): approve-with-edited-args runs the tool with the reviewer's arguments;
 * respond returns the reviewer's value without running the tool.
 */
class ToolExecutionHelperRichApprovalTest {

    /** Strategy that returns a fixed resolution — stands in for a human reviewer. */
    private static ApprovalStrategy fixed(ApprovalResolution resolution) {
        return new ApprovalStrategy() {
            @Override
            public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
                return resolution.outcome();
            }

            @Override
            public ApprovalResolution awaitApprovalDetailed(PendingApproval approval,
                                                            StreamingSession session) {
                return resolution;
            }
        };
    }

    @Test
    void approveWithEditedArgumentsRunsToolWithNewArgs() {
        var seen = new AtomicReference<Map<String, Object>>();
        var tool = ToolDefinition.builder("delete_file", "deletes a file")
                .requiresApproval("Allow?")
                .executor(args -> {
                    seen.set(args);
                    return "deleted " + args.get("path");
                })
                .build();

        var result = ToolExecutionHelper.executeWithApproval(
                "delete_file", tool, Map.of("path", "/important.txt"),
                new CollectingSession("t1"),
                fixed(ApprovalResolution.approveWithArguments(Map.of("path", "/safe.txt"))));

        assertTrue(seen.get() != null && "/safe.txt".equals(seen.get().get("path")),
                "the tool must run with the reviewer's edited arguments, not the model's");
        assertTrue(result.contains("/safe.txt"));
    }

    @Test
    void respondReturnsReviewerValueWithoutRunningTool() {
        var ran = new AtomicBoolean(false);
        var tool = ToolDefinition.builder("query_db", "runs a query")
                .requiresApproval("Allow?")
                .executor(args -> {
                    ran.set(true);
                    return "rows";
                })
                .build();

        var structured = ToolExecutionHelper.executeWithApproval(
                "query_db", tool, Map.of("sql", "select 1"),
                new CollectingSession("t2"),
                fixed(ApprovalResolution.respond(Map.of("status", "read-only"))));

        assertFalse(ran.get(), "respond must NOT run the tool — the reviewer answered on its behalf");
        assertTrue(structured.contains("read-only"), "structured response serialized to JSON: " + structured);
    }

    @Test
    void respondFreeFormTextIsReturnedVerbatim() {
        var tool = ToolDefinition.builder("ask", "asks")
                .requiresApproval("Allow?")
                .executor(args -> "should not run")
                .build();

        var result = ToolExecutionHelper.executeWithApproval(
                "ask", tool, Map.of(), new CollectingSession("t3"),
                fixed(ApprovalResolution.respond("the answer is 42")));

        assertTrue(result.equals("the answer is 42"), "free-form text returned verbatim: " + result);
    }

    @Test
    void denyDoesNotRunTool() {
        var ran = new AtomicBoolean(false);
        var tool = ToolDefinition.builder("delete", "deletes")
                .requiresApproval("Allow?")
                .executor(args -> {
                    ran.set(true);
                    return "x";
                })
                .build();

        var result = ToolExecutionHelper.executeWithApproval(
                "delete", tool, Map.of(), new CollectingSession("t4"),
                fixed(ApprovalResolution.deny()));

        assertFalse(ran.get());
        assertTrue(result.contains("cancelled"));
    }
}
