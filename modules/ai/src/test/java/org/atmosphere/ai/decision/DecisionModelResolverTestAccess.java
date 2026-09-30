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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;

/**
 * Makes the demo runtime the resolved {@link org.atmosphere.ai.AgentRuntime}
 * for a test, whatever earlier tests installed: the demo runtime is available
 * whenever the installed settings reach no model (unconfigured, or fake mode)
 * and no client was explicitly bound.
 */
public final class DecisionModelResolverTestAccess {

    private static AiConfig.LlmSettings saved;

    private DecisionModelResolverTestAccess() {
    }

    public static void forceDemoOnly() {
        var current = AiConfig.get();
        if (current != null && current.hasReachableModel()) {
            saved = current;
            AiConfig.configure("fake", current.model(), null, null);
        }
        AgentRuntimeResolver.clearExplicitClientBinding();
        DecisionModelResolver.reset();
    }

    public static void restore() {
        if (saved != null) {
            AiConfig.configure(saved.mode(), saved.model(), saved.apiKey(), saved.baseUrl());
            saved = null;
        }
        AgentRuntimeResolver.reset();
        DecisionModelResolver.reset();
    }
}
