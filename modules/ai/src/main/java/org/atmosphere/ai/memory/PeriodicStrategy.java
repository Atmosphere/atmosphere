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
 * Extract facts every N messages. Balances cost and freshness.
 */
final class PeriodicStrategy implements MemoryExtractionStrategy {

    private final int interval;
    private final OnSessionCloseStrategy delegate = new OnSessionCloseStrategy();

    PeriodicStrategy(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.interval = interval;
    }

    @Override
    public boolean shouldExtract(String conversationId, String message, int messageCount) {
        return messageCount > 0 && messageCount % interval == 0;
    }

    @Override
    public List<String> extractFacts(String conversationText, AgentRuntime runtime) {
        return delegate.extractFacts(conversationText, runtime);
    }
}
