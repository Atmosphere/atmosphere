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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.llm.CleartextApiKeyPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule every runtime applies before it sends an API key: plain
 * {@code http} only to a loopback host, read from the URL alone, unless the
 * application opts in. Hosts under {@code .invalid} never resolve, so these
 * tests also show the decision makes no DNS lookup.
 */
class CleartextApiKeyPolicyTest {

    private static final String KEY = "sk-test-not-a-real-key";
    private static final String REMOTE_HTTP = "http://llm.invalid:8080/v1";

    @Test
    void loopbackIsReadFromTheNameAlone() {
        for (var host : List.of("localhost", "LocalHost", "LOCALHOST", "127.0.0.1", "127.255.255.254",
                "127.1.2.3", "[::1]", "::1", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]",
                "::ffff:127.0.0.1", "[::ffff:7f00:1]", "[::ffff:127.9.9.9]")) {
            assertTrue(CleartextApiKeyPolicy.isLoopbackHost(host), host);
        }
        for (var host : Arrays.asList("128.0.0.1", "10.0.0.1", "0.0.0.0", "127.0.0.256", "127.0.0",
                "127.1", "[::2]", "[::]", "[::ffff:10.0.0.1]", "[::ffff:a00:1]", "llm.invalid",
                "localhost.invalid", "127.0.0.1.invalid", "host.docker.internal", "", " ", null)) {
            assertFalse(CleartextApiKeyPolicy.isLoopbackHost(host), String.valueOf(host));
        }
    }

    @Test
    void theVerdictTable() {
        // https: always sent.
        assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict("https://llm.invalid/v1", KEY, false));
        assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict("HTTPS://llm.invalid/v1", KEY, false));
        // http to loopback: sent.
        for (var endpoint : List.of("http://localhost:11434/v1", "http://LOCALHOST:11434/v1",
                "http://127.0.0.1:11434/v1", "http://127.0.0.2/v1", "http://[::1]:11434/v1",
                "http://[::ffff:127.0.0.1]:11434/v1")) {
            assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict(endpoint, KEY, false), endpoint);
        }
        // http to any other host with a key: refused, or sent with the opt-in.
        for (var endpoint : List.of(REMOTE_HTTP, "HTTP://llm.invalid/v1", "http://localhost.invalid/v1",
                "http://127.0.0.1.invalid/v1", "http://10.0.0.5:11434/v1", "http://[::ffff:10.0.0.1]/v1",
                "http://llm invalid/v1")) {
            assertEquals(Verdict.REFUSE, CleartextApiKeyPolicy.verdict(endpoint, KEY, false), endpoint);
            assertEquals(Verdict.SEND_IN_CLEARTEXT, CleartextApiKeyPolicy.verdict(endpoint, KEY, true), endpoint);
        }
        // Keyless: anywhere, no opt-in needed.
        for (var key : Arrays.asList(null, "", "  ", CleartextApiKeyPolicy.NO_API_KEY)) {
            assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict(REMOTE_HTTP, key, false), String.valueOf(key));
        }
        // No endpoint: the runtime's own https default.
        assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict(null, KEY, false));
        assertEquals(Verdict.SEND, CleartextApiKeyPolicy.verdict(" ", KEY, false));
    }

    @Test
    void theRefusalNamesTheEndpointAndTheOptInButNeverTheKey() {
        var logger = LoggerFactory.getLogger(CleartextApiKeyPolicyTest.class);
        var refused = assertThrows(IllegalArgumentException.class,
                () -> CleartextApiKeyPolicy.requireAllowed(logger, "Test", REMOTE_HTTP, KEY, false));
        assertTrue(refused.getMessage().contains(REMOTE_HTTP), refused.getMessage());
        assertTrue(refused.getMessage().contains("cleartext"), refused.getMessage());
        assertTrue(refused.getMessage().contains(CleartextApiKeyPolicy.ALLOW_PROPERTY), refused.getMessage());
        assertTrue(refused.getMessage().contains(CleartextApiKeyPolicy.ALLOW_ENV), refused.getMessage());
        assertFalse(refused.getMessage().contains(KEY), "the refusal never carries the key");

        var notAUri = assertThrows(IllegalArgumentException.class,
                () -> CleartextApiKeyPolicy.requireAllowed(logger, "Test", "http://llm invalid/v1", KEY, false));
        assertTrue(notAUri.getMessage().contains("not a URI"), notAUri.getMessage());

        // The opt-in and the safe cases do not throw.
        CleartextApiKeyPolicy.requireAllowed(logger, "Test", REMOTE_HTTP, KEY, true);
        CleartextApiKeyPolicy.requireAllowed(logger, "Test", "https://llm.invalid/v1", KEY, false);
        CleartextApiKeyPolicy.requireAllowed(logger, "Test", REMOTE_HTTP, null, false);
    }

    @Test
    void onlyTrueOptsIn() {
        assertTrue(CleartextApiKeyPolicy.optedIn("true"));
        assertTrue(CleartextApiKeyPolicy.optedIn(" TRUE "));
        for (var raw : Arrays.asList(null, "", "false", "yes", "1", "on")) {
            assertFalse(CleartextApiKeyPolicy.optedIn(raw), String.valueOf(raw));
        }
    }
}
