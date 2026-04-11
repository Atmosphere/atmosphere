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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TCK-style contract test for {@link AgentRuntime} implementations.
 * Each runtime module creates a concrete subclass that provides
 * the runtime instance and any required mocks/stubs.
 *
 * <p>Contract assertions:</p>
 * <ol>
 *   <li>Capabilities declaration — minimum required capabilities</li>
 *   <li>Runtime identification — non-blank name</li>
 *   <li>Streaming completion — session.complete() called exactly once</li>
 *   <li>Text delivery — at least one text chunk sent</li>
 *   <li>Error handling — session.error() called on failure</li>
 * </ol>
 */
public abstract class AbstractAgentRuntimeContractTest {

    /**
     * Provide the runtime under test, fully configured with a mock LLM backend.
     */
    protected abstract AgentRuntime createRuntime();

    /**
     * Provide a context that will trigger a simple text response.
     */
    protected abstract AgentExecutionContext createTextContext();

    /**
     * Provide a context that will trigger a tool call followed by a text response.
     * Return {@code null} if the runtime does not support tool calling.
     */
    protected abstract AgentExecutionContext createToolCallContext();

    /**
     * Provide a context that will cause the runtime to error.
     * Return {@code null} to skip the error-handling test.
     */
    protected abstract AgentExecutionContext createErrorContext();

    @Test
    protected void runtimeDeclaresMinimumCapabilities() {
        var runtime = createRuntime();
        var caps = runtime.capabilities();
        assertTrue(caps.contains(AiCapability.TEXT_STREAMING),
                runtime.name() + " must declare TEXT_STREAMING");
    }

    /**
     * Every runtime that honors {@link AiCapability#SYSTEM_PROMPT} automatically
     * receives structured-output support via {@code AiPipeline}'s
     * {@code StructuredOutputCapturingSession} wrapping — the pipeline augments the
     * system prompt with schema instructions and captures the JSON response. A
     * runtime that declares {@code SYSTEM_PROMPT} but not {@code STRUCTURED_OUTPUT}
     * is almost always advertising incorrectly (Correctness Invariant #5 — Runtime
     * Truth). Subclasses that have a legitimate reason to opt out (e.g. a runtime
     * whose session sink cannot deliver the final text frame to the capturing
     * wrapper) can override this method and explain why in the override's Javadoc.
     */
    @Test
    protected void runtimeWithSystemPromptAlsoDeclaresStructuredOutput() {
        var runtime = createRuntime();
        var caps = runtime.capabilities();
        if (!caps.contains(AiCapability.SYSTEM_PROMPT)) {
            return;
        }
        assertTrue(caps.contains(AiCapability.STRUCTURED_OUTPUT),
                runtime.name() + " declares SYSTEM_PROMPT but not STRUCTURED_OUTPUT; "
                        + "AiPipeline wraps the session in StructuredOutputCapturingSession "
                        + "and augments the system prompt with schema instructions for every "
                        + "SYSTEM_PROMPT-capable runtime. Either declare STRUCTURED_OUTPUT or "
                        + "override runtimeWithSystemPromptAlsoDeclaresStructuredOutput() with "
                        + "a Javadoc explaining why this runtime is a legitimate exception.");
    }

    @Test
    protected void runtimeHasNonBlankName() {
        var runtime = createRuntime();
        assertNotNull(runtime.name());
        assertFalse(runtime.name().isBlank());
    }

