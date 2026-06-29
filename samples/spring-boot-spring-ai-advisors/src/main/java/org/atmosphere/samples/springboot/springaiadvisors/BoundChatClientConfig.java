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

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.spring.SpringAiAgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The heart of the sample: build your OWN, fully configured Spring AI
 * {@link ChatClient} — carrying a {@code defaultAdvisors(...)} chain — and bind
 * it to Atmosphere with {@link SpringAiAgentRuntime#setChatClient(ChatClient)}.
 *
 * <p>This proves the three claims of the Atmosphere 4 blog §3:</p>
 * <ol>
 *   <li><b>Bind your own client</b> — {@link #bindBoundChatClient} hands a
 *       caller-built {@code ChatClient} to {@code setChatClient(...)}; from
 *       then on every Atmosphere AI request flows through it.</li>
 *   <li><b>Atmosphere keeps your {@code defaultAdvisors(...)}</b> — the runtime
 *       dispatches via {@code client.prompt()}, so the default
 *       {@link AuditingAdvisor} installed here fires on every request without
 *       Atmosphere doing anything special.</li>
 *   <li><b>Attach more advisors per request</b> — see
 *       {@link PerRequestAuditInterceptor}, which stamps a second advisor onto
 *       a single request via {@code SpringAiAdvisors}.</li>
 * </ol>
 *
 * <p>The terminal model is {@link LocalEchoChatModel} so the whole thing runs
 * offline. Replace it with a real {@code ChatModel} (e.g. {@code OpenAiChatModel})
 * and the advisor wiring is unchanged.</p>
 */
@Configuration
public class BoundChatClientConfig {

    /** Name + audit key of the advisor installed as a {@code ChatClient} default. */
    public static final String DEFAULT_ADVISOR_NAME = "default-advisor";

    /** Name + audit key of the advisor attached per request by the interceptor. */
    public static final String PER_REQUEST_ADVISOR_NAME = "per-request-advisor";

    private static final Logger logger = LoggerFactory.getLogger(BoundChatClientConfig.class);

    /**
     * Expose the shared audit log as a Spring bean so the REST controller can
     * inject it. It is the same instance the framework-instantiated interceptor
     * and the advisors write to (see {@link AdvisorAuditLog#shared()}).
     */
    @Bean
    AdvisorAuditLog advisorAuditLog() {
        return AdvisorAuditLog.shared();
    }

    /** Bind the bound ChatClient at startup, before the first AI request. */
    @PostConstruct
    void bindAtStartup() {
        bindBoundChatClient(AdvisorAuditLog.shared());
        logger.info("Bound a custom Spring AI ChatClient (with a default '{}') "
                + "to SpringAiAgentRuntime via setChatClient(...)", DEFAULT_ADVISOR_NAME);
    }

    /**
     * Build a fully configured {@link ChatClient} that carries a default
     * {@link AuditingAdvisor}, and bind it to Atmosphere via
     * {@link SpringAiAgentRuntime#setChatClient(ChatClient)}. Factored out as a
     * static method so the delivery test exercises the exact same wiring the
     * running application uses (no drift between demo and proof).
     *
     * @param auditLog the shared log the default advisor records into
     * @return the bound client (also installed as the runtime's static client)
     */
    public static ChatClient bindBoundChatClient(AdvisorAuditLog auditLog) {
        var defaultAdvisor = new AuditingAdvisor(DEFAULT_ADVISOR_NAME, 100, auditLog);
        var client = ChatClient.builder(new LocalEchoChatModel())
                .defaultAdvisors(defaultAdvisor)
                .build();
        SpringAiAgentRuntime.setChatClient(client);
        return client;
    }

    /**
     * Build the per-request advisor the interceptor attaches to a single
     * request. Same {@link AuditingAdvisor} type, different name + a later
     * chain order so it runs after the default one.
     *
     * @param auditLog the shared log this advisor records into
     * @return a fresh per-request advisor instance
     */
    public static Advisor newPerRequestAdvisor(AdvisorAuditLog auditLog) {
        return new AuditingAdvisor(PER_REQUEST_ADVISOR_NAME, 200, auditLog);
    }
}
