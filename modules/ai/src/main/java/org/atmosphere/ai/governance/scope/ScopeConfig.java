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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.annotation.AgentScope;

import java.util.List;

/**
 * Resolved scope configuration extracted from an {@link AgentScope} annotation
 * (or a YAML policy file). Immutable; validated at construction so downstream
 * code can treat the record as trusted.
 */
public record ScopeConfig(
        String purpose,
        List<String> forbiddenTopics,
        AgentScope.Breach onBreach,
        String redirectMessage,
        AgentScope.Tier tier,
        double similarityThreshold,
        boolean postResponseCheck,
        boolean unrestricted,
        String justification) {

    /** Default user-visible message when {@link AgentScope#redirectMessage()} is blank. */
    public static final String DEFAULT_REDIRECT_MESSAGE =
            "I can only help with topics within this assistant's declared scope. "
                    + "What can I help you with on that?";

    public ScopeConfig {
        if (unrestricted) {
            if (justification == null || justification.isBlank()) {
                throw new IllegalArgumentException(
                        "unrestricted @AgentScope requires a non-blank justification");
            }
            // Normalise the "wildcard" shape.
            purpose = purpose == null ? "" : purpose;
            forbiddenTopics = forbiddenTopics == null ? List.of() : List.copyOf(forbiddenTopics);
            redirectMessage = redirectMessage == null ? "" : redirectMessage;
        } else {
            if (purpose == null || purpose.isBlank()) {
                throw new IllegalArgumentException(
                        "@AgentScope requires a non-blank purpose (or unrestricted = true)");
            }
            forbiddenTopics = forbiddenTopics == null ? List.of() : List.copyOf(forbiddenTopics);
            redirectMessage = (redirectMessage == null || redirectMessage.isBlank())
                    ? DEFAULT_REDIRECT_MESSAGE : redirectMessage;
            justification = justification == null ? "" : justification;
        }
        if (onBreach == null) {
            onBreach = AgentScope.Breach.POLITE_REDIRECT;
        }
        if (tier == null) {
            tier = AgentScope.Tier.EMBEDDING_SIMILARITY;
        }
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be in [0, 1], got: " + similarityThreshold);
        }
    }

    /** Build a {@link ScopeConfig} from an {@link AgentScope} annotation instance. */
    public static ScopeConfig fromAnnotation(AgentScope annotation) {
        if (annotation == null) {
            throw new IllegalArgumentException("annotation must not be null");
        }
        return new ScopeConfig(
                annotation.purpose(),
                List.of(annotation.forbiddenTopics()),
                annotation.onBreach(),
                annotation.redirectMessage(),
                annotation.tier(),
                annotation.similarityThreshold(),
                annotation.postResponseCheck(),
                annotation.unrestricted(),
                annotation.justification());
    }
}
