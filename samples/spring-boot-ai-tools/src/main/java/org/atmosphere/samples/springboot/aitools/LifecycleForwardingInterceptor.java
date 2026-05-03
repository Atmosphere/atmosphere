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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiEventForwardingListener;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.Map;

/**
 * Opts the request into model-lifecycle wire forwarding by stamping
 * {@link AiEventForwardingListener#METADATA_KEY} on the request metadata.
 *
 * <p>{@code AiPipeline} reads the flag after the interceptor chain runs and
 * attaches a fresh {@link AiEventForwardingListener} bound to the live
 * streaming session. The browser then receives one
 * {@code progress} frame per model dispatch with payloads like
 * {@code "model:start (gpt-4o, msgs=3, tools=2)"} and
 * {@code "model:end (gpt-4o, in=120, out=85, ms=842)"} — useful for tool-loop
 * visualization, latency overlays, and token-usage HUDs without each
 * {@code @AiEndpoint} author touching {@code AgentExecutionContext} or the
 * listener API directly.</p>
 */
public class LifecycleForwardingInterceptor implements AiInterceptor {

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request.withMetadata(Map.of(AiEventForwardingListener.METADATA_KEY, Boolean.TRUE));
    }
}
