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
package org.atmosphere.ai.state;

/**
 * The hierarchical rule set assembled for an agent × user pair. In the
 * file-backed default this is the merged content of the OpenClaw workspace
 * bootstrap files.
 *
 * <p>{@link #systemPrompt()} is the composed prompt that prefixes every
 * conversation turn. The individual components ({@link #identity()},
 * {@link #persona()}, {@link #userProfile()}, {@link #operatingRules()}) are
 * preserved so callers can inspect or re-render them independently.</p>
 *
 * @param systemPrompt    the composed system prompt to prefix every turn
 * @param identity        agent name / vibe / emoji (from {@code IDENTITY.md})
 * @param persona         tone / boundaries (from {@code SOUL.md})
 * @param userProfile     who the user is (from {@code USER.md})
 * @param operatingRules  how the agent should behave (from {@code AGENTS.md})
 */
public record RuleSet(
        String systemPrompt,
        String identity,
        String persona,
        String userProfile,
        String operatingRules) {

    /** Empty rule set when no workspace rules are available. */
    public static RuleSet empty() {
        return new RuleSet("", "", "", "", "");
    }
}
