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

import org.atmosphere.ai.approval.ApprovalResolution;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.resume.RunEventCapturingSession;
import org.atmosphere.ai.resume.RunEventReplayBuffer;
import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.ai.resume.RunRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A page reloaded while a {@code @RequiresApproval} tool waits for its answer
 * (spring-boot-ai-tools, 2026-10-20): the reloaded page must get the approval
 * card back, be able to answer it from its new connection, and a wait the
 * turn's own limit cuts short must end with an approval-specific message
 * rather than a generic prompt timeout.
 */
class ApprovalAcrossReloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiStreamingSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            AiStreamingSession.removeActiveSession(session);
        }
        DefaultStreamingSession.cleanupResource("client-1");
    }

    private record Run(RunRegistry registry, String runId, AiStreamingSession session) {
    }

    /** A run whose tool waits for approval {@code apr-1}, its page gone. */
    private Run runAwaitingApproval() {
        var registry = new RunRegistry();
        var run = registry.register("ai-tools", "anonymous", "client-1",
                new ExecutionHandle.Settable(() -> { }), RunEventReplayBuffer.forRun(null, null), null);
        var leaf = new DefaultStreamingSession("session-1", client(), false);
        var capture = new RunEventCapturingSession(leaf, run.replayBuffer());
        var resource = client();
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        session = new AiStreamingSession(capture, mock(AgentRuntime.class), "", null, List.of(), resource);
        AiStreamingSession.registerActive(session);
        session.setRunId(run.runId());
        session.approvalRegistry().register(new PendingApproval("apr-1", "reset_city_data",
                Map.of("city", "Tokyo"), "Reset Tokyo?", "session-1", Instant.now().plusSeconds(60)));
        session.emit(new AiEvent.ToolStart("reset_city_data", Map.of("city", "Tokyo")));
        // The page reloads: its connection is gone when the approval is asked for.
        DefaultStreamingSession.cleanupResource("client-1");
        session.emit(new AiEvent.ApprovalRequired("apr-1", "reset_city_data", Map.of("city", "Tokyo"),
                "Reset Tokyo?", 60));
        return new Run(registry, run.runId(), session);
    }

    @Test
    void aReloadedPageGetsThePendingApprovalCardBack() throws Exception {
        var run = runAwaitingApproval();

        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), "reloaded",
                AtmosphereResource.TRANSPORT.WEBSOCKET, replay), run.registry());

        assertEquals(List.of("tool-start", "approval-required"), events(replay));
    }

    @Test
    void anAnsweredApprovalIsNotReplayedAsPending() throws Exception {
        var run = runAwaitingApproval();
        assertTrue(AiStreamingSession.tryResolveApprovalForResource("client-1", "/__approval/apr-1/approve"));

        var replay = new ArrayList<String>();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), "reloaded",
                AtmosphereResource.TRANSPORT.WEBSOCKET, replay), run.registry());

        assertEquals(List.of("tool-start"), events(replay), "no card to answer any more");
    }

    @Test
    void aPageReattachedOverWebSocketAnswersTheApprovalFromItsNewConnection() throws Exception {
        var run = runAwaitingApproval();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), "reloaded-ws",
                AtmosphereResource.TRANSPORT.WEBSOCKET, new ArrayList<>()), run.registry());

        assertTrue(AiStreamingSession.tryResolveApprovalForResource("reloaded-ws", "/__approval/apr-1/approve"));
        assertFalse(run.session().approvalRegistry().isPending("apr-1"));
    }

    @Test
    void aPageReattachedOverLongPollingAnswersTheApprovalFromItsNewConnection() throws Exception {
        var run = runAwaitingApproval();
        RunReattachSupport.replayPendingRun(reconnected(run.runId(), "reloaded-lp",
                AtmosphereResource.TRANSPORT.LONG_POLLING, new ArrayList<>()), run.registry());

        assertTrue(AiStreamingSession.tryResolveApprovalForResource("reloaded-lp", "/__approval/apr-1/approve"),
                "the approval answered from the reloaded page's connection");
    }

    @Test
    void anApprovalWaitsNoLongerThanItsTurn() {
        var tool = ToolDefinition.builder("reset_city_data", "Reset a city's data")
                .parameter("city", "the city", "string")
                .executor(args -> "reset")
                .requiresApproval("Reset?", 300)
                .build();
        var turn = mock(StreamingSession.class);
        var deadline = Instant.now().plusSeconds(20);
        when(turn.turnDeadline()).thenReturn(Optional.of(deadline));
        var asked = new AtomicReference<PendingApproval>();
        ApprovalStrategy strategy = new ApprovalStrategy() {
            @Override
            public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession s) {
                asked.set(approval);
                return ApprovalOutcome.APPROVED;
            }
        };

        ToolExecutionHelper.executeWithApproval("reset_city_data", tool, Map.of("city", "Tokyo"), turn, strategy);

        var expiry = asked.get().expiresAt();
        assertTrue(!expiry.isAfter(deadline), "the approval outlives its turn: " + expiry + " > " + deadline);
        assertTrue(expiry.isAfter(Instant.now().plus(Duration.ofSeconds(10))), "but waits most of it: " + expiry);
    }

    @Test
    void anApprovalTheTurnsLimitCutsShortEndsTheTurnWithAnApprovalMessage() {
        var ran = new AtomicInteger();
        var tool = ToolDefinition.builder("reset_city_data", "Reset a city's data")
                .parameter("city", "the city", "string")
                .executor(args -> {
                    ran.incrementAndGet();
                    return "reset";
                })
                .requiresApproval("Reset?", 300)
                .build();
        var turn = mock(StreamingSession.class);
        var failure = new AtomicReference<Throwable>();
        when(turn.turnDeadline()).thenReturn(Optional.of(Instant.now().plusMillis(500)));
        org.mockito.Mockito.doAnswer(i -> {
            failure.set(i.getArgument(0));
            return null;
        }).when(turn).error(any());
        ApprovalStrategy strategy = new ApprovalStrategy() {
            @Override
            public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession s) {
                return ApprovalOutcome.TIMED_OUT;
            }

            @Override
            public ApprovalResolution awaitApprovalDetailed(PendingApproval approval, StreamingSession s) {
                return ApprovalResolution.timedOut();
            }
        };

        var result = ToolExecutionHelper.executeWithApproval("reset_city_data", tool, Map.of("city", "Tokyo"),
                turn, strategy);

        assertEquals(0, ran.get());
        assertTrue(result.contains("timeout"), result);
        assertInstanceOf(AiException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("Approval for tool 'reset_city_data'"),
                failure.get().getMessage());
    }

    private static List<String> events(List<String> frames) {
        return frames.stream().map(f -> {
            JsonNode node = MAPPER.readTree(f);
            return node.has("event") ? node.path("event").asString() : node.path("type").asString();
        }).filter(kind -> !kind.isEmpty() && !"metadata".equals(kind)).toList();
    }

    @SuppressWarnings("unchecked")
    private static AtmosphereResource client() {
        var resource = mock(AtmosphereResource.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.uuid()).thenReturn("client-1");
        when(resource.transport()).thenReturn(AtmosphereResource.TRANSPORT.WEBSOCKET);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(broadcaster.broadcast(any(RawMessage.class), anySet())).thenReturn(null);
        return resource;
    }

    private static AtmosphereResource reconnected(String runId, String uuid, AtmosphereResource.TRANSPORT transport,
                                                  List<String> writes) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        var sink = new StringWriter();
        var writer = new PrintWriter(sink) {
            @Override
            public void flush() {
                super.flush();
                if (!sink.toString().isEmpty()) {
                    for (var line : sink.toString().split("\n")) {
                        if (!line.isBlank()) {
                            writes.add(line);
                        }
                    }
                    sink.getBuffer().setLength(0);
                }
            }
        };
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(transport);
        when(request.getAttribute(anyString())).thenAnswer(i ->
                RunReattachSupport.RUN_ID_ATTRIBUTE.equals(i.getArgument(0)) ? runId : null);
        when(resource.getResponse()).thenReturn(response);
        when(response.getWriter()).thenReturn(writer);
        return resource;
    }
}
