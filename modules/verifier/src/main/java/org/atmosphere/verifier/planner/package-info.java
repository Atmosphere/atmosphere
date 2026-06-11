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

/**
 * Deterministic Goal-Oriented Action Planning (GOAP) for the verifier
 * pipeline. Where {@link org.atmosphere.verifier.PlanAndVerify} normally asks
 * an LLM for a workflow, {@link org.atmosphere.verifier.planner.GoapPlanner}
 * <em>derives</em> one by bounded breadth-first search over
 * {@link org.atmosphere.verifier.planner.GoapAction} preconditions and
 * effects, and {@link org.atmosphere.verifier.planner.GoapPlanRuntime} exposes
 * that as a drop-in {@link org.atmosphere.ai.AgentRuntime} so the same static
 * verifier chain runs over the result.
 *
 * <p>The deterministic planner is reproducible and exhaustive within its
 * bounds, and only ever assembles actions that advance the declared goal — so
 * it cannot be steered into an off-goal (e.g. exfiltration) step, the planning
 * analogue of the verifier rejecting a malicious LLM plan.</p>
 */
package org.atmosphere.verifier.planner;
