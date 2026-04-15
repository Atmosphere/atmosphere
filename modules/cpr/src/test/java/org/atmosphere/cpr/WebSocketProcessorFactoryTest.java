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
package org.atmosphere.cpr;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocketProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebSocketProcessorFactoryTest {

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init();
    }

    @AfterEach
    void tearDown() {
        WebSocketProcessorFactory.getDefault().destroy();
        framework.destroy();
    }

    @Test
    void getDefaultReturnsSameInstance() {
        var factory1 = WebSocketProcessorFactory.getDefault();
        var factory2 = WebSocketProcessorFactory.getDefault();
        assertSame(factory1, factory2);
    }

    @Test
    void getWebSocketProcessorReturnsDefaultProcessor() {
        var factory = WebSocketProcessorFactory.getDefault();
        WebSocketProcessor processor = factory.getWebSocketProcessor(framework);

        assertNotNull(processor);
        assertInstanceOf(DefaultWebSocketProcessor.class, processor);
    }

    @Test
    void getWebSocketProcessorReturnsCachedInstance() {
        var factory = WebSocketProcessorFactory.getDefault();
        WebSocketProcessor first = factory.getWebSocketProcessor(framework);
        WebSocketProcessor second = factory.getWebSocketProcessor(framework);

        assertSame(first, second);
    }

    @Test
    void processorsMapContainsCreatedProcessors() {
        var factory = WebSocketProcessorFactory.getDefault();
        factory.getWebSocketProcessor(framework);

        assertEquals(1, factory.processors().size());
        assertNotNull(factory.processors().get(framework));
    }

    @Test
    void destroyClearsProcessors() {
        var factory = WebSocketProcessorFactory.getDefault();
        factory.getWebSocketProcessor(framework);

        assertEquals(1, factory.processors().size());

        factory.destroy();

        assertEquals(0, factory.processors().size());
    }

    @Test
    void differentFrameworksGetDifferentProcessors() throws Exception {
        var framework2 = new AtmosphereFramework();
        framework2.setAsyncSupport(new BlockingIOCometSupport(framework2.getAtmosphereConfig()));
        framework2.init();

        try {
            var factory = WebSocketProcessorFactory.getDefault();
            WebSocketProcessor p1 = factory.getWebSocketProcessor(framework);
            WebSocketProcessor p2 = factory.getWebSocketProcessor(framework2);

            assertNotNull(p1);
            assertNotNull(p2);
            assertEquals(2, factory.processors().size());
        } finally {
            framework2.destroy();
        }
    }
}
