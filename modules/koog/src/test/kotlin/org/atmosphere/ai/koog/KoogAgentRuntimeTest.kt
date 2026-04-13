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

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.atmosphere.ai.AiCapability
import org.atmosphere.ai.AgentExecutionContext
import org.atmosphere.ai.StreamingSessions
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.Broadcaster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KoogAgentRuntimeTest {

    private lateinit var resource: AtmosphereResource
    private lateinit var broadcaster: Broadcaster

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        resource = mock(AtmosphereResource::class.java)
        broadcaster = mock(Broadcaster::class.java)
        `when`(resource.uuid()).thenReturn("resource-1")
        `when`(resource.getBroadcaster()).thenReturn(broadcaster)
        `when`(broadcaster.broadcast(any<Any>(), any<Set<AtmosphereResource>>()))
            .thenReturn(mock(Future::class.java) as Future<Any>)
    }

    @AfterEach
    fun tearDown() {
        // Clear static executor to avoid test leakage
        clearExecutor()
    }

    private fun clearExecutor() {
        try {
            // Kotlin companion object stores the field on the outer class as a static
            val field = KoogAgentRuntime::class.java.getDeclaredField("promptExecutor")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Fallback: try companion inner class
            try {
                val companionClass = Class.forName("org.atmosphere.ai.koog.KoogAgentRuntime\$Companion")
                for (f in KoogAgentRuntime::class.java.declaredFields) {
                    if (f.type == PromptExecutor::class.java ||
                        f.name.contains("promptExecutor", ignoreCase = true)) {
                        f.isAccessible = true
                        f.set(null, null)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun context(message: String = "hello"): AgentExecutionContext {
        return AgentExecutionContext(
            message, null, null, null, null, null, null,
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )
    }

    /** Creates a fake PromptExecutor that streams the given frames. */
    private fun fakeExecutor(vararg frames: StreamFrame): PromptExecutor {
        return object : PromptExecutor() {
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = flowOf(*frames)

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): List<Message.Response> = emptyList()

            override suspend fun moderate(
                prompt: Prompt,
                model: LLModel
            ): ModerationResult = ModerationResult(false, emptyMap())

            override fun close() {}
        }
    }

    /**
     * Fake executor that captures the Prompt it was invoked with so tests
     * can assert exactly what the runtime built from the context (system
     * prompt, history, multi-modal parts, etc.). Returns a single End frame
     * so the runtime completes cleanly.
     */
    private class CapturingExecutor : PromptExecutor() {
        @Volatile var capturedPrompt: Prompt? = null
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> {
            capturedPrompt = prompt
            return flowOf(StreamFrame.End())
        }
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): List<Message.Response> {
            capturedPrompt = prompt
            return emptyList()
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            ModerationResult(false, emptyMap())
        override fun close() {}
    }

    /** Creates a fake PromptExecutor that throws on streaming. */
    private fun failingExecutor(error: Exception): PromptExecutor {
        return object : PromptExecutor() {
            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = throw error

            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): List<Message.Response> = throw error

            override suspend fun moderate(
                prompt: Prompt,
                model: LLModel
            ): ModerationResult = ModerationResult(false, emptyMap())

            override fun close() {}
        }
    }

    private fun capturedMessages(): List<String> {
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(broadcaster, atLeast(1)).broadcast(captor.capture(), any<Set<AtmosphereResource>>())
        return captor.allValues.map { it.toString() }
    }

    // ── Basic properties ──

    @Test
    fun `name returns koog`() {
        assertEquals("koog", KoogAgentRuntime().name())
    }

    @Test
    fun `isAvailable returns true when koog is on classpath`() {
        assertTrue(KoogAgentRuntime().isAvailable())
    }

    @Test
    fun `priority is 100`() {
        assertEquals(100, KoogAgentRuntime().priority())
    }

    @Test
    fun `capabilities include all expected values`() {
        val caps = KoogAgentRuntime().capabilities()
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING))
        assertTrue(caps.contains(AiCapability.TOOL_CALLING))
        assertTrue(caps.contains(AiCapability.STRUCTURED_OUTPUT))
        assertTrue(caps.contains(AiCapability.AGENT_ORCHESTRATION))
        assertTrue(caps.contains(AiCapability.CONVERSATION_MEMORY))
        assertTrue(caps.contains(AiCapability.SYSTEM_PROMPT))
    }

    @Test
    fun `execute without executor throws IllegalStateException`() {
        clearExecutor()
        try {
            KoogAgentRuntime().execute(
                context(),
                mock(org.atmosphere.ai.StreamingSession::class.java)
            )
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PromptExecutor not configured"))
        }
    }

    // ── StreamFrame → AiEvent mapping ──

    @Test
    fun `TextDelta frames are forwarded as text-delta AiEvents`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Hello "),
            StreamFrame.TextDelta("world!"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("td-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"text-delta\"") && it.contains("Hello ") })
        assertTrue(messages.any { it.contains("\"event\":\"text-delta\"") && it.contains("world!") })
    }

    @Test
    fun `TextComplete frame is forwarded as text-complete AiEvent`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextComplete("Full response"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tc-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"text-complete\"") && it.contains("Full response") })
    }

    @Test
    fun `ToolCallComplete without tools in context is silently ignored`() {
        // When no tools are in the context, the direct executor path is used.
        // ToolCallComplete frames are ignored because there's nothing to execute.
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Hi"),
            StreamFrame.ToolCallComplete("call-1", "get_weather", "{\"city\":\"Montreal\"}"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tc-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"text-delta\"") && it.contains("Hi") })
        assertTrue(messages.none { it.contains("\"event\":\"tool-start\"") })
        assertTrue(messages.last().contains("\"type\":\"complete\""))
    }

    @Test
    fun `ReasoningDelta frame is forwarded as progress AiEvent`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ReasoningDelta("Thinking about the answer..."),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("rd-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"event\":\"progress\"") && it.contains("Thinking about the answer") })
    }

    @Test
    fun `ToolCallDelta frames are silently accumulated`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ToolCallDelta("call-1", "search", "{\"q\":"),
            StreamFrame.ToolCallDelta("call-1", null, "\"test\"}"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("tcd-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        // ToolCallDelta should NOT produce agent-step events
        assertTrue(messages.none { it.contains("\"event\":\"agent-step\"") && it.contains("search") })
    }

    @Test
    fun `End frame triggers session complete`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Done"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("end-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"type\":\"complete\"") })
    }

    @Test
    fun `events on closed session are ignored`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.TextDelta("Should be ignored"),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("closed-session", resource)
        session.complete()
        reset(broadcaster)

        @Suppress("UNCHECKED_CAST")
        `when`(broadcaster.broadcast(any<Any>(), any<Set<AtmosphereResource>>()))
            .thenReturn(mock(Future::class.java) as Future<Any>)

        KoogAgentRuntime().execute(context(), session)

        // Session was already closed, no text-delta should get through
        verify(broadcaster, atMost(1)).broadcast(any<Any>(), any<Set<AtmosphereResource>>())
    }

    // ── Full lifecycle ──

    @Test
    fun `full streaming lifecycle with mixed frame types`() {
        KoogAgentRuntime.setPromptExecutor(fakeExecutor(
            StreamFrame.ReasoningDelta("Let me think..."),
            StreamFrame.TextDelta("Hello, "),
            StreamFrame.TextDelta("world!"),
            StreamFrame.TextDelta(" The time is 3pm."),
            StreamFrame.End()
        ))

        val session = StreamingSessions.start("lifecycle-session", resource)
        KoogAgentRuntime().execute(context("What time is it?"), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("Let me think") })
        assertTrue(messages.any { it.contains("Hello, ") })
        assertTrue(messages.any { it.contains("world!") })
        assertTrue(messages.any { it.contains("The time is 3pm") })
        assertTrue(messages.last().contains("\"type\":\"complete\""))
    }

    @Test
    fun `executor error produces session error`() {
        KoogAgentRuntime.setPromptExecutor(failingExecutor(RuntimeException("LLM unavailable")))

        val session = StreamingSessions.start("error-session", resource)
        KoogAgentRuntime().execute(context(), session)

        val messages = capturedMessages()
        assertTrue(messages.any { it.contains("\"type\":\"error\"") || it.contains("LLM unavailable") })
    }

    // ── Multi-modal parts: VISION / AUDIO / MULTI_MODAL bridge ──

    private fun contextWithParts(
        message: String,
        parts: List<org.atmosphere.ai.Content>
    ): AgentExecutionContext {
        return AgentExecutionContext(
            message, null, null, null, null, null, null,
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            emptyMap<String, Any>(),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null, emptyList<org.atmosphere.ai.AgentLifecycleListener>(),
            parts,
            org.atmosphere.ai.approval.ToolApprovalPolicy.annotated()
        )
    }

    /**
     * Regression test for the Koog multi-modal bridge: a
     * [org.atmosphere.ai.Content.Image] part on the context must land on
     * the outgoing Koog [Prompt] as a [ai.koog.prompt.message.ContentPart.Image]
     * attached to the current user message. Without this bridge, declaring
     * `VISION` on [KoogAgentRuntime] would be runtime-truth dishonest
     * (Correctness Invariant #5).
     */
    @Test
    fun `Image part is translated to Koog ContentPart Image on the user message`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte()
        )
        val ctx = contextWithParts(
            "Describe this image.",
            listOf(org.atmosphere.ai.Content.Image(pngBytes, "image/png"))
        )

        val session = StreamingSessions.start("vision-session", resource)
        KoogAgentRuntime().execute(ctx, session)

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null, "executor should have been invoked")

        val userMessages = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>()
        assertTrue(userMessages.isNotEmpty(), "at least one user message expected")

        val last = userMessages.last()
        val messageParts = last.parts
        val imageParts = messageParts.filterIsInstance<ai.koog.prompt.message.ContentPart.Image>()
        assertTrue(
            imageParts.isNotEmpty(),
            "user message must carry at least one ContentPart.Image — multi-modal bridge failed"
        )
        val firstImage = imageParts.first()
        assertEquals("image/png", firstImage.mimeType)

        val attachment = firstImage.content
        assertTrue(
            attachment is ai.koog.prompt.message.AttachmentContent.Binary.Base64,
            "image attachment should use AttachmentContent.Binary.Base64 encoding"
        )
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        assertEquals(expectedBase64,
            (attachment as ai.koog.prompt.message.AttachmentContent.Binary.Base64).base64)
    }

    /**
     * Regression test for the Koog audio bridge: a [org.atmosphere.ai.Content.Audio]
     * part must land as a [ai.koog.prompt.message.ContentPart.Audio] on the user
     * message. Koog 0.7 exposes a dedicated Audio ContentPart; declaring the
     * `AUDIO` capability would be dishonest without this path.
     */
    @Test
    fun `Audio part is translated to Koog ContentPart Audio on the user message`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x20, 0x00, 0x00, 0x00,
            0x57, 0x41, 0x56, 0x45, 0x66, 0x6D, 0x74, 0x20)
        val ctx = contextWithParts(
            "What does this audio say?",
            listOf(org.atmosphere.ai.Content.Audio(wavBytes, "audio/wav"))
        )

        val session = StreamingSessions.start("audio-session", resource)
        KoogAgentRuntime().execute(ctx, session)

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null)

        val last = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().last()
        val audioParts = last.parts.filterIsInstance<ai.koog.prompt.message.ContentPart.Audio>()
        assertTrue(audioParts.isNotEmpty(), "user message must carry ContentPart.Audio")
        assertEquals("audio/wav", audioParts.first().mimeType)
    }

    /**
     * Text-only context must NOT go through the multi-modal user(text, parts)
     * overload — we preserve the plain user(text) wire shape for existing
     * clients so the Koog provider drivers don't unexpectedly observe a
     * multi-content-array message for a simple text prompt.
     */
    @Test
    fun `text-only context emits a plain user message without parts`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val session = StreamingSessions.start("text-session", resource)
        KoogAgentRuntime().execute(context("Just text"), session)

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null)

        val last = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().last()
        // hasAttachments() is Koog's own predicate that returns true when the
        // message carries any non-Text ContentPart. A text-only dispatch must
        // leave this false so the wire format matches the pre-Phase-4 path.
        assertTrue(
            !last.hasAttachments(),
            "text-only dispatch must not attach multi-modal parts"
        )
    }

    // ── Prompt caching: CacheHint → CacheControl.Bedrock.* ──

    private fun contextWithCacheHint(
        policy: org.atmosphere.ai.llm.CacheHint.CachePolicy,
        ttl: java.time.Duration? = null
    ): AgentExecutionContext {
        val hint = org.atmosphere.ai.llm.CacheHint(
            policy,
            java.util.Optional.of("test-key"),
            if (ttl != null) java.util.Optional.of(ttl) else java.util.Optional.empty()
        )
        return AgentExecutionContext(
            "hello", null, null, null, null, null, null,
            emptyList<org.atmosphere.ai.tool.ToolDefinition>(), null, null,
            emptyList<org.atmosphere.ai.ContextProvider>(),
            mapOf<String, Any>(org.atmosphere.ai.llm.CacheHint.METADATA_KEY to hint),
            emptyList<org.atmosphere.ai.llm.ChatMessage>(),
            null, null
        )
    }

    /**
     * CONSERVATIVE policy with no explicit TTL must attach
     * [ai.koog.prompt.message.CacheControl.Bedrock.FiveMinutes] to the
     * outgoing user message. This is the single user-visible contract of
     * the Koog PROMPT_CACHING capability — Bedrock-backed Koog models will
     * see the cache control on the wire; other providers will silently
     * ignore it (matching Spring AI / LangChain4j's OpenAI-only behavior).
     */
    @Test
    fun `conservative cache hint attaches FiveMinutes Bedrock cache control`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val session = StreamingSessions.start("cache-conservative", resource)
        KoogAgentRuntime().execute(
            contextWithCacheHint(org.atmosphere.ai.llm.CacheHint.CachePolicy.CONSERVATIVE),
            session
        )

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null)
        val last = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().last()
        val cache = last.cacheControl
        assertTrue(
            cache is ai.koog.prompt.message.CacheControl.Bedrock.FiveMinutes,
            "CONSERVATIVE hint must map to CacheControl.Bedrock.FiveMinutes; got: $cache"
        )
    }

    /**
     * AGGRESSIVE policy must attach
     * [ai.koog.prompt.message.CacheControl.Bedrock.OneHour] — the longer
     * Bedrock TTL bucket for caller-intent "maximum reuse".
     */
    @Test
    fun `aggressive cache hint attaches OneHour Bedrock cache control`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val session = StreamingSessions.start("cache-aggressive", resource)
        KoogAgentRuntime().execute(
            contextWithCacheHint(org.atmosphere.ai.llm.CacheHint.CachePolicy.AGGRESSIVE),
            session
        )

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null)
        val last = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().last()
        assertTrue(
            last.cacheControl is ai.koog.prompt.message.CacheControl.Bedrock.OneHour,
            "AGGRESSIVE hint must map to CacheControl.Bedrock.OneHour"
        )
    }

    /**
     * NONE policy must leave the outgoing user message unchanged —
     * specifically, no cache control attached. Proves the zero-cache fast
     * path still works and doesn't accidentally attach a control just
     * because the metadata slot is present.
     */
    @Test
    fun `none policy leaves user message without cache control`() {
        val capturing = CapturingExecutor()
        KoogAgentRuntime.setPromptExecutor(capturing)

        val session = StreamingSessions.start("cache-none", resource)
        KoogAgentRuntime().execute(
            contextWithCacheHint(org.atmosphere.ai.llm.CacheHint.CachePolicy.NONE),
            session
        )

        val prompt = capturing.capturedPrompt
        assertTrue(prompt != null)
        val last = prompt!!.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().last()
        assertTrue(
            last.cacheControl == null,
            "NONE policy must leave cacheControl unset on the user message"
        )
    }
}
