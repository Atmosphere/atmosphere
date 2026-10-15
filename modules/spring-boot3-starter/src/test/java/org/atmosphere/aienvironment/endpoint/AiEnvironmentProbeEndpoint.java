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
package org.atmosphere.aienvironment.endpoint;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;

/**
 * The only {@code @AiEndpoint} of its package, scanned by
 * {@code TwoApplicationsAiEnvironmentTest}: it streams the prompt through the
 * application's runtime, so the model request shows which application's
 * settings and knobs it carried.
 */
@AiEndpoint(path = "/atmosphere/ai-environment-probe")
public class AiEnvironmentProbeEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
