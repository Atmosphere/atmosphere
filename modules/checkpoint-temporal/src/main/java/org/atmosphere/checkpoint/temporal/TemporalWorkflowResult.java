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

/**
 * Terminal outcome of the generic Temporal workflow, mapped back to
 * {@code WorkflowResult} by the provider: the terminal kind, the last step
 * that ran, and the failure reason when {@code FAILED}.
 */
public record TemporalWorkflowResult(Kind kind, String lastStepName, String reason) {

    /** Terminal state, mirroring {@code WorkflowResult}'s three cases. */
    public enum Kind { COMPLETED, HIBERNATED, FAILED }
}
