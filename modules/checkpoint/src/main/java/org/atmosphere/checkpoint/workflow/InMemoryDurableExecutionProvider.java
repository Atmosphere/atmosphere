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

/**
 * Dependency-free reference {@link DurableExecutionProvider} that runs a
 * {@link Workflow} on Atmosphere's own step engine — each step checkpointed
 * through the workflow's configured {@code CheckpointStore}. This is the
 * always-available fallback; a Temporal/DBOS/Restate adapter implements the same
 * SPI to run the identical {@code Workflow<S>} on an external engine.
 */
public final class InMemoryDurableExecutionProvider implements DurableExecutionProvider {

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public <S> WorkflowResult<S> run(Workflow<S> workflow, S initialState) {
        return workflow.run(initialState);
    }
}
