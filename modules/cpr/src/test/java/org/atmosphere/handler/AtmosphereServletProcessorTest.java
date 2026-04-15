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
package org.atmosphere.handler;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServletProcessor;

import jakarta.servlet.ServletException;

import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AtmosphereServletProcessorTest {

    @Test
    void extendsAtmosphereHandler() {
        assertEquals(AtmosphereHandler.class, AtmosphereServletProcessor.class.getInterfaces()[0]);
    }

    @Test
    void isInterface() {
        org.junit.jupiter.api.Assertions.assertTrue(AtmosphereServletProcessor.class.isInterface());
    }

    @Test
    void declaresInitMethod() {
        assertDoesNotThrow(() -> {
            Method init = AtmosphereServletProcessor.class.getDeclaredMethod("init", AtmosphereConfig.class);
            assertNotNull(init);
            assertEquals(void.class, init.getReturnType());
        });
    }

    @Test
    void initMethodDeclaresServletException() {
        assertDoesNotThrow(() -> {
            Method init = AtmosphereServletProcessor.class.getDeclaredMethod("init", AtmosphereConfig.class);
            Class<?>[] exceptions = init.getExceptionTypes();
            assertEquals(1, exceptions.length);
            assertEquals(ServletException.class, exceptions[0]);
        });
    }

    @Test
    void inheritsOnRequestFromAtmosphereHandler() {
        assertDoesNotThrow(() -> {
            Method onRequest = AtmosphereServletProcessor.class.getMethod("onRequest", AtmosphereResource.class);
            assertNotNull(onRequest);
        });
    }

    @Test
    void inheritsOnStateChangeFromAtmosphereHandler() {
        assertDoesNotThrow(() -> {
            Method onStateChange = AtmosphereServletProcessor.class.getMethod("onStateChange", AtmosphereResourceEvent.class);
            assertNotNull(onStateChange);
        });
    }

    @Test
    void inheritsDestroyFromAtmosphereHandler() {
        assertDoesNotThrow(() -> {
            Method destroy = AtmosphereServletProcessor.class.getMethod("destroy");
            assertNotNull(destroy);
        });
    }

    @Test
    void implementationCanBeInstantiated() {
        AtmosphereServletProcessor processor = new AtmosphereServletProcessor() {
            @Override
            public void init(AtmosphereConfig config) throws ServletException {
                // no-op
            }

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                // no-op
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                // no-op
            }

            @Override
            public void destroy() {
                // no-op
            }
        };

        assertNotNull(processor);
    }
}
