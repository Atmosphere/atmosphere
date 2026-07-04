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
 * The harness PLANNING primitive — the agent maintains a plan it exposes and
 * updates. {@link org.atmosphere.ai.plan.AgentPlan} models the ordered steps
 * (content + {@link org.atmosphere.ai.plan.PlanStatus}), persisted per
 * agent × conversation through {@link org.atmosphere.ai.plan.AgentPlanStore}
 * (file-backed default:
 * {@link org.atmosphere.ai.plan.FileSystemAgentPlanStore}).
 *
 * <p>The portable floor is the built-in {@code write_todos} tool
 * ({@link org.atmosphere.ai.plan.PlanningTools} — deepagents full-list
 * replace semantics); runtimes that genuinely wire native plan machinery
 * declare {@link org.atmosphere.ai.AiCapability#PLANNING} and win under the
 * {@link org.atmosphere.ai.plan.PlanningMode} AUTO default. Every plan
 * change emits {@link org.atmosphere.ai.AiEvent.PlanUpdate} so consoles
 * render the live plan.</p>
 */
package org.atmosphere.ai.plan;
