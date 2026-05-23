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

import org.atmosphere.checkpoint.SqliteCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Cold-restart resume test: hibernates a workflow, closes the
 * {@link SqliteCheckpointStore} (simulating the JVM going away), opens
 * a fresh store on the same file, instantiates a fresh {@link Workflow}
 * over the same coordination, and asserts execution resumes at the
 * next un-completed step — using the persisted state from the prior
 * "process".
 *
 * <p>This is the contract that distinguishes this primitive from an
 * in-memory state machine: a JVM crash between steps does not lose
 * progress, and the dormant period holds no thread.</p>
 */
class WorkflowSqliteResumeTest {

    @Test
    void workflowResumesAcrossSqliteStoreClose(@TempDir Path tmp) {
        var dbFile = tmp.resolve("workflow-resume.db");
        var coord = "coord-" + UUID.randomUUID();

        // --- Process 1: hibernate at step 2 ---
        var store1 = new SqliteCheckpointStore(dbFile);
        store1.start();
        try {
            var first = new Workflow<>("resume-across-restart", coord,
                    List.of(
                            stringStep("ingest", s -> StepOutcome.advance(s + "→ingested")),
                            stringStep("wait", s -> StepOutcome.hibernate(s + "→hibernated")),
                            stringStep("publish", s -> StepOutcome.done(s + "→published"))),
                    store1);
            var hib = first.run("doc");
            assertInstanceOf(WorkflowResult.Hibernated.class, hib);
            assertEquals("wait", ((WorkflowResult.Hibernated<String>) hib).lastStepName());
            assertEquals("doc→ingested→hibernated",
                    ((WorkflowResult.Hibernated<String>) hib).savedState());
        } finally {
            store1.stop();
        }

        // --- Process 2: same DB file, fresh store handle, fresh Workflow ---
        var store2 = new SqliteCheckpointStore(dbFile);
        store2.start();
        try {
            var ingestRan = new AtomicInteger();
            var waitRan = new AtomicInteger();
            var publishRan = new AtomicInteger();
            var second = new Workflow<>("resume-across-restart", coord,
                    List.of(
                            stringStep("ingest", s -> {
                                ingestRan.incrementAndGet();
                                return StepOutcome.advance(s + "→REDONE");
                            }),
                            stringStep("wait", s -> {
                                waitRan.incrementAndGet();
                                return StepOutcome.advance(s + "→REDONE");
                            }),
                            stringStep("publish", s -> {
                                publishRan.incrementAndGet();
                                return StepOutcome.done(s + "→published");
                            })),
                    store2);
            var result = second.run("ignored-because-resuming");
            var completed = assertInstanceOf(WorkflowResult.Completed.class, result);
            assertEquals(0, ingestRan.get(),
                    "ingest should not re-run after cold restart resume");
            assertEquals(0, waitRan.get(),
                    "wait should not re-run after cold restart resume");
            assertEquals(1, publishRan.get(),
                    "publish should run exactly once after resume");
            assertEquals("doc→ingested→hibernated→published", completed.finalState());
        } finally {
            store2.stop();
        }
    }

    @FunctionalInterface
    private interface Body {
        StepOutcome<String> apply(String state) throws Exception;
    }

    private static WorkflowStep<String> stringStep(String name, Body body) {
        return new WorkflowStep<>() {
            @Override public String name() { return name; }
            @Override public StepOutcome<String> execute(String state) throws Exception {
                return body.apply(state);
            }
        };
    }
}
