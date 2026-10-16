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
package org.atmosphere.samples.springboot.reattach;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;

/**
 * {@code @AiEndpoint} whose {@code @Prompt} answers with
 * {@code session.stream(...)}: a model call, unlike {@link SlowEmitterChat}'s
 * {@code session.send(...)} loop, that a disconnect cancels. The model is the
 * harness's {@link PacedModelClient}, a slow reply of numbered tokens.
 *
 * <p>A client whose connection is lost (a reload, a dropped network) keeps
 * the call going for the reattach grace: coming back with
 * {@code X-Atmosphere-Run-Id} it receives the whole reply through
 * {@code complete}. A client that leaves on purpose (atmosphere.js
 * {@code close()}) cancels the call at once.</p>
 *
 * <p>Path: {@code /atmosphere/agent/harness-model}.</p>
 */
@AiEndpoint(path = "/atmosphere/agent/harness-model",
        requires = {AiCapability.TEXT_STREAMING})
@AgentScope(unrestricted = true,
        justification = "Reattach-harness test fixture — accepts arbitrary prompts to exercise the reattach grace of a model call.")
public class SlowModelChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
