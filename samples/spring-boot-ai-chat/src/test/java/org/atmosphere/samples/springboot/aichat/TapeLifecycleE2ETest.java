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
package org.atmosphere.samples.springboot.aichat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeRecordingSession;
import org.atmosphere.ai.tape.TapeRun;
import org.atmosphere.ai.tape.TapeRunInfo;
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeStore;
import org.atmosphere.ai.tape.TapeSupport;
import org.atmosphere.ai.tape.TapeTrainingExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end proof of the whole session-tape lifecycle on the real
 * {@code spring-boot-ai-chat} application: <em>record → admin-read → replay →
 * extract</em>. This is the reliable, in-CI realization of the release-gate
 * "tape E2E smoke" — it drives the tape through the actual installed recorder
 * and reads it back over the real gated HTTP admin surface, without depending
 * on a live LLM or the WebSocket transport (both of which would make a
 * shell-driven release-gate boot flaky).
 *
 * <ol>
 *   <li><b>record</b> — a completed turn is taped through the app's installed
 *       {@link TapeRecordingSession} recorder (the same seam the AI pipeline
 *       uses), including the {@code input} step that a training example needs;</li>
 *   <li><b>admin</b> — {@code GET /api/admin/tape/runs} returns the run over the
 *       real gated admin filter (the recorded-content read gate is opened for
 *       the test via {@code content-read-auth-required=false});</li>
 *   <li><b>replay</b> — {@code GET /api/admin/tape/runs/{id}/replay}
 *       deterministically reconstructs the run's input and output;</li>
 *   <li><b>extract</b> — {@link TapeTrainingExtractor} folds the COMPLETED run
 *       into one (prompt → completion) chat-JSONL training example.</li>
 * </ol>
 *
 * <p>The tape store is in-memory here to keep the smoke hermetic; the on-disk
 * SQLite persistence path is pinned separately by
 * {@code TapeAutoConfigurationTest}.
 */
@SpringBootTest(
        classes = AiChatApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.ai.tape.enabled=true",
                // Hermetic: lifecycle is store-agnostic; SQLite persistence is covered elsewhere.
                "atmosphere.ai.tape.store=memory",
                // Open the recorded-content read gate so the E2E can read the tape back
                // (default-deny 401 otherwise — the gate itself is pinned by
                // AdminApiAuthFilterReadGateTest).
                "atmosphere.admin.content-read-auth-required=false",
                "atmosphere.auth.enabled=false",
                // Boot the AI config without a live key (demo runtime), warn instead of fail.
                "atmosphere.ai.fail-fast=false"
        })
class TapeLifecycleE2ETest {

    private static final String SYSTEM_PROMPT = "You are a concise assistant.";
    private static final String USER_MESSAGE = "Summarize the tape lifecycle in one line.";
    private static final String COMPLETION = "Recorded, read back, replayed, and extracted.";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void recordThenAdminReadThenReplayThenExtract() throws Exception {
        // ---- record: tape a completed turn through the app's installed recorder ----
        TapeStore store = TapeSupport.installedStore().orElseThrow(() ->
                new AssertionError("tape recorder not installed — sample tape wiring regressed"));

        StreamingSession session = TapeSupport.wrap(new ProbeSession(),
                TapeRunInfo.pipeline("tape-e2e", "demo-model", "demo"));
        // recordInput lives on the tape (pipeline) seam; without an input step the
        // extractor would skip the run as no-input, so drive it the way AiPipeline does.
        if (session instanceof TapeRecordingSession tape) {
            tape.recordInput(SYSTEM_PROMPT, List.of(), USER_MESSAGE);
        } else {
            throw new AssertionError("wrap did not return a recording session — tape not installed");
        }
        session.send(COMPLETION);
        session.complete();

        // ---- await: the writer thread drains asynchronously ----
        TapeRun run = awaitCompletedRun(store);
        String runId = run.runId();

        // ---- admin: the gated read surface returns the run over real HTTP ----
        HttpResponse<String> runs = get("/api/admin/tape/runs?status=COMPLETED");
        assertThat(runs.statusCode())
                .as("recorded-content read returns 200 with the gate opened")
                .isEqualTo(200);
        assertThat(runs.body())
                .as("the completed run is listed by the admin tape endpoint")
                .contains(runId);

        // ---- replay: deterministic reconstruction of input + output ----
        HttpResponse<String> replay = get("/api/admin/tape/runs/" + runId + "/replay");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body())
                .as("replay reconstructs the run, its prompt and its completion")
                .contains("\"present\":true")
                .contains(USER_MESSAGE)
                .contains(COMPLETION);

        // ---- extract: the COMPLETED run folds into one training example ----
        TapeTrainingExtractor.Result result = new TapeTrainingExtractor().extract(store, 0);
        assertThat(result.examples())
                .as("a COMPLETED run with an input step yields exactly one training example")
                .hasSize(1);
        String jsonl = TapeTrainingExtractor.toJsonl(result.examples());
        assertThat(jsonl)
                .as("the extractor emits chat-format JSONL carrying the completion")
                .startsWith("{\"messages\":")
                .contains(COMPLETION);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** The writer persists asynchronously; poll until the run reaches COMPLETED (writer tick is sub-second). */
    private static TapeRun awaitCompletedRun(TapeStore store) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<TapeRun> completed = store.listRuns(TapeQuery.byStatus(TapeStatus.COMPLETED, 10));
            if (!completed.isEmpty()) {
                return completed.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("tape run did not reach COMPLETED within 10s: "
                + store.listRuns(TapeQuery.all(10)));
    }

    /** Minimal terminal-side {@link StreamingSession} the tape wraps; only send/complete matter here. */
    private static final class ProbeSession implements StreamingSession {
        @Override
        public Optional<String> runId() {
            return Optional.empty();
        }

        @Override
        public void emit(AiEvent event) {
        }

        @Override
        public String sessionId() {
            return "tape-e2e-session";
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(String summary) {
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
