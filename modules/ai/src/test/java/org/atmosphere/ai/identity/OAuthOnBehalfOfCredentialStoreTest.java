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
package org.atmosphere.ai.identity;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthOnBehalfOfCredentialStoreTest {

    private HttpServer server;
    private URI tokenEndpoint;
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            try (InputStream in = exchange.getRequestBody()) {
                lastForm.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = status / 100 == 2
                    ? "{\"access_token\":\"AT-123\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                            .getBytes(StandardCharsets.UTF_8)
                    : "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        tokenEndpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OAuthOnBehalfOfCredentialStore store(CredentialStore backing) {
        var config = OAuthOboConfig.builder(tokenEndpoint, "agent-client", "secret")
                .defaultScope("https://api.example.com/.default")
                .build();
        return new OAuthOnBehalfOfCredentialStore(backing, config);
    }

    @Test
    void exchangesSubjectTokenForAccessToken() {
        var backing = new InMemoryCredentialStore();
        var obo = store(backing);
        obo.put("alice", "oauth.subject_token", "USER-SUBJECT-TOKEN");

        var token = obo.get("alice", "scope-a").orElseThrow();

        assertEquals("AT-123", token);
        var form = lastForm.get();
        assertTrue(form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"),
                "must use the RFC 8693 token-exchange grant: " + form);
        assertTrue(form.contains("subject_token=USER-SUBJECT-TOKEN"), form);
        assertTrue(form.contains("scope=scope-a"), form);
    }

    @Test
    void cachesExchangedTokenUntilExpiry() {
        var obo = store(new InMemoryCredentialStore());
        obo.put("bob", "oauth.subject_token", "SUBJ");

        var first = obo.get("bob", "s").orElseThrow();
        var second = obo.get("bob", "s").orElseThrow();

        assertEquals(first, second);
        assertEquals(1, calls.get(), "the second lookup must be served from cache, not re-exchanged");
    }

    @Test
    void noSubjectTokenFailsClosed() {
        var obo = store(new InMemoryCredentialStore());
        assertTrue(obo.get("nobody", "s").isEmpty(),
                "a user with no delegated subject token must yield no credential");
        assertEquals(0, calls.get(), "no exchange should be attempted without a subject token");
    }

    @Test
    void exchangeErrorFailsClosed() {
        status = 400;
        var obo = store(new InMemoryCredentialStore());
        obo.put("carol", "oauth.subject_token", "SUBJ");
        assertTrue(obo.get("carol", "s").isEmpty(),
                "a token-exchange error must fail closed (no fallback credential)");
    }

    @Test
    void rePuttingSubjectTokenInvalidatesCache() {
        var obo = store(new InMemoryCredentialStore());
        obo.put("dave", "oauth.subject_token", "SUBJ-1");
        obo.get("dave", "s");
        obo.put("dave", "oauth.subject_token", "SUBJ-2"); // rotation invalidates cache

        obo.get("dave", "s");
        assertEquals(2, calls.get(), "rotating the subject token must force a fresh exchange");
        assertTrue(lastForm.get().contains("subject_token=SUBJ-2"));
    }

    @Test
    void identifierDoesNotTriggerExchange() {
        var backing = new InMemoryCredentialStore();
        var obo = store(backing);
        obo.put("erin", "oauth.subject_token", "SUBJ");

        var id = obo.identifier("erin", "s");
        assertTrue(id.isPresent() && id.get().startsWith("cred-"));
        assertEquals(0, calls.get(), "identifier must not perform a token exchange");
        assertEquals("oauth-obo", obo.name());
    }

    @Test
    void deletingSubjectTokenRemovesAccess() {
        var obo = store(new InMemoryCredentialStore());
        obo.put("frank", "oauth.subject_token", "SUBJ");
        assertFalse(obo.get("frank", "s").isEmpty());
        obo.delete("frank", "oauth.subject_token");
        assertTrue(obo.get("frank", "s").isEmpty(), "deleting the subject token revokes access");
    }
}