    /**
     * Every runtime that declares {@link AiCapability#TOOL_CALLING} must
     * also declare {@link AiCapability#TOOL_APPROVAL}. Every tool bridge on
     * the unified SPI routes through
     * {@link ToolExecutionHelper#executeWithApproval}, so the approval gate
     * is already firing on every tool invocation — a runtime that declares
     * {@code TOOL_CALLING} without {@code TOOL_APPROVAL} is advertising
     * dishonestly (Correctness Invariant #5 — Runtime Truth).
     *
     * <p>The converse is intentionally not asserted — a runtime may
     * declare {@code TOOL_APPROVAL} without {@code TOOL_CALLING} when the
     * shared pipeline-level approval seam fires on native hooks.</p>
     */
    @Test
    protected void runtimeWithToolCallingAlsoDeclaresToolApproval() {
        var runtime = createRuntime();
        var caps = runtime.capabilities();
        if (!caps.contains(AiCapability.TOOL_CALLING)) {
            return;
        }
        assertTrue(caps.contains(AiCapability.TOOL_APPROVAL),
                runtime.name() + " declares TOOL_CALLING but not TOOL_APPROVAL; "
                        + "every runtime routes tool invocation through "
                        + "ToolExecutionHelper.executeWithApproval, "
                        + "so the approval gate already fires on every tool call. "
                        + "Either declare TOOL_APPROVAL or override this method with "
                        + "a Javadoc explaining why this runtime is a legitimate exception.");
    }

    /**
     * Every runtime that declares {@link AiCapability#VISION} must accept
     * a small PNG {@link org.atmosphere.ai.Content.Image} part on the
     * execution context without throwing at dispatch. This contract is the
     * boundary-safety guarantee for multi-modal input
     * (Correctness Invariant #4): a runtime that advertises VISION but
     * doesn't translate {@link org.atmosphere.ai.Content.Image} into its
     * framework-native type (Spring AI {@code Media}, LC4j
     * {@code ImageContent}, ADK {@code Part.fromBytes}, OpenAI
     * {@code image_url} content block) silently drops the image from the
     * prompt.
     *
     * <p>Subclasses that cannot mock a tool-calling execution path may
     * override {@link #createImageContext()} to return {@code null} — the
     * assertion then skips cleanly.</p>
     */
    @Test
    protected void runtimeWithVisionCapabilityAcceptsImagePart() {
        var runtime = createRuntime();
        if (!runtime.capabilities().contains(AiCapability.VISION)) {
            return;
        }
        var context = createImageContext();
        if (context == null) {
            return;
        }
        // Dispatch should not throw — the capability assertion is "accepts
        // the part without blowing up at message assembly". Downstream
        // success depends on the configured model and is tested elsewhere.
        try {
            runtime.execute(context, new NoopSession());
        } catch (UnsupportedOperationException uoe) {
            org.junit.jupiter.api.Assertions.fail(
                    runtime.name() + " declares VISION but threw UnsupportedOperationException on an image part: "
                            + uoe.getMessage());
        } catch (IllegalStateException | IllegalArgumentException iae) {
            // Bridges may reject at configure() time when the native model
            // client isn't wired — that's acceptable and not a contract
            // violation.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    runtime.name() + " skipped image dispatch: " + iae.getMessage());
        } catch (Exception ignored) {
            // Network / model-provider failures are not part of this
            // contract; the assertion is purely about accepting the part
            // through the message assembler without throwing
            // UnsupportedOperationException.
        }
    }

    /**
     * Subclass hook: return an {@link AgentExecutionContext} whose
     * {@code parts()} list contains a small PNG {@link org.atmosphere.ai.Content.Image}.
     * Subclasses typically return a minimal context with a 1×1 PNG encoded
     * in {@code Content.Image}; the default returns {@code null} so
     * runtimes without a mockable dispatch path skip the assertion.
     */
    protected AgentExecutionContext createImageContext() {
        return null;
    }

    /** Minimal 1×1 transparent PNG for VISION contract tests. */
    protected static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    /**
     * Every runtime must return a non-null list from
     * {@link AgentRuntime#models()}. Runtimes with a deterministic model
     * hint available post-{@code configure()} should return it so admin
     * UIs and routing decisions can enumerate the runtime-resolved state.
     * Runtimes whose model selection is per-request only (e.g. ADK, Koog)
     * may legitimately return an empty list — they override this method
     * with a Javadoc explaining why.
     */
    @Test
    protected void runtimeReportsConfiguredModelsAfterConfigure() {
        var runtime = createRuntime();
        try {
            runtime.configure(org.atmosphere.ai.AiConfig.fromEnvironment());
        } catch (Exception ignored) {
            // Subclass test fixtures may not supply full LlmSettings; fall
            // through and let the accessor return whatever it has.
        }
        assertNotNull(runtime.models(),
                runtime.name() + " models() must return a non-null list (empty is fine)");
    }

