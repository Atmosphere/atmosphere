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
package org.atmosphere.ai.koog

import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.AiProviderException
import org.atmosphere.ai.CollectingSession
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.gateway.AiGateway
import org.atmosphere.ai.gateway.AiGatewayHolder
import org.atmosphere.ai.gateway.PerUserRateLimiter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KoogAgentRuntime] implements [org.atmosphere.ai.AgentRuntime] directly
 * and admits through the [AiGateway] on the calling thread of both `execute`
 * and `executeWithHandle`, before its worker starts. A refusal was thrown with
 * the session still open. Both entry points must end the session once with
 * the [AiProviderException.RateLimited] refusal.
 */
internal class KoogGatewayRejectionTest {

    @BeforeEach
    fun installExhaustedGateway() {
        val gateway = AiGateway(
            PerUserRateLimiter(1, Duration.ofHours(1)),
            AiGateway.CredentialResolver.noop(),
        ) { }
        assertTrue(gateway.admit("alice", "drain", "default").accepted())
        AiGatewayHolder.install(gateway)
    }

    @AfterEach
    fun restoreDefault() {
        AiGatewayHolder.reset()
    }

    @Test
    fun executeEndsSessionWhenGatewayRejects() {
        val session = CountingSession()

        val thrown = assertThrows(AiProviderException.RateLimited::class.java) {
            KoogAgentRuntime().execute(context(), session)
        }

        assertTrue(session.isClosed, "the refused call must end the session")
        assertEquals(1, session.errors.get(), "the session must be ended exactly once")
        assertEquals(thrown, session.failure())
    }

    @Test
    fun executeWithHandleEndsSessionWhenGatewayRejects() {
        val session = CountingSession()

        val thrown = assertThrows(AiProviderException.RateLimited::class.java) {
            KoogAgentRuntime().executeWithHandle(context(), session)
        }

        assertTrue(session.isClosed, "the refused call must end the session")
        assertEquals(1, session.errors.get(), "the session must be ended exactly once")
        assertEquals(thrown, session.failure())
    }

    private fun context() = AgentExecutionContext(
        "Hello", "You are helpful", "gpt-4o-mini",
        null, "session-1", "alice", "conv-1",
        emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
        emptyList<org.atmosphere.ai.ContextProvider>(),
        emptyMap<String, Any>(),
        emptyList<org.atmosphere.ai.llm.ChatMessage>(),
        null, null,
    )

    /** Counts every `error` call, the ones after the session ended included. */
    private class CountingSession : StreamingSession {
        private val delegate = CollectingSession("koog-gateway")
        val errors = AtomicInteger()

        fun failure(): Throwable? = delegate.failure()

        override fun sessionId(): String = delegate.sessionId()
        override fun send(text: String) = delegate.send(text)
        override fun sendMetadata(key: String, value: Any) {}
        override fun progress(message: String) {}
        override fun complete() = delegate.complete()
        override fun complete(summary: String) = delegate.complete(summary)
        override fun error(t: Throwable) {
            errors.incrementAndGet()
            delegate.error(t)
        }
        override fun isClosed(): Boolean = delegate.isClosed
        override fun hasErrored(): Boolean = delegate.failed()
    }
}
