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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.intent.IntentRouting;
import org.atmosphere.ai.intent.IntentRoutingProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code @AiEndpoint} side of the intent-routing e2e: a real annotated
 * endpoint, registered through {@code AiEndpointProcessor} and served by
 * {@code AiEndpointHandler}, whose {@code intentRouting} attribute installs
 * {@link ScriptedIntentRuntime#customerRouting}. The {@code @Prompt} body only
 * streams: routing happens inside {@code session.stream(message)}, and on the
 * {@code general} route the endpoint's resolved runtime answers (the demo
 * runtime in the keyless e2e).
 */
@AiEndpoint(path = IntentRoutingTestEndpoint.PATH,
        systemPrompt = "You help customers.",
        intentRouting = IntentRoutingTestEndpoint.Routes.class)
public class IntentRoutingTestEndpoint {

    /** Where the endpoint is served. */
    public static final String PATH = "/ai/intent-endpoint";

    /** The provider the annotation names; instantiated once at registration. */
    public static final class Routes implements IntentRoutingProvider {
        private final AtomicInteger tickets = new AtomicInteger();

        @Override
        public IntentRouting intentRouting() {
            return ScriptedIntentRuntime.customerRouting(tickets);
        }
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
