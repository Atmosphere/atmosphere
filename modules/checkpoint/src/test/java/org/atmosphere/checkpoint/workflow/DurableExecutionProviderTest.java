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
package org.atmosphere.checkpoint.workflow;

import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable-execution SPI (P1.2): the in-memory reference adapter runs a workflow. */
class DurableExecutionProviderTest {

    private interface Body {
        StepOutcome<String> apply(String state);
    }

    private static WorkflowStep<String> step(String name, Body body) {
        return new WorkflowStep<>() {
            @Override public String name() {
                return name;
            }

            @Override public StepOutcome<String> execute(String state) {
                return body.apply(state);
            }
        };
    }

    @Test
    void resolveReturnsTheInMemoryReferenceWhenNoExternalEngine() {
        var provider = DurableExecutionProvider.resolve();
        assertTrue(provider.isAvailable());
        assertEquals("in-memory", provider.name(),
                "with no external engine registered, the in-tree reference is used");
    }

    @Test
    void inMemoryProviderRunsAWorkflowToCompletion() {
        var store = new InMemoryCheckpointStore();
        store.start();
        var workflow = new Workflow<>("p12-demo", "coord-p12", List.of(
                step("ingest", s -> StepOutcome.advance(s + "-ingested")),
                step("publish", s -> StepOutcome.done(s + "-published"))), store);

        WorkflowResult<String> result = new InMemoryDurableExecutionProvider().run(workflow, "doc");

        var completed = assertInstanceOf(WorkflowResult.Completed.class, result,
                "a two-step workflow with a done() outcome completes");
        assertEquals("doc-ingested-published", completed.finalState());
    }
}
