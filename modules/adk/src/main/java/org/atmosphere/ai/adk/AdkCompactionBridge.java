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
package org.atmosphere.ai.adk;

import com.google.adk.summarizer.EventsCompactionConfig;
import org.atmosphere.ai.AiCompactionStrategy;
import org.atmosphere.ai.SlidingWindowCompaction;
import org.atmosphere.ai.SummarizingCompaction;

/**
 * Maps Atmosphere {@link AiCompactionStrategy} configuration to ADK's
 * {@link EventsCompactionConfig} for native compaction support.
 *
 * <p>When configuring an ADK {@code LlmAgent}, use this bridge to translate
 * the Atmosphere compaction settings into ADK-native parameters.</p>
 */
public final class AdkCompactionBridge {

    private AdkCompactionBridge() {
    }

    /**
     * Create an ADK {@link EventsCompactionConfig} from an Atmosphere
     * {@link AiCompactionStrategy} and max message count.
     *
     * @param strategy    the Atmosphere compaction strategy
     * @param maxMessages the maximum number of messages to retain
     * @return ADK-native compaction config, or {@code null} if the strategy
     *         does not map to ADK compaction
     */
    public static EventsCompactionConfig toAdkConfig(AiCompactionStrategy strategy,
                                                      int maxMessages) {
        if (strategy instanceof SlidingWindowCompaction) {
            return EventsCompactionConfig.builder()
                    .eventRetentionSize(maxMessages)
                    .build();
        }
        if (strategy instanceof SummarizingCompaction) {
            // Map to ADK's compaction with summarization interval
            return EventsCompactionConfig.builder()
                    .compactionInterval(maxMessages)
                    .overlapSize(Math.max(2, maxMessages / 3))
                    .build();
        }
        // Unknown strategy — let ADK use its defaults
        return null;
    }
}
