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
package org.atmosphere.verifier;

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.verifier.annotation.Sink;
import org.atmosphere.verifier.annotation.SinkScanner;
import org.atmosphere.verifier.policy.TaintRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinkScannerTest {

    /**
     * Realistic example: an EmailTools class that declares its own
     * dataflow constraint via {@link Sink} on the body parameter.
     */
    static class EmailTools {

        @AiTool(name = "fetch_emails", description = "Fetch emails")
        public String fetchEmails(@Param("folder") String folder) {
            return "stub";
        }

        @AiTool(name = "send_email", description = "Send an email")
        public String sendEmail(
                @Param("to") String to,
                @Param("body") @Sink(forbidden = {"fetch_emails"}) String body) {
            return "stub";
        }
    }

    /** Multiple forbidden sources on a single parameter. */
    static class MultiSourceSink {
        @AiTool(name = "post_webhook", description = "POST to webhook")
        public String postWebhook(
                @Param("payload")
                @Sink(forbidden = {"fetch_emails", "read_secrets"}) String payload) {
            return "stub";
        }
    }

    /** No sinks at all — scanner should produce empty list. */
    static class CleanTools {
        @AiTool(name = "echo", description = "Echo")
        public String echo(@Param("msg") String msg) {
            return msg;
        }
    }

    /** Custom rule name override. */
    static class NamedSink {
        @AiTool(name = "exec", description = "Execute shell")
        public String exec(
                @Param("cmd")
                @Sink(forbidden = {"fetch_url"}, name = "no-rce-from-url") String cmd) {
            return "stub";
        }
    }

    @Test
    void scanDerivesRulesFromSinkAnnotations() {
        List<TaintRule> rules = SinkScanner.scan(EmailTools.class);
        assertEquals(1, rules.size());
        TaintRule rule = rules.get(0);
        assertEquals("fetch_emails", rule.sourceTool());
        assertEquals("send_email", rule.sinkTool());
        assertEquals("body", rule.sinkParam());
        // Default name embeds source / sink / param so two leaks on
        // different params produce distinguishable violations.
        assertNotNull(rule.name());
        assertTrue(rule.name().contains("fetch_emails"));
        assertTrue(rule.name().contains("send_email"));
        assertTrue(rule.name().contains("body"));
    }

    @Test
    void scanExpandsMultipleForbiddenSources() {
        List<TaintRule> rules = SinkScanner.scan(MultiSourceSink.class);
        assertEquals(2, rules.size());
        Set<String> sources = rules.stream()
                .map(TaintRule::sourceTool)
                .collect(Collectors.toUnmodifiableSet());
        assertTrue(sources.contains("fetch_emails"));
        assertTrue(sources.contains("read_secrets"));
        // Both rules target the same sink + param
        for (TaintRule r : rules) {
            assertEquals("post_webhook", r.sinkTool());
            assertEquals("payload", r.sinkParam());
        }
    }

    @Test
    void scanReturnsEmptyForToolsWithoutSinks() {
        assertTrue(SinkScanner.scan(CleanTools.class).isEmpty());
    }

    @Test
    void customNameOverridesDefault() {
        List<TaintRule> rules = SinkScanner.scan(NamedSink.class);
        assertEquals(1, rules.size());
        assertEquals("no-rce-from-url", rules.get(0).name());
    }

    @Test
    void scanMultipleClassesConcatenates() {
        List<TaintRule> rules = SinkScanner.scan(EmailTools.class, MultiSourceSink.class);
        assertEquals(3, rules.size());
    }

    @Test
    void scanNullClassReturnsEmpty() {
        assertTrue(SinkScanner.scan((Class<?>) null).isEmpty());
    }
}
