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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.spring.SpringAiAdvisors;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates blog claim #3 in the live {@code @AiEndpoint} path: a second
 * advisor attached <em>per request</em>, on top of the {@code defaultAdvisors}
 * baked into the bound {@link org.springframework.ai.chat.client.ChatClient}.
 *
 * <p>When a request's message contains the trigger word {@value #TRIGGER}, this
 * interceptor stamps a fresh {@link AuditingAdvisor} into the request metadata
 * under {@link SpringAiAdvisors#METADATA_KEY}. Atmosphere copies that metadata
 * onto the {@code AgentExecutionContext}, and {@code SpringAiAgentRuntime} reads
 * it back via {@code SpringAiAdvisors.from(context)} and appends it to the
 * builder defaults through {@code promptSpec.advisors(...)} — so that single
 * request runs BOTH the default advisor and this per-request one. Every other
 * request runs only the default.</p>
 *
 * <p>Instantiated by the framework's object factory with a no-arg constructor
 * (declared on {@code @AiEndpoint(interceptors = ...)}), so it pulls the shared
 * audit log from {@link AdvisorAuditLog#shared()} rather than by injection.</p>
 */
public class PerRequestAuditInterceptor implements AiInterceptor {

    /** Messages containing this word (case-insensitive) get the per-request advisor. */
    public static final String TRIGGER = "audit";

    private static final Logger logger = LoggerFactory.getLogger(PerRequestAuditInterceptor.class);

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var message = request.message();
        if (message == null || !message.toLowerCase().contains(TRIGGER)) {
            return request;
        }
        var advisor = BoundChatClientConfig.newPerRequestAdvisor(AdvisorAuditLog.shared());
        logger.info("Attaching per-request advisor '{}' to this request",
                BoundChatClientConfig.PER_REQUEST_ADVISOR_NAME);
        return request.withMetadata(
                Map.of(SpringAiAdvisors.METADATA_KEY, List.of(advisor)));
    }
}
