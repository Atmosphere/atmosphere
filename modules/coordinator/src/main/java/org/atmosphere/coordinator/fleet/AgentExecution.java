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
package org.atmosphere.coordinator.fleet;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A cancellable handle to an in-flight agent call. Returned by
 * {@link AgentProxy#callWithHandle} and {@link AgentFleet#parallelCancellable}.
 *
 * <p>This is the manual equivalent of {@code StructuredTaskScope} — when
 * it finalizes (JDK 27+), this handle can delegate to it.</p>
 */
public sealed interface AgentExecution {

    /** The agent name this execution targets. */
    String agentName();

    /** A running agent call that can be cancelled or joined. */
    record Running(
            String agentName,
            String skill,
            Instant startedAt,
            CompletableFuture<AgentResult> future
    ) implements AgentExecution {

        /**
         * Cancel this execution. Returns true if the cancellation signal
         * was delivered (the agent call may still complete normally).
         */
        public boolean cancel() {
            try {
                return future.cancel(true);
            } catch (CancellationException e) {
                return true;
            }
        }

        /**
         * Wait for the result with a timeout.
         *
         * @param timeout maximum wait time
         * @return the result, or a failure if the timeout expired
         */
        public AgentResult join(Duration timeout) {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return AgentResult.failure(agentName, skill,
                        "Timed out after " + timeout.toMillis() + "ms",
                        Duration.between(startedAt, Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentResult.failure(agentName, skill,
                        "Interrupted", Duration.between(startedAt, Instant.now()));
            } catch (CancellationException e) {
                return AgentResult.failure(agentName, skill,
                        "Cancelled", Duration.between(startedAt, Instant.now()));
            } catch (ExecutionException e) {
                return AgentResult.failure(agentName, skill,
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage(),
                        Duration.between(startedAt, Instant.now()));
            }
        }

        /** Wait for the result using the default fleet timeout (120s). */
        public AgentResult join() {
            return join(Duration.ofSeconds(120));
        }

        /** Whether the execution has completed (normally or exceptionally). */
        public boolean isDone() {
            return future.isDone();
        }
    }

    /** A completed execution with a result already available. */
    record Done(String agentName, AgentResult result) implements AgentExecution {}
}
