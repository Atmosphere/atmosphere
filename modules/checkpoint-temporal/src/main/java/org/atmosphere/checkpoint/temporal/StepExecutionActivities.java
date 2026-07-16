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

import io.temporal.activity.ActivityInterface;

/**
 * Activities bridging Temporal back into the live {@link ExecutionSession}:
 * resolving where a (possibly resumed) run starts, and executing one step.
 * Both delegate to the session registered by the provider in the JVM that
 * started the run.
 */
@ActivityInterface
public interface StepExecutionActivities {

    /**
     * Resume rule of the in-tree engine: the index after the last
     * checkpointed step, or 0 (after seeding an initial snapshot) on a
     * fresh start.
     */
    int resolveStartIndex(String executionId);

    /** Execute the named step against the session's current state. */
    StepReport executeStep(String executionId, String stepName);
}
