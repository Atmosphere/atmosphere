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
package org.atmosphere.ai.plan;

import java.util.Optional;

/**
 * Persistence SPI for {@link AgentPlan} — plans are keyed per
 * {@code agentId + conversationId} so an agent's plan survives across turns
 * of one conversation without bleeding into another. The default
 * implementation is {@link FileSystemAgentPlanStore}, which persists JSON in
 * the agent workspace subtree; native planning bridges (e.g. AgentScope
 * {@code PlanStorage}) may persist through this SPI so Atmosphere owns the
 * plan state regardless of which surface maintains it.
 */
public interface AgentPlanStore {

    /**
     * Read the current plan for an agent × conversation.
     *
     * @param agentId        the owning agent (never {@code null} or blank)
     * @param conversationId the conversation scope (never {@code null} or blank)
     * @return the stored plan, or empty when none was written yet
     */
    Optional<AgentPlan> get(String agentId, String conversationId);

    /**
     * Replace the plan for an agent × conversation (full-list replace —
     * deepagents {@code write_todos} parity).
     *
     * @param agentId        the owning agent (never {@code null} or blank)
     * @param conversationId the conversation scope (never {@code null} or blank)
     * @param plan           the new plan (never {@code null})
     * @throws IllegalArgumentException when a key is invalid or the plan
     *                                  exceeds the store's size bounds
     */
    void put(String agentId, String conversationId, AgentPlan plan);
}
