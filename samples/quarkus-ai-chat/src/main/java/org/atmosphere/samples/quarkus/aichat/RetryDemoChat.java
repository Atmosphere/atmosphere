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
package org.atmosphere.samples.quarkus.aichat;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quarkus port of {@code spring-boot-ai-chat#RetryDemoChat}. Demonstrates the
 * {@code @AiEndpoint(retry = @Retry(...))} annotation: the Atmosphere endpoint
 * processor converts the attribute values into a
 * {@link org.atmosphere.ai.RetryPolicy} and threads it onto every
 * {@link org.atmosphere.ai.AgentExecutionContext} via
 * {@link org.atmosphere.ai.AgentExecutionContext#withRetryPolicy}.
 *
 * <p>Wire-level demo: every prompt reflects the four annotation values back
 * as {@code retry.maxRetries / initialDelayMs / maxDelayMs / backoffMultiplier}
 * metadata frames so tests can confirm the attribute reached the runtime
 * without needing a real LLM. Prompts of the form {@code "fail-once:<id>"}
 * exercise a deterministic fault-injection path: the first request for a
 * given id emits {@code retry.attempt=1} + {@code session.error(...)} (a
 * simulated transient failure); a second request with the same id emits
 * {@code retry.attempt=2} and completes successfully.</p>
 */
@AiEndpoint(path = "/atmosphere/ai-chat-with-retry",
        retry = @AiEndpoint.Retry(
                maxRetries = 2,
                initialDelayMs = 100,
                maxDelayMs = 500,
                backoffMultiplier = 2.0))
@AgentScope(unrestricted = true,
        justification = "Retry-policy demo — intentionally accepts arbitrary prompts to exercise RetryPolicy behaviour. Production deployments should replace with a scoped @AgentScope.")
public class RetryDemoChat {

    private static final Logger logger = LoggerFactory.getLogger(RetryDemoChat.class);

    private static final String FAULT_PREFIX = "fail-once:";

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
