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
package org.atmosphere.ai.jfr;

import org.atmosphere.ai.AiMetrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fans out every {@link AiMetrics} callback to a list of delegates. Exceptions
 * thrown by a delegate are caught and skipped so a misbehaving observer cannot
 * derail the others.
 *
 * <p>{@link #withJfr} is the canonical factory the pipeline uses to compose a
 * user-supplied {@link AiMetrics} with the always-on
 * {@link JfrAiMetrics}.</p>
 */
public final class CompositeAiMetrics implements AiMetrics {

    private final List<AiMetrics> delegates;

    public CompositeAiMetrics(AiMetrics... delegates) {
        this(Arrays.asList(delegates));
    }

    public CompositeAiMetrics(List<AiMetrics> delegates) {
        var copy = new ArrayList<AiMetrics>(delegates.size());
        for (var delegate : delegates) {
            if (delegate != null && delegate != AiMetrics.NOOP) {
                copy.add(delegate);
            }
        }
        this.delegates = List.copyOf(copy);
    }

    /**
     * Compose the supplied {@link AiMetrics} with a {@link JfrAiMetrics}.
     * Returns the input unchanged when it is already a {@code CompositeAiMetrics}
     * that contains a {@code JfrAiMetrics}, so wrapping is idempotent.
     *
     * @param userMetrics caller-supplied metrics (may be {@link AiMetrics#NOOP})
     * @return composite that fans out to {@code userMetrics} and a {@link JfrAiMetrics}
     */
    public static AiMetrics withJfr(AiMetrics userMetrics) {
        if (userMetrics instanceof CompositeAiMetrics composite && composite.containsJfr()) {
            return composite;
        }
        if (userMetrics == null || userMetrics == AiMetrics.NOOP) {
            return new CompositeAiMetrics(new JfrAiMetrics());
        }
        return new CompositeAiMetrics(userMetrics, new JfrAiMetrics());
    }

    /** Visible for testing. */
    public List<AiMetrics> delegates() {
        return delegates;
    }

    private boolean containsJfr() {
        for (var delegate : delegates) {
            if (delegate instanceof JfrAiMetrics) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
        for (var delegate : delegates) {
            try {
                delegate.recordStreamingTextUsage(model, promptStreamingTexts, completionStreamingTexts);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordStreamingTextUsage", e);
            }
        }
    }

    @Override
    public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
        for (var delegate : delegates) {
            try {
                delegate.recordLatency(model, timeToFirstStreamingText, totalDuration);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordLatency", e);
            }
        }
    }

    @Override
    public void recordTokenUsage(String model, long inputTokens, long outputTokens, long totalTokens) {
        for (var delegate : delegates) {
            try {
                delegate.recordTokenUsage(model, inputTokens, outputTokens, totalTokens);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordTokenUsage", e);
            }
        }
    }

    @Override
    public void recordCost(String model, BigDecimal cost) {
        for (var delegate : delegates) {
            try {
                delegate.recordCost(model, cost);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordCost", e);
            }
        }
    }

    @Override
    public void recordToolCall(String model, String toolName, Duration duration, boolean success) {
        for (var delegate : delegates) {
            try {
                delegate.recordToolCall(model, toolName, duration, success);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordToolCall", e);
            }
        }
    }

    @Override
    public void recordError(String model, String errorType) {
        for (var delegate : delegates) {
            try {
                delegate.recordError(model, errorType);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordError", e);
            }
        }
    }

    @Override
    public void sessionStarted(String model) {
        for (var delegate : delegates) {
            try {
                delegate.sessionStarted(model);
            } catch (RuntimeException e) {
                logFailure(delegate, "sessionStarted", e);
            }
        }
    }

    @Override
    public void sessionEnded(String model) {
        for (var delegate : delegates) {
            try {
                delegate.sessionEnded(model);
            } catch (RuntimeException e) {
                logFailure(delegate, "sessionEnded", e);
            }
        }
    }

    @Override
    public void recordInputAssembly(String model, String stage, int approximateTokens, int approximateChars) {
        for (var delegate : delegates) {
            try {
                delegate.recordInputAssembly(model, stage, approximateTokens, approximateChars);
            } catch (RuntimeException e) {
                logFailure(delegate, "recordInputAssembly", e);
            }
        }
    }

    private static void logFailure(AiMetrics delegate, String callback, RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(CompositeAiMetrics.class)
                .trace("AiMetrics delegate {} threw during {}: {}",
                        delegate.getClass().getName(), callback, e.toString(), e);
    }
}
