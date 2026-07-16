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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ServiceLoader-registered stand-in for an external engine adapter
 * (Temporal/DBOS/Restate). Unavailable by default so every other test in the
 * module keeps resolving the in-tree reference; a test flips {@link #AVAILABLE}
 * to prove {@link Workflow#run} routes to a registered external provider.
 */
public final class TestExternalDurableExecutionProvider implements DurableExecutionProvider {

    public static final AtomicBoolean AVAILABLE = new AtomicBoolean();
    public static final AtomicInteger RUNS = new AtomicInteger();

    @Override
    public String name() {
        return "test-external";
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE.get();
    }

    @Override
    public <S> WorkflowResult<S> run(Workflow<S> workflow, S initialState) {
        RUNS.incrementAndGet();
        return new WorkflowResult.Completed<>(initialState, workflow.coordinationId());
    }
}
