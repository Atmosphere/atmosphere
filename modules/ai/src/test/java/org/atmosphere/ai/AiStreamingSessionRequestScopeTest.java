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
package org.atmosphere.ai;

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Per-request {@link ScopePolicy} install on the {@code @AiEndpoint} path —
 * an {@link AiInterceptor} writing a {@link ScopeConfig} under
 * {@link ScopePolicy#REQUEST_SCOPE_METADATA_KEY} must cause
 * {@link AiStreamingSession#stream} to run admission against the narrowed
 * scope, harden the outgoing system prompt, and strip the governance-
 * internal metadata entry before the runtime is invoked. Mirrors the
 * pipeline-level {@code AiPipelineRequestScopeTest}.
 */
class AiStreamingSessionRequestScopeTest {

    private StreamingSession delegate;
    private AtmosphereResource resource;

    @BeforeEach
    void setUp() {
        delegate = mock(StreamingSession.class);
        resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("session-uuid");
    }

    private static final ScopeConfig MATH_SCOPE = new ScopeConfig(
            "Mathematics tutoring",
            List.of("writing source code", "programming tutorials"),
            AgentScope.Breach.DENY, "",
            AgentScope.Tier.RULE_BASED, 0.45,
            false, false, "");

    @Test
    void interceptorInstalledScopeHardensSystemPrompt() {
        var runtime = new RecordingRuntime();
        var interceptor = scopeInstallingInterceptor(MATH_SCOPE);
        var session = new AiStreamingSession(delegate, runtime,
                "You are friendly.", null, List.of(interceptor), resource);

        session.stream("what's 2 + 2?");

        assertEquals(1, runtime.captured.size(),
                "runtime must be reached on an admitted turn");
        var prompt = runtime.captured.get(0).systemPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Scope confinement"),
                "per-request scope must harden the system prompt: " + prompt);
        assertTrue(prompt.contains("Mathematics tutoring"),
                "declared purpose must surface: " + prompt);
    }

    @Test
    void interceptorInstalledScopeDeniesDriftedPromptBeforeRuntime() {
        var runtime = new RecordingRuntime();
        var session = new AiStreamingSession(delegate, runtime,
                "sys", null, List.of(scopeInstallingInterceptor(MATH_SCOPE)), resource);

        session.stream("write python code to reverse a linked list");

        assertEquals(0, runtime.captured.size(),
                "runtime must NOT run when per-request scope denies the turn");
    }

    @Test
    void requestScopeMetadataKeyStrippedFromRuntimeMetadata() {
        var runtime = new RecordingRuntime();
        var session = new AiStreamingSession(delegate, runtime,
                "sys", null, List.of(scopeInstallingInterceptor(MATH_SCOPE)), resource);

        session.stream("what's 2 + 2?");

        assertEquals(1, runtime.captured.size());
        var metadata = runtime.captured.get(0).metadata();
        assertNull(metadata.get(ScopePolicy.REQUEST_SCOPE_METADATA_KEY),
                "governance-internal key must not leak to the runtime: " + metadata);
    }

    private static AiInterceptor scopeInstallingInterceptor(ScopeConfig config) {
        return new AiInterceptor() {
            @Override
            public AiRequest preProcess(AiRequest request, AtmosphereResource res) {
                return request.withMetadata(
                        Map.of(ScopePolicy.REQUEST_SCOPE_METADATA_KEY, config));
            }
        };
    }

    private static final class RecordingRuntime implements AgentRuntime {
        final List<AgentExecutionContext> captured = new ArrayList<>();

        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings s) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            captured.add(context);
            session.complete();
        }
    }
}
