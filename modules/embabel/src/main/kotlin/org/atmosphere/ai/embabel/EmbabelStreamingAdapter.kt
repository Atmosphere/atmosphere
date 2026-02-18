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
package org.atmosphere.ai.embabel

import org.atmosphere.ai.AiStreamingAdapter
import org.atmosphere.ai.StreamingSession

/**
 * Embabel adapter implementing the Atmosphere [AiStreamingAdapter] SPI.
 *
 * This adapter creates an [AtmosphereOutputChannel] for each streaming request,
 * allowing Embabel agents to push events directly to browser clients.
 */
class EmbabelStreamingAdapter : AiStreamingAdapter<EmbabelStreamingAdapter.AgentRequest> {

    override fun name(): String = "embabel"

    override fun stream(request: AgentRequest, session: StreamingSession) {
        session.progress("Starting agent: ${request.agentName}...")
        val channel = AtmosphereOutputChannel(session)
        request.runner(channel)
    }

    /**
     * Request wrapping an agent name and a runner function.
     * The runner receives the OutputChannel and is responsible for
     * invoking the Embabel agent platform.
     */
    data class AgentRequest(
        val agentName: String,
        val runner: (AtmosphereOutputChannel) -> Unit
    )
}
