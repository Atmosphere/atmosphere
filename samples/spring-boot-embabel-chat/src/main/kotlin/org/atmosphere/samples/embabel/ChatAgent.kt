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
package org.atmosphere.samples.embabel

import org.atmosphere.agent.annotation.Agent
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.annotation.Prompt

/**
 * Atmosphere @Agent that uses the Embabel AgentRuntime for execution.
 * The @Prompt method delegates to session.stream() which routes through
 * whichever AgentRuntime is on the classpath — in this case, Embabel.
 */
@Agent(name = "embabel-chat", description = "Chat agent powered by Embabel GOAP")
class ChatAgent {

    @Prompt
    fun onPrompt(message: String, session: StreamingSession) {
        session.stream(message)
    }
}
