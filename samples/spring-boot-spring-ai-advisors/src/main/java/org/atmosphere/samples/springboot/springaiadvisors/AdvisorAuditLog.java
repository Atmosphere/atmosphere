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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single observable side effect of this sample: every time a Spring AI
 * {@link org.springframework.ai.chat.client.advisor.api.Advisor} actually runs
 * inside the {@code ChatClient} advisor chain, it appends its name here. The
 * delivery test and the {@code /api/advisors/audit-log} REST endpoint read this
 * log to prove an advisor <em>executed</em> — not merely that a bean exists.
 *
 * <p>Exposed as a process-wide singleton via {@link #shared()} because the
 * per-request {@link PerRequestAuditInterceptor} is instantiated by the
 * framework's object factory (no-arg) and cannot receive the bean by
 * constructor injection; the singleton lets the interceptor, the bound default
 * advisor (built in {@link BoundChatClientConfig}), and the Spring-managed
 * {@code @Bean} all write to and read from the same instance. The Spring
 * {@code @Bean} in {@link BoundChatClientConfig} simply returns
 * {@link #shared()}.</p>
 */
public final class AdvisorAuditLog {

    private static final AdvisorAuditLog SHARED = new AdvisorAuditLog();

    private final List<String> invocations = new CopyOnWriteArrayList<>();

    /** The process-wide instance shared by the config, interceptor, and advisors. */
    public static AdvisorAuditLog shared() {
        return SHARED;
    }

    /** Record that the named advisor ran (called from inside the advisor chain). */
    public void record(String advisorName) {
        invocations.add(advisorName);
    }

    /** How many times the named advisor ran since the last {@link #clear()}. */
    public int count(String advisorName) {
        return (int) invocations.stream().filter(advisorName::equals).count();
    }

    /** Immutable snapshot of the advisor names in execution order. */
    public List<String> invocations() {
        return List.copyOf(invocations);
    }

    /** Reset the log (used by the delivery test's per-test isolation). */
    public void clear() {
        invocations.clear();
    }
}
