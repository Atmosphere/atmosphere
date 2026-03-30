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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntime;

import java.util.List;

/**
 * Extract facts after every message. Real-time but expensive (one LLM call per message).
 */
final class PerMessageStrategy implements MemoryExtractionStrategy {

    private final OnSessionCloseStrategy delegate = new OnSessionCloseStrategy();

    @Override
    public boolean shouldExtract(String conversationId, String message, int messageCount) {
        return true;
    }

    @Override
    public List<String> extractFacts(String conversationText, AgentRuntime runtime) {
        return delegate.extractFacts(conversationText, runtime);
    }
}
