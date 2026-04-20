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
package org.atmosphere.ai.cost;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.Content;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;

import java.util.Map;

/**
 * {@link StreamingSession} decorator that intercepts
 * {@link StreamingSession#usage(TokenUsage)} events and routes them
 * through a configured {@link CostAccountant} before forwarding to the
 * underlying session. The tenant id comes from the
 * {@code business.tenant.id} SLF4J MDC tag (captured on the dispatch
 * thread — the decorator must be installed while the MDC is still
 * populated, matching {@link org.atmosphere.ai.processor.AiEndpointHandler}'s
 * per-turn publish).
 *
 * <p>Follows the same wrapping pattern as
 * {@link org.atmosphere.ai.MemoryCapturingSession} and
 * {@link org.atmosphere.ai.MetricsCapturingSession} so the session
 * decorator chain in {@code AiStreamingSession.dispatch} stays uniform.</p>
 */
public final class CostAccountingSession implements StreamingSession {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CostAccountingSession.class);

    /** SLF4J MDC key read at usage-time; matches CostCeilingGuardrail. */
    private static final String TENANT_MDC_KEY = "business.tenant.id";

    private final StreamingSession delegate;
    private final CostAccountant accountant;

    public CostAccountingSession(StreamingSession delegate, CostAccountant accountant) {
        this.delegate = delegate;
        this.accountant = accountant;
    }

    @Override
    public Map<Class<?>, Object> injectables() {
        return delegate.injectables();
    }

    @Override public String sessionId() { return delegate.sessionId(); }
    @Override public void send(String text) { delegate.send(text); }
    @Override public void sendContent(Content content) { delegate.sendContent(content); }
    @Override public void sendMetadata(String key, Object value) { delegate.sendMetadata(key, value); }
    @Override public void progress(String message) { delegate.progress(message); }
    @Override public void complete() { delegate.complete(); }
    @Override public void complete(String summary) { delegate.complete(summary); }
    @Override public void error(Throwable t) { delegate.error(t); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public void emit(AiEvent event) { delegate.emit(event); }
    @Override public void stream(String message) { delegate.stream(message); }

    @Override
    public void usage(TokenUsage usage) {
        if (usage != null && usage.hasCounts()) {
            // Pull the tenant tag FIRST so the accountant sees the turn's
            // MDC context rather than whatever thread picks up the delegate
            // call — AiStreamingSession dispatch runs on the servlet thread,
            // which is where AiEndpointHandler publishes business.tenant.id.
            var tenant = org.slf4j.MDC.get(TENANT_MDC_KEY);
            try {
                accountant.record(tenant, usage, usage.model());
            } catch (RuntimeException e) {
                // Never let accounting break the session — the LLM turn
                // should still complete so the user gets a response.
                // Surfaced at WARN so operator wiring errors are visible
                // rather than swallowed.
                logger.warn("CostAccountant.record failed for tenant={} model={}: {}",
                        tenant, usage.model(), e.toString(), e);
            }
        }
        delegate.usage(usage);
    }
}
