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

import org.atmosphere.agent.annotation.Command;
import org.atmosphere.agent.processor.AgentHandler;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.ai.processor.AiHandlerDecorator;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code @Command} slash-command routing reaches
 * {@code @AiEndpoint} classes (not only {@code @Agent}), via the
 * {@link AiHandlerDecorator} SPI. This closes the 4.0.60 release-gate gap where
 * the rag-chat console (an {@code @AiEndpoint}) sent {@code /sources} straight
 * to the LLM because {@code @Command} was only honored on the {@code @Agent}
 * path.
 */
class CommandRoutingHandlerDecoratorTest {

    /** An @AiEndpoint-style class that declares a @Command. */
    static class EndpointWithCommand {
        @Command(value = "/sources", description = "List sources")
        public String sources() {
            return "doc-a, doc-b";
        }
    }

    /** An @AiEndpoint-style class with no @Command methods. */
    static class EndpointWithoutCommand {
    }

    private final CommandRoutingHandlerDecorator decorator = new CommandRoutingHandlerDecorator();

    private static AiHandlerDecorator.Context contextFor(Object target) {
        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        return new AiHandlerDecorator.Context(
                target, target.getClass(), framework, "/atmosphere/ai-chat");
    }

    @Test
    void wrapsEndpointDeclaringACommandWithCommandRouting() {
        var target = new EndpointWithCommand();
        AtmosphereHandler bare = mock(AiEndpointHandler.class);

        var decorated = decorator.decorate(bare, contextFor(target));

        assertInstanceOf(AgentHandler.class, decorated,
                "an @AiEndpoint that declares a @Command must be wrapped with command routing");
    }

    @Test
    void leavesEndpointWithoutCommandsUntouched() {
        var target = new EndpointWithoutCommand();
        AtmosphereHandler bare = mock(AiEndpointHandler.class);

        var decorated = decorator.decorate(bare, contextFor(target));

        assertSame(bare, decorated,
                "an endpoint with no @Command methods must register unchanged (no per-request cost)");
    }

    @Test
    void ignoresHandlersThatAreNotAiEndpointHandlers() {
        var target = new EndpointWithCommand();
        AtmosphereHandler alreadyWrapped = mock(AtmosphereHandler.class);

        var decorated = decorator.decorate(alreadyWrapped, contextFor(target));

        assertSame(alreadyWrapped, decorated,
                "only a bare AiEndpointHandler is wrapped — no double decoration");
    }

    @Test
    void isDiscoverableViaServiceLoader() {
        var found = StreamSupport.stream(
                        ServiceLoader.load(AiHandlerDecorator.class).spliterator(), false)
                .anyMatch(d -> d instanceof CommandRoutingHandlerDecorator);
        assertTrue(found,
                "CommandRoutingHandlerDecorator must be registered under "
                        + "META-INF/services/org.atmosphere.ai.processor.AiHandlerDecorator "
                        + "so AiEndpointProcessor discovers it");
    }
}
