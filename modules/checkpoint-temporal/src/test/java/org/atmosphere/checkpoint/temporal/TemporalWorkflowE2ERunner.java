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
package org.atmosphere.checkpoint.temporal;

import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.workflow.DurableExecutionProvider;
import org.atmosphere.checkpoint.workflow.StepOutcome;
import org.atmosphere.checkpoint.workflow.Workflow;
import org.atmosphere.checkpoint.workflow.WorkflowResult;
import org.atmosphere.checkpoint.workflow.WorkflowStep;

import java.util.List;

/**
 * One-shot producer for the Playwright e2e ({@code e2e/tests/temporal-workflow.spec.ts}):
 * runs a two-step {@code Workflow<S>} through {@code Workflow.run()} against a
 * <em>real</em> Temporal dev server, then exits. Exits non-zero when the
 * temporal provider was not selected or the result is wrong, so a silent
 * fallback to the in-tree engine fails the pipeline here instead of producing
 * an empty UI for the spec to time out on.
 *
 * <pre>
 * temporal server start-dev   # UI on :8233, gRPC on :7233
 * ./mvnw -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *     -pl modules/checkpoint-temporal \
 *     -Dexec.mainClass=org.atmosphere.checkpoint.temporal.TemporalWorkflowE2ERunner \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * (Lives in test scope; the name deliberately avoids surefire's
 * {@code *Test} patterns so it never runs as a unit test.)
 */
public final class TemporalWorkflowE2ERunner {

    private TemporalWorkflowE2ERunner() {
    }

    public static void main(String[] args) {
        var provider = DurableExecutionProvider.resolve();
        System.out.println("provider=" + provider.name());
        if (!"temporal".equals(provider.name())) {
            System.err.println("FATAL: expected the temporal provider, got '" + provider.name()
                    + "' — is the dev server up on 127.0.0.1:7233?");
            System.exit(2);
        }

        var store = new InMemoryCheckpointStore();
        store.start();
        var workflow = new Workflow<>("e2e-doc-pipeline", "coord-e2e", List.of(
                step("ingest", s -> StepOutcome.advance(s + ":ingested")),
                step("publish", s -> StepOutcome.done(s + ":published"))), store);

        WorkflowResult<String> result = workflow.run("doc-42");
        System.out.println("result=" + result);
        if (!(result instanceof WorkflowResult.Completed<String> completed)
                || !"doc-42:ingested:published".equals(completed.finalState())) {
            System.err.println("FATAL: unexpected result " + result);
            System.exit(3);
        }
        System.out.println("E2E RUN OK");
        // The worker's poller threads are non-daemon — release them so the
        // JVM (and exec:java) can exit instead of hanging on join.
        TemporalRuntime.shutdown();
    }

    private interface Body {
        StepOutcome<String> apply(String state) throws Exception;
    }

    private static WorkflowStep<String> step(String name, Body body) {
        return new WorkflowStep<>() {
            @Override public String name() {
                return name;
            }

            @Override public StepOutcome<String> execute(String state) throws Exception {
                return body.apply(state);
            }
        };
    }
}
