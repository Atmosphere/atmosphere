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
package org.atmosphere.coordinator.fleet;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Proxy to a single discovered agent. Encapsulates transport (local or remote).
 */
public interface AgentProxy {

    String name();

    String version();

    boolean isAvailable();

    /**
     * Preference weight for routing decisions. Higher values indicate stronger
     * preference. Reserved for future load-balancing and preference scoring
     * across agents with overlapping capabilities. Currently logged at startup
     * but not used for routing.
     */
    int weight();

    boolean isLocal();

    AgentResult call(String skill, Map<String, Object> args);

    CompletableFuture<AgentResult> callAsync(String skill, Map<String, Object> args);

    void stream(String skill, Map<String, Object> args,
                Consumer<String> onToken, Runnable onComplete);
}
