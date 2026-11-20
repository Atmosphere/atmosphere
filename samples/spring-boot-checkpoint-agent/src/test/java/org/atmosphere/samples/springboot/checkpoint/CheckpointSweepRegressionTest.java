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
package org.atmosphere.samples.springboot.checkpoint;

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the pre-4.0.78 sample sweep of this sample.
 *
 * <ol>
 *   <li>The checkpoint mutators accepted anonymous calls and recorded whatever
 *       {@code ?by=} the caller asserted as the approver (Correctness
 *       Invariant #6). They now need a reviewer token, and the approval
 *       records the authenticated principal.</li>
 *   <li>The chat reply carried no checkpoint reference: the coordinator sent
 *       its pointer through {@code session.stream(...)}, i.e. to the model as a
 *       prompt. The reply now carries the checkpoint id verbatim.</li>
 *   <li>The boot log promised a signed {@code CommitmentRecord} per dispatch
 *       but the coordinator's single-agent {@code fleet.agent(..).call(..)}
 *       journaled none (Correctness Invariant #7).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.admin.enabled=false",
                "atmosphere.checkpoint.store=in-memory"
        })
class CheckpointSweepRegressionTest {

    private static final Pattern CHECKPOINT_ID =
            Pattern.compile("Checkpoint: ([0-9a-fA-F-]{36})");

    @LocalServerPort
    int port;

    @Autowired
    CheckpointStore store;

    @Autowired
    CoordinationJournal journal;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anonymousMutatorsAreRefusedAndLeaveTheStoreUnchanged() throws Exception {
        var id = seed().id().value();

        assertEquals(401, send("POST", "/api/checkpoints/" + id + "/approve?by=alice", null).statusCode(),
                "an anonymous approve must be refused");
        assertEquals(401, send("POST", "/api/checkpoints/" + id + "/fork?state=x", null).statusCode(),
                "an anonymous fork must be refused");
        assertEquals(401, send("DELETE", "/api/checkpoints/" + id, null).statusCode(),
                "an anonymous delete must be refused");
        assertEquals(401, send("DELETE", "/api/checkpoints/" + id, "not-a-token").statusCode(),
                "an unknown token must be refused");

        assertTrue(store.load(CheckpointId.of(id)).isPresent(), "the refused delete must not delete");
        assertTrue(store.list(org.atmosphere.checkpoint.CheckpointQuery.forCoordination("dispatch"))
                        .stream().noneMatch(s -> s.parent().map(p -> p.value().equals(id)).orElse(false)),
                "the refused approve/fork must not chain a child snapshot");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theApprovalRecordsTheAuthenticatedReviewerNotACallerAssertedOne() throws Exception {
        var id = seed().id().value();

        var impersonation = send("POST", "/api/checkpoints/" + id + "/approve?by=mallory",
                CheckpointOperatorAuth.DEMO_TOKEN);
        assertEquals(403, impersonation.statusCode(),
                "?by= naming someone other than the authenticated reviewer must be refused: "
                        + impersonation.body());

        var approve = send("POST", "/api/checkpoints/" + id + "/approve",
                CheckpointOperatorAuth.DEMO_TOKEN);
        assertEquals(200, approve.statusCode(), approve.body());
        assertTrue(approve.body().contains("\"approvedBy\":\"alice\""),
                "approvedBy is the token's principal: " + approve.body());

        var bearer = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base() + "/api/checkpoints/" + id + "/fork?state=branch"))
                        .header("Authorization", "Bearer " + CheckpointOperatorAuth.DEMO_TOKEN)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bearer.statusCode(), "a Bearer token works too: " + bearer.body());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void theChatReplyCarriesTheCheckpointIdAndTheDispatchIsSigned() throws Exception {
        var frames = new StringBuilder();
        var found = new CompletableFuture<String>();
        var socket = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/atmosphere/agent/dispatch"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                synchronized (frames) {
                                    frames.append(data);
                                    var m = CHECKPOINT_ID.matcher(frames);
                                    if (m.find()) {
                                        found.complete(m.group(1));
                                    }
                                }
                                ws.request(1);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);
        String checkpointId;
        try {
            socket.sendText("please refund order 4321", true).get(5, TimeUnit.SECONDS);
            try {
                checkpointId = found.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                synchronized (frames) {
                    throw new AssertionError("the reply must carry 'Checkpoint: <id>'; frames: " + frames, e);
                }
            }
        } finally {
            socket.abort();
        }

        var snapshot = store.load(CheckpointId.of(checkpointId)).orElseThrow(
                () -> new AssertionError("the id in the reply must name a stored checkpoint"));
        assertEquals("analyzer", snapshot.agentName());
        assertTrue(String.valueOf(snapshot.state()).contains("please refund order 4321"),
                "the referenced checkpoint is this request's analysis: " + snapshot.state());

        var commitments = journal.query(CoordinationQuery.all()).stream()
                .filter(CoordinationEvent.CommitmentRecorded.class::isInstance)
                .map(CoordinationEvent.CommitmentRecorded.class::cast)
                .filter(c -> "analyzer".equals(c.record().subject()))
                .toList();
        assertFalse(commitments.isEmpty(),
                "the analyzer dispatch must journal a signed CommitmentRecord");
        assertTrue(commitments.getLast().record().isSigned());
    }

    private WorkflowSnapshot<Object> seed() {
        var analyzerJson = "{\"request\":\"refund order 55\",\"risk\":\"HIGH\"}";
        return store.save(WorkflowSnapshot.root("dispatch", new CoordinationEvent.AgentCompleted(
                "dispatch", "analyzer", "analyze", analyzerJson, Duration.ofMillis(1), Instant.now())));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create(base() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header(CheckpointOperatorAuth.TOKEN_HEADER, token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
