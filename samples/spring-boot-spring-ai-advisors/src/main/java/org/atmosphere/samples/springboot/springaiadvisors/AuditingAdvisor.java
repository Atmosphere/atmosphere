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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * A minimal, real Spring AI {@link org.springframework.ai.chat.client.advisor.api.Advisor}
 * whose only behaviour is to record that it ran into an {@link AdvisorAuditLog}
 * and then delegate to the rest of the chain. It is deliberately not a heavy
 * {@code QuestionAnswerAdvisor} (which would need an embedding model and a
 * vector store) so the demonstration stays deterministic and fully offline.
 *
 * <p>It implements <em>both</em> {@link CallAdvisor} and {@link StreamAdvisor}
 * so it fires regardless of whether the request is dispatched through
 * {@code ChatClient.call()} or {@code ChatClient.stream()}. Atmosphere's
 * {@code SpringAiAgentRuntime} always uses the streaming path
 * ({@code promptSpec.stream().chatResponse()}), so in this sample the audit
 * record happens inside {@link #adviseStream}.</p>
 *
 * <p>The recording is the single observable side effect that proves the advisor
 * actually executed inside the {@code ChatClient} advisor chain — the whole
 * point of the sample.</p>
 */
public final class AuditingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(AuditingAdvisor.class);

    private final String name;
    private final int order;
    private final AdvisorAuditLog auditLog;

    /**
     * @param name     the advisor name recorded into the audit log (also its
     *                 Spring AI advisor name)
     * @param order    the chain order; lower runs earlier (Spring AI orders the
     *                 advisor chain by {@link org.springframework.core.Ordered})
     * @param auditLog the shared log this advisor records into when it runs
     */
    public AuditingAdvisor(String name, int order, AdvisorAuditLog auditLog) {
        this.name = name;
        this.order = order;
        this.auditLog = auditLog;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        record();
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {
        record();
        return chain.nextStream(request);
    }

    private void record() {
        auditLog.record(name);
        logger.info("Advisor '{}' ran in the Spring AI ChatClient chain", name);
    }
}
