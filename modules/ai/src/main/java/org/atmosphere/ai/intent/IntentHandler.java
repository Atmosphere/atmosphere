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
package org.atmosphere.ai.intent;

/**
 * Answers a request routed to an {@link IntentRoute.Handler} or an
 * {@link IntentRoute.Human} route.
 *
 * <p>The framework owns the session: it sends the returned text and completes
 * the turn, or errors the turn when the handler throws. A handler never sees
 * the session, so it cannot leave a turn open. It runs on the thread that
 * dispatched the turn (a virtual thread on {@code @AiEndpoint}), like a
 * {@code @Prompt} body.</p>
 */
@FunctionalInterface
public interface IntentHandler {

    /**
     * Handle the request.
     *
     * @param decision the routing decision, carrying the classified message and the request
     * @return the reply to send; {@code null} or empty sends nothing and only completes the turn
     * @throws Exception any failure; the turn is errored with it
     */
    String handle(IntentDecision decision) throws Exception;
}
