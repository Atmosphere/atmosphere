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
package org.atmosphere.agui.runtime;

import org.atmosphere.agui.annotation.AgUiAction;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RUN_ERROR ends an AG-UI run on every path: an {@link AiEvent.Error} emitted
 * through {@code emit()}, {@code error()}, an action that throws, and the
 * pipeline-driven {@code stream()} mode. After it no RUN_FINISHED and no other
 * event reaches the wire, a later {@code complete()} or {@code error()} is a
 * no-op, and the wrapped session is terminated exactly once.
 */
class AgUiRunErrorTerminalTest {

    enum Kind { STREAMING_SESSION, RESOURCE_SESSION }

    /** One session under test, its wire and the terminal calls its delegate saw. */
    private record Fixture(StreamingSession session, StringWriter output, List<String> delegateCalls) {
        List<String> types() {
            return AgUiRunErrorTerminalTest.types(output.toString());
        }
    }

    private static Fixture fixture(Kind kind) throws Exception {
        var output = new StringWriter();
        var calls = new CopyOnWriteArrayList<String>();
        var delegate = new RecordingSession(calls);
        var runContext = new RunContext("t1", "r1", List.of(), null, null, null);
        StreamingSession session = switch (kind) {
            case STREAMING_SESSION -> {
                var response = mock(AtmosphereResponse.class);
                when(response.getWriter()).thenReturn(new PrintWriter(output));
                yield new AgUiStreamingSession(delegate, response, runContext);
            }
            case RESOURCE_SESSION -> new AgUiHandler.ResourceAgUiStreamingSession(
                    delegate, new AgUiHandler.SseWriter(new PrintWriter(output)), runContext);
        };
        return new Fixture(session, output, calls);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void emittedErrorEndsTheRun(Kind kind) throws Exception {
        var f = fixture(kind);
        f.session().send("partial ");
        f.session().emit(new AiEvent.Error("rate limited", "rate_limit", true));
        f.session().send("too late");
        f.session().emit(new AiEvent.ToolStart("weather", java.util.Map.of()));
        f.session().complete("done");
        f.session().error(new IllegalStateException("again"));

        assertEquals(List.of("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "RUN_ERROR"), f.types(),
                "an emitted Error is terminal: nothing, RUN_FINISHED included, may follow RUN_ERROR");
        var runError = f.output().toString();
        assertTrue(runError.contains("\"code\":\"rate_limit\"") && runError.contains("\"runId\":\"r1\""),
                "RUN_ERROR carries the run id and the event's code: " + runError);
        assertTrue(f.session().isClosed(), "the session is closed after RUN_ERROR");
        assertTrue(f.session().hasErrored(), "the session reports the error it ended with");
        assertEquals(List.of("error"), f.delegateCalls(),
                "the wrapped session is terminated exactly once, with an error");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void errorIsCloseOnce(Kind kind) throws Exception {
        var f = fixture(kind);
        f.session().error(new IllegalStateException("boom"));
        f.session().complete();
        f.session().error(new IllegalStateException("again"));
        f.session().emit(new AiEvent.Error("third", null, false));

        assertEquals(List.of("RUN_ERROR"), f.types(), "only the first terminal call writes a frame");
        assertTrue(f.output().toString().contains("boom"));
        assertEquals(List.of("error"), f.delegateCalls(), "the wrapped session is terminated once");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void errorAfterCompleteIsANoOp(Kind kind) throws Exception {
        var f = fixture(kind);
        f.session().complete();
        f.session().error(new IllegalStateException("late"));
        f.session().emit(new AiEvent.Error("late", "x", false));

        assertEquals(List.of("RUN_FINISHED"), f.types(), "no RUN_ERROR after RUN_FINISHED");
        assertFalse(f.session().hasErrored(), "a completed run did not error");
        assertEquals(List.of("complete"), f.delegateCalls(), "the wrapped session is completed once");
    }

    // ── Through the handler: action return, action exception, stream mode ──

    public static class EmitErrorThenReturn {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.send("partial ");
            session.emit(new AiEvent.Error("upstream failed", "upstream", false));
        }
    }

    public static class CompleteThenThrow {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.send("done");
            session.complete();
            throw new IllegalStateException("after the run finished");
        }
    }

    public static class ErrorThenThrow {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.error(new IllegalStateException("first"));
            throw new IllegalStateException("second");
        }
    }

    public static class Streams {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.stream("hello");
        }
    }

    @Test
    void emittedErrorIsNotFollowedByTheHandlersCompletion() throws Exception {
        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "RUN_ERROR"),
                types(run(EmitErrorThenReturn.class, null)),
                "the handler must not complete a run an emitted Error already ended");
    }

    @Test
    void anExceptionAfterCompletionWritesNoRunError() throws Exception {
        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END", "RUN_FINISHED"),
                types(run(CompleteThenThrow.class, null)),
                "an action that throws after complete() must not append RUN_ERROR to a finished run");
    }

    @Test
    void anExceptionAfterErrorWritesOneRunError() throws Exception {
        var wire = run(ErrorThenThrow.class, null);
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), types(wire), "one RUN_ERROR per run");
        assertTrue(wire.contains("first") && !wire.contains("second"), wire);
    }

    @Test
    void streamModeEndsOnAnEmittedErrorLikeTheDirectMode() throws Exception {
        var pipeline = mock(AiPipeline.class);
        doAnswer(invocation -> {
            StreamingSession session = invocation.getArgument(2);
            session.send("partial ");
            session.emit(new AiEvent.Error("model failed", "model", false));
            session.complete();
            return null;
        }).when(pipeline).execute(anyString(), anyString(), any(StreamingSession.class));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "RUN_ERROR"),
                types(run(Streams.class, pipeline)),
                "a pipeline-driven run ends on RUN_ERROR exactly like a direct emit");
    }

    private static String run(Class<?> endpointType, AiPipeline pipeline) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(request.getMethod()).thenReturn("POST");
        when(request.getReader()).thenReturn(new BufferedReader(
                new StringReader("{\"threadId\":\"t1\",\"runId\":\"r1\"}")));
        var output = new StringWriter();
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.getResponse()).thenReturn(servletResponse);
        when(servletResponse.getWriter()).thenReturn(new PrintWriter(output));

        var handler = new AgUiHandler(endpointType.getDeclaredConstructor().newInstance(),
                endpointType.getMethod("onRun", RunContext.class, StreamingSession.class), pipeline);
        // handlePost joins the run thread, so the stream is complete on return.
        handler.onRequest(resource);
        return output.toString();
    }

    private static List<String> types(String wire) {
        var types = new ArrayList<String>();
        for (var line : wire.split("\n")) {
            if (line.startsWith("event: ")) {
                types.add(line.substring(7).trim());
            }
        }
        return types;
    }

    /** Records the terminal calls the AG-UI session forwards to it. */
    private static final class RecordingSession implements StreamingSession {
        private final List<String> calls;

        RecordingSession(List<String> calls) {
            this.calls = calls;
        }

        @Override public String sessionId() { return "recording"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { calls.add("complete"); }
        @Override public void complete(String summary) { calls.add("complete"); }
        @Override public void error(Throwable t) { calls.add("error"); }
        @Override public boolean isClosed() { return !calls.isEmpty(); }
        @Override public void emit(AiEvent event) {
            if (event instanceof AiEvent.Error) {
                calls.add("emit-error");
            }
        }
        @Override public void sendContent(Content content) { }
    }
}
