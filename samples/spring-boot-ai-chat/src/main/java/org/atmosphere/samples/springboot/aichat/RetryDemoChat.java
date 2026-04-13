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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates the {@code @AiEndpoint(retry = @Retry(...))} annotation with an
 * observable, wire-level retry signal that works in demo mode (no real LLM
 * required).
 *
 * <p>The annotation attribute values on this class are:</p>
 * <ul>
 *   <li>{@code maxRetries = 2}</li>
 *   <li>{@code initialDelayMs = 100}</li>
 *   <li>{@code maxDelayMs = 500}</li>
 *   <li>{@code backoffMultiplier = 2.0}</li>
 * </ul>
 *
 * <p>When {@code session.stream(message)} is invoked against a real
 * {@code AgentRuntime}, the Atmosphere AI endpoint processor converts these
 * attributes into an {@link org.atmosphere.ai.RetryPolicy} and threads it
 * onto every {@link org.atmosphere.ai.AgentExecutionContext} via
 * {@link org.atmosphere.ai.AgentExecutionContext#withRetryPolicy}. Runtimes
 * honor this policy when the underlying provider call throws a retryable
 * error.</p>
 *
 * <p>This demo handler mirrors the wire-level echo pattern used by the
 * {@code /ai/retry-policy} integration-test handler: it reflects the
 * annotation values back as structured metadata so tests can confirm the
 * attribute is wired end-to-end, without needing a real LLM. The handler
 * also exposes a deterministic fault-injection path keyed on the prompt
 * {@code "fail-once:<id>"}: the first request for a given id emits
 * {@code retry.attempt=1} followed by {@code session.error(...)} (a simulated
 * transient failure); a second request with the same id emits
 * {@code retry.attempt=2} and completes successfully, proving the sample
 * wire exposes an observable attempt counter the way a retried LLM call
 * would.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat-with-retry",
        retry = @AiEndpoint.Retry(
                maxRetries = 2,
                initialDelayMs = 100,
                maxDelayMs = 500,
                backoffMultiplier = 2.0))
public class RetryDemoChat {

    private static final Logger logger = LoggerFactory.getLogger(RetryDemoChat.class);

    private static final String FAULT_PREFIX = "fail-once:";

    /** Per-id attempt counter for the deterministic fault-injection prompt. */
    private final ConcurrentHashMap<String, AtomicInteger> faultAttempts = new ConcurrentHashMap<>();

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        var retry = getClass().getAnnotation(AiEndpoint.class).retry();
        session.sendMetadata("retry.maxRetries", retry.maxRetries());
        session.sendMetadata("retry.initialDelayMs", retry.initialDelayMs());
        session.sendMetadata("retry.maxDelayMs", retry.maxDelayMs());
        session.sendMetadata("retry.backoffMultiplier", retry.backoffMultiplier());

        if (message.startsWith(FAULT_PREFIX)) {
            var id = message.substring(FAULT_PREFIX.length());
            var counter = faultAttempts.computeIfAbsent(id, k -> new AtomicInteger());
            var attempt = counter.incrementAndGet();
            session.sendMetadata("retry.attempt", attempt);
            if (attempt == 1) {
                logger.info("Simulated transient failure for id={} attempt={}", id, attempt);
                session.error(new RuntimeException("simulated transient failure"));
                return;
            }
            logger.info("Retry succeeded for id={} attempt={}", id, attempt);
            session.send("Recovered on attempt " + attempt);
            session.complete();
            return;
        }

        session.send("Retry policy acknowledged: maxRetries=" + retry.maxRetries());
        session.complete();
    }
}
