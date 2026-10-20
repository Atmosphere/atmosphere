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
package org.atmosphere.samples.springboot.a2astartup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The README's operational controls are admin writes. The Sessions-tab cancel
 * ({@code POST /api/admin/sessions/{uuid}/cancel-inflight}) must pass the write
 * guard for the demo operator token and refuse an anonymous caller: before the
 * sample defined a token validator, it answered 401 even with a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorAdminWriteTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> cancel(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/admin/sessions/" + UUID.randomUUID() + "/cancel-inflight"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("X-Atmosphere-Auth", token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theDemoOperatorPassesTheAdminWriteGuard() throws Exception {
        // 404: past the guard, with no in-flight session for this uuid to cancel.
        var response = cancel("demo-operator");
        assertEquals(404, response.statusCode(), () -> "operator cancel: " + response.body());
    }

    @Test
    void anAnonymousAdminWriteIsRefused() throws Exception {
        var response = cancel(null);
        assertEquals(401, response.statusCode(), () -> "anonymous cancel: " + response.body());
    }

    @Test
    void anUnknownTokenIsRefused() throws Exception {
        var response = cancel("admin");
        assertEquals(401, response.statusCode(), () -> "unknown-token cancel: " + response.body());
    }
}
