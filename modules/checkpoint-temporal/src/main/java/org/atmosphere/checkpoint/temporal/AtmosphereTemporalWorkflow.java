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

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The one generic Temporal workflow type this adapter registers: it drives an
 * Atmosphere {@code Workflow<S>} step by step through
 * {@link StepExecutionActivities}, so every Atmosphere workflow — regardless
 * of its state type — runs under a single registered Temporal workflow
 * definition.
 */
@WorkflowInterface
public interface AtmosphereTemporalWorkflow {

    /** Execute the workflow described by {@code request} to a terminal result. */
    @WorkflowMethod
    TemporalWorkflowResult execute(TemporalWorkflowRequest request);
}
