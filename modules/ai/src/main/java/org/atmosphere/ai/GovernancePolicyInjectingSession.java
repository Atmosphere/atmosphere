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

import java.util.LinkedHashMap;
import java.util.Map;

import org.atmosphere.ai.governance.GovernancePolicyChain;

/**
 * {@link StreamingSession} decorator that adds the effective
 * {@link GovernancePolicyChain} to the tool-execution injectables map. The
 * resource-free {@code AiPipeline} dispatch paths (channel bridges, A2A, AG-UI,
 * coordinator-local) have no {@link org.atmosphere.cpr.AtmosphereResource} to
 * carry policies, so {@code ToolExecutionHelper} reads the chain from here to
 * run per-tool-call admission with parity to the {@code @AiEndpoint} path
 * (Correctness Invariant #7, Mode Parity).
 *
 * <p>The merge is computed lazily on each {@link #injectables()} call rather
 * than captured at construction, so a late addition to the delegate's
 * injectables (the volatile map on {@code AiStreamingSession}) is never lost.
 * If the delegate already carries a chain it is left untouched.</p>
 */
final class GovernancePolicyInjectingSession extends DelegatingStreamingSession {

    private final GovernancePolicyChain chain;

    GovernancePolicyInjectingSession(StreamingSession delegate, GovernancePolicyChain chain) {
        super(delegate);
        this.chain = chain;
    }

    @Override
    public Map<Class<?>, Object> injectables() {
        var base = delegate.injectables();
        if (chain == null || chain.isEmpty() || base.containsKey(GovernancePolicyChain.class)) {
            return base;
        }
        var merged = new LinkedHashMap<Class<?>, Object>(base);
        merged.put(GovernancePolicyChain.class, chain);
        return Map.copyOf(merged);
    }
}
