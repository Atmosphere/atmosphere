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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.InMemoryCheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.quarkus.runtime.AtmosphereCheckpointProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the checkpoint {@code @BuildStep} (Spring Boot parity for
 * {@code AtmosphereCheckpointAutoConfiguration} + {@code AtmosphereCheckpointEndpoint}).
 * Boots Quarkus with {@code atmosphere-checkpoint} on the classpath and no
 * checkpoint config, then proves the same chain the Spring side ships:
 * {@code AtmosphereCheckpointProducer} produced a {@link CheckpointStore} CDI bean
 * (the bounded in-memory default, since nothing configured SQLite or supplied a
 * user bean); {@code /api/console/info} advertises {@code hasCheckpoints:true} so
 * the console gates its Checkpoints tab on a plane that genuinely exists; and the
 * read endpoint {@code GET /api/admin/checkpoints} serves the exact snapshots the
 * produced store holds, with the opaque {@code state} omitted. The test would FAIL
 * without {@code AtmosphereProcessor.registerCheckpointStore} — no store bean, a
 * {@code hasCheckpoints:false} flag, and a 404 on {@code /api/admin/checkpoints}.
 */
public class CheckpointStoreBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CheckpointStoreBuildStepTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    CheckpointStore store;

    @Inject
    AtmosphereCheckpointProducer producer;

    @TestHTTPResource("/api/console/info")
    URL consoleInfoUrl;

    @TestHTTPResource("/api/admin/checkpoints")
    URL checkpointsUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void defaultStoreProducedAdvertisedAndReadable() throws Exception {
        // 1. The @DefaultBean CheckpointStore is CDI-resolvable and is the bounded
        //    in-memory default (no SQLite config, no user-supplied bean).
        assertNotNull(store, "a CheckpointStore must be CDI-resolvable on Quarkus");
        assertInstanceOf(InMemoryCheckpointStore.class, store,
                "the default store is the bounded in-memory store, got " + store.getClass());
        assertNotNull(producer.resolved(),
                "the producer must have eagerly resolved the store during StartupEvent");

        // 2. hasCheckpoints is advertised true on /api/console/info, so the console
        //    gates its Checkpoints tab on a plane that genuinely exists.
        var info = get(consoleInfoUrl);
        assertEquals(200, info.statusCode(), "GET /api/console/info must succeed");
        assertTrue(info.body().contains("\"hasCheckpoints\":true"),
                "console info must advertise hasCheckpoints=true when a CheckpointStore "
                        + "is wired: " + info.body());

        // 3. The read endpoint serves the exact snapshot the produced store holds
        //    (end-to-end parity with AtmosphereCheckpointEndpoint), and omits the
        //    opaque state field.
        var id = CheckpointId.random();
        store.save(WorkflowSnapshot.<String>builder()
                .id(id)
                .coordinationId("coord-quarkus")
                .agentName("alice")
                .state("secret-state")
                .metadata(Map.of("phase", "review"))
                .createdAt(Instant.now())
                .build());

        var read = get(checkpointsUrl);
        assertEquals(200, read.statusCode(), "GET /api/admin/checkpoints must succeed");
        assertTrue(read.body().contains(id.value()),
                "the read endpoint must list the saved snapshot: " + read.body());
        assertTrue(read.body().contains("coord-quarkus") && read.body().contains("\"alice\""),
                "the wire envelope carries coordinationId + agentName: " + read.body());
        assertTrue(read.body().contains("review"),
                "the wire envelope carries the metadata map: " + read.body());
        assertFalse(read.body().contains("secret-state"),
                "the opaque state field must be omitted from the admin view: " + read.body());
    }

    @Test
    public void malformedTimestampIsRejectedAsBadRequest() throws Exception {
        var url = URI.create(checkpointsUrl.toString() + "?since=not-an-instant");
        var response = http.send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(),
                "a malformed since= instant must be rejected 400, not 500 (Boundary Safety)");
        assertTrue(response.body().contains("Invalid timestamp"),
                "the 400 body explains the rejection: " + response.body());
    }

    private HttpResponse<String> get(URL url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url.toString())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
