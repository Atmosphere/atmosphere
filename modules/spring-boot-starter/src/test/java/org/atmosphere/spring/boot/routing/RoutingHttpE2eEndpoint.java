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
package org.atmosphere.spring.boot.routing;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.ChatCompletionRequest;

/**
 * Top-level {@code @AiEndpoint} for the config-driven routing wire e2e
 * ({@code RoutingConfigHttpE2eTest}). Lives in its own sub-package so the
 * test can point {@code atmosphere.packages} here and scan <em>only</em> this
 * endpoint — the sibling {@code LtmHttpE2eEndpoint} in the parent package
 * declares bean-dependent interceptors that would fail to instantiate in this
 * test's context.
 *
 * <p>The {@code @Prompt} deliberately drives {@link AiConfig#get()}{@code
 * .client()} directly — the exact process-wide client every {@code
 * AgentRuntime} dispatch reads, and the one the routing auto-configuration
 * replaces with a {@code RoutingLlmClient} when {@code
 * atmosphere.ai.routing.enabled=true}. Driving it from a real {@code @Prompt}
 * over a live WebSocket session is what proves the config-installed router is
 * actually consumed on the request critical path and streams its
 * {@code routing.model} decision back over the wire — the seam the
 * in-process auto-config test (capturing client) and the programmatic
 * Playwright e2e (builder-made router) each leave untested.</p>
 */
@AiEndpoint(path = "/atmosphere/routing-e2e",
        systemPrompt = "You are a helpful test assistant.")
public class RoutingHttpE2eEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        // Read the GLOBAL config-installed client (the RoutingLlmClient the
        // auto-config wrapped around the resolved fake client) and drive a real
        // turn over the live wire session. The router emits
        // session.sendMetadata("routing.model", <model>) before delegating, so
        // the routing decision reaches the connected client over the socket.
        AiConfig.get().client().streamChatCompletion(
                ChatCompletionRequest.of("auto", message), session);
    }
}
