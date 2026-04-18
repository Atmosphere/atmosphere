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
package org.atmosphere.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link StreamingSession} wrapper that runs {@link AiGuardrail#inspectResponse}
 * on the accumulated response text. Checks are performed every {@code checkInterval}
 * characters and on {@code complete()} to catch policy violations during streaming.
 *
 * <p>If a guardrail blocks the response, the stream is terminated with an error.</p>
 */
class GuardrailCapturingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(GuardrailCapturingSession.class);

    private static final int DEFAULT_CHECK_INTERVAL = 200;

    private final StreamingSession delegate;
    private final List<AiGuardrail> guardrails;
    private final int checkInterval;
    private final ReentrantLock lock = new ReentrantLock();
    private final StringBuilder accumulated = new StringBuilder();
    private int lastCheckedLength;
    private volatile boolean blocked;

    GuardrailCapturingSession(StreamingSession delegate, List<AiGuardrail> guardrails) {
        this(delegate, guardrails, DEFAULT_CHECK_INTERVAL);
    }

    GuardrailCapturingSession(StreamingSession delegate, List<AiGuardrail> guardrails,
                              int checkInterval) {
        this.delegate = delegate;
        this.guardrails = guardrails;
        this.checkInterval = checkInterval;
    }

    @Override
    public java.util.Map<Class<?>, Object> injectables() {
        return delegate.injectables();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        lock.lock();
        try {
            if (blocked) {
                return;
            }
            accumulated.append(text);
            if (accumulated.length() - lastCheckedLength >= checkInterval) {
                lastCheckedLength = accumulated.length();
                if (checkGuardrails()) {
                    return;
                }
            }
            delegate.send(text);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void sendContent(Content content) {
        // Text variants flow through the guardrail scanner so a Content.Text
        // chunk is inspected the same way a send() chunk is. Binary variants
        // have no text projection the text-centric guardrail API can check —
        // forward them to the delegate when we are not already blocked so
        // the binary frame reaches the leaf writer unchanged.
        if (content instanceof Content.Text text) {
            send(text.text());
            return;
        }
        lock.lock();
        try {
            if (blocked) {
                return;
            }
        } finally {
            lock.unlock();
        }
        delegate.sendContent(content);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        lock.lock();
        try {
            if (!blocked) {
                checkGuardrails(); // Final check on complete response
            }
        } finally {
            lock.unlock();
        }
        if (!blocked) {
            delegate.complete();
        }
    }

    @Override
    public void complete(String summary) {
        lock.lock();
        try {
            if (!blocked) {
                // Check the summary (which is the full response)
                accumulated.setLength(0);
                accumulated.append(summary);
                checkGuardrails();
            }
        } finally {
            lock.unlock();
        }
        if (!blocked) {
            delegate.complete(summary);
        }
    }

    @Override
    public void error(Throwable t) {
        delegate.error(t);
    }

    @Override
    public void emit(AiEvent event) {
        lock.lock();
        try {
            if (blocked) {
                return;
            }
            if (event instanceof AiEvent.TextDelta delta) {
                accumulated.append(delta.text());
                if (accumulated.length() - lastCheckedLength >= checkInterval) {
                    lastCheckedLength = accumulated.length();
                    if (checkGuardrails()) {
                        return;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        delegate.emit(event);
    }

    @Override
    public boolean isClosed() {
        return blocked || delegate.isClosed();
    }

    /**
     * @return {@code true} if a guardrail blocked the response
     */
    private boolean checkGuardrails() {
        var response = accumulated.toString();
        for (var guardrail : guardrails) {
            try {
                var result = guardrail.inspectResponse(response);
                if (result instanceof AiGuardrail.GuardrailResult.Block block) {
                    logger.warn("Response blocked by guardrail {}: {}",
                            guardrail.getClass().getSimpleName(), block.reason());
                    blocked = true; // NOPMD — field read by isClosed() and other session methods
                    delegate.error(new SecurityException("Response blocked: " + block.reason()));
                    return true;
                }
            } catch (Exception e) {
                logger.error("AiGuardrail.inspectResponse failed: {}",
                        guardrail.getClass().getName(), e);
            }
        }
        return false;
    }
}
