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
package org.atmosphere.agent.command;

import org.atmosphere.agent.processor.AgentHandler;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.ai.processor.AiHandlerDecorator;
import org.atmosphere.cpr.AtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings {@code @Command} slash-command routing to plain {@code @AiEndpoint}
 * classes, so a slash command behaves identically whether the endpoint is an
 * {@code @Agent} or an {@code @AiEndpoint} (Correctness Invariant #7, Mode
 * Parity).
 *
 * <p>Discovered by {@link AiEndpointHandler}'s processor via {@link
 * java.util.ServiceLoader}: when an {@code @AiEndpoint} class declares at least
 * one {@code @Command} method, its freshly built {@link AiEndpointHandler} is
 * wrapped in an {@link AgentHandler} — the exact same command-routing decorator
 * the {@code @Agent} path uses — so {@code /}-prefixed messages hit the command
 * (an instant, LLM-free reply) and everything else falls through to the AI
 * pipeline unchanged.</p>
 *
 * <p>Endpoints with no {@code @Command} methods are returned untouched, so the
 * decorator is a no-op for the common case and adds no per-request cost there.
 * Only classes processed as {@code @AiEndpoint} reach here; {@code @Agent}
 * classes are already command-routed by their own processor, so there is no
 * double wrapping.</p>
 */
public final class CommandRoutingHandlerDecorator implements AiHandlerDecorator {

    private static final Logger logger =
            LoggerFactory.getLogger(CommandRoutingHandlerDecorator.class);

    @Override
    public AtmosphereHandler decorate(AtmosphereHandler handler, Context context) {
        if (!(handler instanceof AiEndpointHandler aiHandler)) {
            // Already wrapped by another decorator, or not an AI endpoint
            // handler — nothing to route commands over.
            return handler;
        }
        var registry = new CommandRegistry();
        registry.scan(context.targetClass());
        if (registry.size() == 0) {
            // No @Command methods declared — leave the endpoint untouched.
            return handler;
        }
        var router = new CommandRouter(registry, context.target());
        logger.info("Slash-command routing enabled for @AiEndpoint {} ({} command(s))",
                context.path(), registry.size());
        return new AgentHandler(aiHandler, router, context.target(),
                context.framework().getAtmosphereConfig());
    }
}
