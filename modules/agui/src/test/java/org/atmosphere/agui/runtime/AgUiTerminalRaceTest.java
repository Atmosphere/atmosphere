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

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Emitters racing the terminal call: several threads emit text and tool
 * events while another calls {@code complete()} or {@code error()}. Every run
 * must end with exactly one terminal frame, nothing may follow it, and every
 * frame must be whole. All threads are released by one latch, so the terminal
 * call lands in the middle of the emissions without any sleep.
 */
class AgUiTerminalRaceTest {

    private static final int ITERATIONS = 300;
    private static final int EMITTERS = 4;
    private static final int EVENTS_PER_EMITTER = 40;
    private static final Set<String> TERMINAL = Set.of("RUN_FINISHED", "RUN_ERROR");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Kind { STREAMING_SESSION, RESOURCE_SESSION }

    enum Ending { COMPLETE, ERROR, EMITTED_COMPLETE, EMITTED_ERROR }

    private static ExecutorService pool;

    @BeforeAll
    static void startPool() {
        pool = Executors.newFixedThreadPool(EMITTERS + 1);
    }

    @AfterAll
    static void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the test pool must stop");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void nothingFollowsTheTerminalFrame(Kind kind) throws Exception {
        var terminalWritten = new AtomicInteger();
        for (int i = 0; i < ITERATIONS; i++) {
            var ending = Ending.values()[i % Ending.values().length];
            var output = new StringWriter();
            var session = session(kind, output);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int e = 0; e < EMITTERS; e++) {
                var emitter = e;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < EVENTS_PER_EMITTER; n++) {
                        switch (n % 4) {
                            case 0 -> session.send("t" + emitter + "-" + n + " ");
                            case 1 -> session.emit(new AiEvent.ToolStart("tool" + emitter, Map.of("n", n)));
                            case 2 -> session.emit(new AiEvent.ToolResult("tool" + emitter, "r" + n));
                            default -> session.emit(new AiEvent.AgentStep("s" + emitter, "step", Map.of()));
                        }
                    }
                    return null;
                }));
            }
            tasks.add(pool.submit(() -> {
                start.await();
                switch (ending) {
                    case COMPLETE -> session.complete();
                    case ERROR -> session.error(new IllegalStateException("boom"));
                    case EMITTED_COMPLETE -> session.emit(new AiEvent.Complete("done", Map.of()));
                    case EMITTED_ERROR -> session.emit(new AiEvent.Error("boom", "race", false));
                }
                return null;
            }));
            start.countDown();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            // Calls after the race are no-ops on a terminated session.
            session.complete();
            session.error(new IllegalStateException("late"));

            var types = types(output.toString());
            var terminals = types.stream().filter(TERMINAL::contains).count();
            assertEquals(1, terminals, "exactly one terminal frame, iteration " + i + " (" + ending + "): " + types);
            var last = types.get(types.size() - 1);
            assertTrue(TERMINAL.contains(last),
                    "nothing may follow the terminal frame, iteration " + i + " (" + ending + "): " + types);
            var expected = ending == Ending.COMPLETE || ending == Ending.EMITTED_COMPLETE
                    ? "RUN_FINISHED" : "RUN_ERROR";
            assertEquals(expected, last, "the terminal call decides the frame, iteration " + i);
            terminalWritten.incrementAndGet();
        }
        assertEquals(ITERATIONS, terminalWritten.get());
    }

    private static StreamingSession session(Kind kind, StringWriter output) throws Exception {
        var runContext = new RunContext("t1", "r1", List.of(), null, null, null);
        return switch (kind) {
            case STREAMING_SESSION -> {
                var response = mock(AtmosphereResponse.class);
                when(response.getWriter()).thenReturn(new PrintWriter(output));
                yield new AgUiStreamingSession(new QuietSession(), response, runContext);
            }
            case RESOURCE_SESSION -> new AgUiHandler.ResourceAgUiStreamingSession(
                    new QuietSession(), new AgUiHandler.SseWriter(new PrintWriter(output)), runContext);
        };
    }

    /** Frame types in wire order; every data line must be a whole JSON object. */
    private static List<String> types(String wire) throws Exception {
        var types = new ArrayList<String>();
        String type = null;
        for (var line : wire.split("\n")) {
            if (line.startsWith("event: ")) {
                assertEquals(null, type, "an event line without its data line: " + wire);
                type = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
                assertTrue(type != null && MAPPER.readTree(line.substring(6)).isObject(),
                        "a torn frame: " + line);
                types.add(type);
                type = null;
            }
        }
        return types;
    }

    private static final class QuietSession implements StreamingSession {
        @Override public String sessionId() { return "quiet"; }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }
        @Override public void emit(AiEvent event) { }
        @Override public void sendContent(Content content) { }
    }
}
