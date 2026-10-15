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
package org.atmosphere.ai.processor.selection;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AgentRuntimeSelectionException;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.processor.AiEndpointProcessor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * On a plain servlet deployment, which has no Spring or Quarkus startup check,
 * an {@code atmosphere.ai.runtime} that names no available runtime fails the
 * framework's initialization, as it fails the application's startup on the
 * Spring Boot starters and the Quarkus extension, instead of the framework
 * starting with every {@code @AiEndpoint} missing.
 */
class PlainServletRuntimeSelectionTest {

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AgentRuntimeResolver.reset();
        if (framework != null) {
            framework.destroy();
        }
    }

    @Test
    void aRuntimeNamedButNotAvailableFailsTheFrameworkInit() {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        AgentRuntimeResolver.reset();
        framework = new AtmosphereFramework();
        // JUnit on the classpath turns the whole-classpath scan off; scan this package.
        framework.addAnnotationPackage(SelectionProbeEndpoint.class);

        var failure = assertThrows(ServletException.class, () -> framework.init(servletConfig()));

        var messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
        }
        assertTrue(messages.toString().contains(
                "atmosphere.ai.runtime=no-such-runtime names no available AgentRuntime"), messages.toString());
    }

    /**
     * The no-argument {@code init()} the gRPC server and the embedded
     * WebSocket handler call: the failure reaches the caller instead of being
     * logged while the host starts with no AI endpoint.
     */
    @Test
    void aRuntimeNamedButNotAvailableFailsTheNoArgumentInit() {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        AgentRuntimeResolver.reset();
        framework = new AtmosphereFramework();
        // No annotation map in the servlet context: the bytecode scan of this
        // package runs, and finds the @AiEndpoint processor in the package named.
        framework.addAnnotationPackage(SelectionProbeEndpoint.class);
        framework.addInitParameter(ApplicationConfig.CUSTOM_ANNOTATION_PACKAGE,
                AiEndpointProcessor.class.getPackage().getName());

        var failure = assertThrows(AgentRuntimeSelectionException.class, () -> framework.init());

        assertTrue(failure.getMessage().contains(
                "atmosphere.ai.runtime=no-such-runtime names no available AgentRuntime"), failure.getMessage());
        assertFalse(framework.initialized(), "the framework did not initialize");
    }

    @Test
    void theNamedRuntimeRegistersTheEndpoint() throws ServletException {
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "built-in");
        AgentRuntimeResolver.reset();
        framework = new AtmosphereFramework();
        // JUnit on the classpath turns the whole-classpath scan off; scan this package.
        framework.addAnnotationPackage(SelectionProbeEndpoint.class);

        framework.init(servletConfig());

        assertTrue(framework.getAtmosphereHandlers().containsKey("/atmosphere/selection-probe"),
                "registered handlers: " + framework.getAtmosphereHandlers().keySet());
    }

    /**
     * A servlet container's annotation scan result, as the Atmosphere
     * {@code ServletContainerInitializer} (or the Quarkus extension) hands it
     * over: the {@code @AiEndpoint} processor and the one endpoint.
     */
    private static ServletConfig servletConfig() {
        var context = mock(ServletContext.class);
        Map<Class<? extends Annotation>, Set<Class<?>>> scanned = new HashMap<>();
        scanned.put(AtmosphereAnnotation.class, new HashSet<>(Set.of(AiEndpointProcessor.class)));
        scanned.put(AiEndpoint.class, new HashSet<>(Set.of(SelectionProbeEndpoint.class)));
        when(context.getAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE)).thenReturn(scanned);
        return new ServletConfig() {
            @Override
            public String getServletName() {
                return "selection";
            }

            @Override
            public ServletContext getServletContext() {
                return context;
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }
        };
    }
}
