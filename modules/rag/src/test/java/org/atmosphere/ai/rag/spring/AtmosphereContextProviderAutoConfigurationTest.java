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
package org.atmosphere.ai.rag.spring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.ai.processor.AiEndpointProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "No ContextProvider configured" warning must fire only when no provider
 * applies: an {@code @AiEndpoint(contextProviders = ...)} wires its providers
 * without any bean, and the warning used to fire at bean creation regardless.
 */
class AtmosphereContextProviderAutoConfigurationTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(AtmosphereContextProviderAutoConfiguration.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void anEndpointThatWiredAProviderSilencesTheWarning() {
        var configuration = new AtmosphereContextProviderAutoConfiguration();
        configuration.contextProvider();

        configuration.report(frameworkWith(Map.of(
                AiEndpointProcessor.WIRED_CONTEXT_PROVIDERS_PROPERTY, 2)));

        assertEquals(0, warnings(), "retrieval is wired by the endpoint: " + appender.list);
    }

    @Test
    void theWarningFiresWhenNothingRetrieves() {
        var configuration = new AtmosphereContextProviderAutoConfiguration();
        configuration.contextProvider();

        configuration.report(frameworkWith(Map.of()));

        assertEquals(1, warnings(), "only the empty default exists: " + appender.list);
    }

    @Test
    void noWarningWhenTheApplicationSuppliedItsOwnBean() {
        // The default factory method never ran: an application bean won.
        new AtmosphereContextProviderAutoConfiguration().report(frameworkWith(Map.of()));

        assertEquals(0, warnings());
    }

    @Test
    void theDecisionNeedsTheDefaultAndNoWiredProvider() {
        assertTrue(AtmosphereContextProviderAutoConfiguration.shouldWarn(true, 0));
        assertFalse(AtmosphereContextProviderAutoConfiguration.shouldWarn(true, 1));
        assertFalse(AtmosphereContextProviderAutoConfiguration.shouldWarn(false, 0));
    }

    private long warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().startsWith("No ContextProvider configured"))
                .count();
    }

    private static AtmosphereFramework frameworkWith(Map<String, Object> properties) {
        var config = mock(AtmosphereConfig.class);
        when(config.properties()).thenReturn(new ConcurrentHashMap<>(properties));
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        return framework;
    }
}