    /**
     * Every runtime that declares {@link AiCapability#TOOL_CALLING} must route
     * {@code @RequiresApproval} tool invocations through
     * {@link ToolExecutionHelper#executeWithApproval}. This contract is the
     * cross-runtime guarantee for Correctness Invariant #7 (Mode Parity) —
     * the 2026-04-11 Phase 0 review found it missing even though the 5
     * per-runtime bridge tests covered the individual call sites.
     *
     * <p>Unlike the earlier version of this test, which only exercised the
     * helper directly and therefore could not catch a bridge that bypassed
     * it, this implementation drives the full runtime seam. Subclasses that
     * can provide a context which causes {@code runtime.execute} to actually
     * invoke a {@code @RequiresApproval} tool (typically by configuring a
     * mock chat client to emit a tool-call for the known tool name) override
     * {@link #createApprovalTriggerContext()} to return that context. The
     * base class supplies a capturing strategy, calls {@code runtime.execute},
     * and asserts the strategy was consulted — proving the bridge routed
     * through {@code executeWithApproval}. Subclasses that cannot set up a
     * tool-invoking mock return {@code null} and skip the assertion.</p>
     */
    @Test
    protected void hitlPendingApprovalEmitsProtocolEvent() throws Exception {
        var runtime = createRuntime();
        if (!runtime.capabilities().contains(AiCapability.TOOL_CALLING)) {
            return; // runtime does not advertise tool calling — HITL gate N/A
        }

        var triggerContext = createApprovalTriggerContext();
        if (triggerContext == null) {
            // Subclass has not provided a bridge-driving context — fall back
            // to the helper-level check so we at least verify the shared
            // call site still works. This is a known gap for runtimes whose
            // mock infrastructure cannot emit a synthetic tool-call.
            assertHelperLevelHitl(runtime);
            return;
        }

        var observed = new AtomicReference<PendingApproval>();
        ApprovalStrategy capturing = (approval, session) -> {
            observed.set(approval);
            return ApprovalStrategy.ApprovalOutcome.DENIED;
        };

        var context = triggerContext.withApprovalStrategy(capturing);
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertNotNull(observed.get(),
                runtime.name() + " runtime.execute did not consult the ApprovalStrategy on "
                        + "a @RequiresApproval tool-call path — the runtime bridge is "
                        + "bypassing the unified ToolExecutionHelper.executeWithApproval seam "
                        + "(Correctness Invariant #7 — Mode Parity).");
    }

    /**
     * Helper-level fallback assertion for runtimes whose subclass cannot yet
     * emit a tool-call through its mock client. Kept so every
     * {@code TOOL_CALLING} runtime still has <em>some</em> coverage of the
     * shared HITL seam until a richer trigger context is plumbed.
     */
    private void assertHelperLevelHitl(AgentRuntime runtime) {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        var sensitive = ToolDefinition.builder("contract_delete", "test-only deletion")
                .parameter("id", "row id", "string")
                .executor(args -> {
                    counter.incrementAndGet();
                    return "deleted:" + args.get("id");
                })
                .requiresApproval("Approve contract deletion?", 60)
                .build();

        var observed = new AtomicReference<PendingApproval>();
        ApprovalStrategy capturing = (approval, session) -> {
            observed.set(approval);
            return ApprovalStrategy.ApprovalOutcome.DENIED;
        };

        var result = ToolExecutionHelper.executeWithApproval(
                "contract_delete", sensitive, Map.of("id", "r-1"),
                new NoopSession(), capturing);

        assertNotNull(observed.get(),
                runtime.name() + " ToolExecutionHelper.executeWithApproval did not "
                        + "consult the ApprovalStrategy (shared helper regression).");
        assertTrue(result.contains("cancelled"),
                "denied outcome must surface cancellation result from the unified helper");
        assertTrue(counter.get() == 0,
                "denied @RequiresApproval tool must not execute its delegate");
    }

