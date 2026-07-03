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

import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CompactionConfigTest {

    private AtmosphereConfig config(String strategy, Integer recentWindow) {
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.getInitParameter(CompactionConfig.STRATEGY_KEY)).thenReturn(strategy);
        when(cfg.getInitParameter(CompactionConfig.RECENT_WINDOW_KEY, 0))
                .thenReturn(recentWindow != null ? recentWindow : 0);
        return cfg;
    }

    @Test
    public void nullConfigDefaultsToSlidingWindow() {
        assertInstanceOf(SlidingWindowCompaction.class, CompactionConfig.resolve(null));
    }

    @Test
    public void unsetDefaultsToSlidingWindow() {
        assertInstanceOf(SlidingWindowCompaction.class,
                CompactionConfig.resolve(config(null, null)));
    }

    @Test
    public void explicitSlidingWindow() {
        assertInstanceOf(SlidingWindowCompaction.class,
                CompactionConfig.resolve(config("sliding-window", null)));
    }

    @Test
    public void summarizingSelectsLlmCompaction() {
        assertInstanceOf(LlmSummarizingCompaction.class,
                CompactionConfig.resolve(config("summarizing", null)));
    }

    @Test
    public void summarizingIsCaseInsensitive() {
        assertInstanceOf(LlmSummarizingCompaction.class,
                CompactionConfig.resolve(config("SUMMARIZING", null)));
    }

    @Test
    public void summarizingHonorsRecentWindow() {
        assertInstanceOf(LlmSummarizingCompaction.class,
                CompactionConfig.resolve(config("summarizing", 4)));
    }

    @Test
    public void unknownValueFallsBackToSlidingWindow() {
        assertInstanceOf(SlidingWindowCompaction.class,
                CompactionConfig.resolve(config("token-window", null)));
    }
}
