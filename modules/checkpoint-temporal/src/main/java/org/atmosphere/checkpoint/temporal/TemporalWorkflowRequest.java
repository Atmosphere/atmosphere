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

import java.util.List;

/**
 * Input of the generic {@link AtmosphereTemporalWorkflow}: which live
 * execution session to drive ({@code executionId}), the ordered step specs,
 * and the per-step start-to-close timeout. This is everything the
 * deterministic workflow code needs — application state never enters the
 * Temporal payload; it stays with the session and the checkpoint store.
 */
public record TemporalWorkflowRequest(String executionId, String workflowName,
                                      List<StepSpec> steps, long stepTimeoutMillis) { }
