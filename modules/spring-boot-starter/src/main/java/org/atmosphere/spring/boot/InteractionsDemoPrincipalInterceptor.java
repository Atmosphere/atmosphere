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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;

/**
 * Demo-only principal shim for the interactions live-stream socket.
 *
 * <p>The REST endpoints get their demo principal from a servlet {@code Filter}
 * (see {@code InteractionsAutoConfiguration#atmosphereInteractionsDemoPrincipal}).
 * A filter only runs in the servlet container's filter chain, which a
 * long-polling poll re-enters on every request — but a WebSocket upgrades once
 * and every subsequent frame is handled by Atmosphere's WebSocket processor
 * <em>outside</em> that chain, so the filter-set {@code ai.userId} attribute
 * never reaches the {@link AtmosphereResource}'s request. The stream handler
 * then resolves {@code anonymous} and the ownership check fails closed.</p>
 *
 * <p>An {@code AtmosphereInterceptor} runs inside Atmosphere's own request
 * lifecycle for <em>all</em> transports (WebSocket / SSE / long-polling), so
 * stamping the demo principal here makes ownership resolution transport-agnostic.
 * This mirrors the documented extension point real auth stacks use to populate
 * {@code ai.userId} (see {@code AiEndpointHandler}). DEMO ONLY — gated behind the
 * {@code atmosphere.interactions.demo-principal} opt-in with a loud startup
 * warning; never enable in production.</p>
 */
final class InteractionsDemoPrincipalInterceptor extends AtmosphereInterceptorAdapter {

    private final String principal;

    InteractionsDemoPrincipalInterceptor(String principal) {
        this.principal = principal;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        // Do not clobber a real principal (servlet getUserPrincipal / an upstream
        // auth interceptor); only fill in the demo value when nothing set it.
        if (r.getRequest() != null && r.getRequest().getAttribute("ai.userId") == null) {
            r.getRequest().setAttribute("ai.userId", principal);
        }
        return Action.CONTINUE;
    }
}
