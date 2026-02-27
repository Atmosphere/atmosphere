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
package org.atmosphere.ai.fanout;

/**
 * Result from one model in a fan-out streaming session.
 * Available programmatically after the fan-out completes.
 *
 * @param modelId           the model endpoint ID
 * @param fullResponse      the complete aggregated response text
 * @param timeToFirstTokenMs milliseconds from request start to first token received
 * @param totalTimeMs       total milliseconds for the complete response
 * @param tokenCount        number of streaming token chunks received
 */
public record FanOutResult(
        String modelId,
        String fullResponse,
        long timeToFirstTokenMs,
        long totalTimeMs,
        int tokenCount
) {
}