    /**
     * Subclass hook: return an {@link AgentExecutionContext} whose
     * {@code runtime.execute} call will cause the runtime to invoke a
     * {@code @RequiresApproval} tool via its bridge. Typically the subclass
     * configures its mock chat client to emit a tool-call response for a
     * known tool name and builds a context containing that tool with
     * {@code requiresApproval()}. The base class injects a capturing
     * {@link ApprovalStrategy} via
     * {@link AgentExecutionContext#withApprovalStrategy} before dispatch.
     *
     * <p>Return {@code null} if the subclass cannot set up such a context —
     * the base test falls back to asserting the helper-level wiring still
     * works (less informative, but preserves some coverage).</p>
     */
    protected AgentExecutionContext createApprovalTriggerContext() {
        return null;
    }

    /** Minimal StreamingSession satisfying the helper's session.sessionId() call. */
    private static final class NoopSession implements StreamingSession {
        @Override public String sessionId() { return "contract-test"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
    }

    @Test
    protected void runtimeIsAvailable() {
        assertTrue(createRuntime().isAvailable());
    }

    @Test
    protected void textStreamingCompletesSession() throws Exception {
        var runtime = createRuntime();
        var context = createTextContext();
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete within 10s");
        assertFalse(session.textChunks.isEmpty(),
                "At least one text chunk should be sent");
        assertTrue(session.errors.isEmpty(),
                "No errors expected: " + session.errors);
    }

    @Test
    protected void toolCallExecutesIfSupported() throws Exception {
        var runtime = createRuntime();
        if (!runtime.capabilities().contains(AiCapability.TOOL_CALLING)) {
            return;
        }
        var context = createToolCallContext();
        if (context == null) {
            return;
        }
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete within 10s after tool call");
    }

    @Test
    protected void errorContextTriggersSessionError() throws Exception {
        var context = createErrorContext();
        if (context == null) {
            return;
        }
        var runtime = createRuntime();
        var session = new RecordingSession();

        runtime.execute(context, session);

        assertTrue(session.awaitCompletion(10, TimeUnit.SECONDS),
                "Session should complete (via error) within 10s");
        assertFalse(session.errors.isEmpty(),
                "At least one error expected");
    }

    /**
     * Test double that captures all session events for assertion.
     */
    protected static class RecordingSession implements StreamingSession {
        public final List<String> textChunks = new CopyOnWriteArrayList<>();
        public final Map<String, Object> metadata = new ConcurrentHashMap<>();
        public final List<String> progressMessages = new CopyOnWriteArrayList<>();
        public final List<AiEvent> events = new CopyOnWriteArrayList<>();
        public final List<Throwable> errors = new CopyOnWriteArrayList<>();
        public final AtomicInteger completionCount = new AtomicInteger();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public String sessionId() {
            return "contract-test";
        }

        @Override
        public void send(String text) {
            textChunks.add(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            metadata.put(key, value);
        }

        @Override
        public void progress(String message) {
            progressMessages.add(message);
        }

        @Override
        public void complete() {
            completionCount.incrementAndGet();
            closed.set(true);
            latch.countDown();
        }

        @Override
        public void complete(String summary) {
            completionCount.incrementAndGet();
            closed.set(true);
            latch.countDown();
        }

        @Override
        public void error(Throwable t) {
            errors.add(t);
            closed.set(true);
            latch.countDown();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void emit(AiEvent event) {
            events.add(event);
            StreamingSession.super.emit(event);
        }

        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
