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
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.gateway.AiGateway
import org.atmosphere.ai.gateway.AiGatewayHolder
import org.atmosphere.ai.gateway.PerUserRateLimiter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exec-level proof that [KoogAgentRuntime] admits through [AiGateway] on
 * every invocation. Koog does not extend AbstractAgentRuntime (implements
 * AgentRuntime directly), so the admit hop is implemented at the top of
 * its own `execute` method — this test drives the real `execute` path to
 * prove that hop fires before the native PromptExecutor is touched. No
 * PromptExecutor is configured; the runtime throws downstream, but
 * admission has already been recorded.
 */
internal class KoogGatewayAdmissionTest {

    private lateinit var exporter: CountingExporter

    @BeforeEach
    fun installCountingGateway() {
        exporter = CountingExporter()
        AiGatewayHolder.install(
            AiGateway(
                PerUserRateLimiter(1_000_000, Duration.ofHours(1)),
                AiGateway.CredentialResolver.noop(),
                exporter,
            )
        )
    }

    @AfterEach
    fun restoreDefault() {
        AiGatewayHolder.reset()
    }

    @Test
    fun executeRecordsExactlyOneAdmissionWithRuntimeLabel() {
        val runtime = KoogAgentRuntime()
        val context = AgentExecutionContext(
            "Hello", "You are helpful", "gpt-4o-mini",
            null, "session-1", "alice", "conv-1",
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null,
        )

        try {
            runtime.execute(context, NoopSession())
        } catch (ignored: RuntimeException) {
            // PromptExecutor is not configured — the runtime throws
            // IllegalStateException *after* admit fires. Fine.
        }

        assertEquals(
            1, exporter.entries.size,
            "execute() must admit through the gateway exactly once — saw " + exporter.entries.size,
        )
        val entry = exporter.entries[0]
        assertTrue(entry.accepted(), "test limiter accepts: " + entry.reason())
        assertEquals("koog", entry.provider(), "gateway trace must carry the runtime label")
        assertEquals("alice", entry.userId())
        assertEquals("gpt-4o-mini", entry.model())
    }

    private class CountingExporter : AiGateway.GatewayTraceExporter {
        val entries: MutableList<AiGateway.GatewayTraceEntry> = CopyOnWriteArrayList()

        override fun record(entry: AiGateway.GatewayTraceEntry) {
            entries.add(entry)
        }
    }

    private class NoopSession : StreamingSession {
        override fun sessionId(): String = "admission-test"
        override fun send(text: String) {}
        override fun sendMetadata(key: String, value: Any) {}
        override fun progress(message: String) {}
        override fun complete() {}
        override fun complete(summary: String) {}
        override fun error(t: Throwable) {}
        override fun isClosed(): Boolean = false
    }
}
