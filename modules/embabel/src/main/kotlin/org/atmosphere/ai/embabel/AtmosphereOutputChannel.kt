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

import com.embabel.agent.api.channel.*
import org.atmosphere.ai.StreamingSession
import org.slf4j.LoggerFactory

/**
 * Embabel [OutputChannel] implementation that pushes agent events
 * to connected browsers via an Atmosphere [StreamingSession].
 *
 * Bridges Embabel's event types to Atmosphere's streaming wire protocol:
 * - [MessageOutputChannelEvent] → `session.send()` (token/content)
 * - [ProgressOutputChannelEvent] → `session.progress()` (status updates)
 * - [ContentOutputChannelEvent] → `session.send()` (structured content)
 * - [LoggingOutputChannelEvent] → `session.progress()` (at INFO+ level)
 *
 * ```kotlin
 * val session = StreamingSessions.start(resource)
 * val channel = AtmosphereOutputChannel(session)
 * agentPlatform.runProcess(agent, input, outputChannel = channel)
 * ```
 */
class AtmosphereOutputChannel(
    private val session: StreamingSession
) : OutputChannel {

    private val logger = LoggerFactory.getLogger(AtmosphereOutputChannel::class.java)

    override fun send(event: OutputChannelEvent) {
        if (session.isClosed) {
            logger.debug("Ignoring event on closed session {}: {}", session.sessionId(), event)
            return
        }

        when (event) {
            is MessageOutputChannelEvent -> {
                session.send(event.message.content)
            }

            is ContentOutputChannelEvent -> {
                session.send(event.content.content)
            }

            is ProgressOutputChannelEvent -> {
                session.progress(event.message)
            }

            is LoggingOutputChannelEvent -> {
                if (event.level >= LoggingOutputChannelEvent.Level.INFO) {
                    session.progress(event.message)
                }
            }

            else -> {
                logger.debug("Unhandled event type: {}", event.javaClass.simpleName)
            }
        }
    }
}
