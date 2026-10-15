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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.processor.AiEndpointProcessor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * On a plain servlet deployment, the framework's first {@code @AiEndpoint}
 * creates the application's {@link AiEnvironment} from its init-params: the
 * {@code atmosphere.ai.*} knobs, and, when no settings are configured, the
 * {@code org.atmosphere.ai.llm*} coordinates. Destroying the framework closes it.
 */
class PlainServletAiEnvironmentTest {

    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(System.getenv("LLM_MODE") == null && System.getenv(AiConfig.TEMPERATURE_ENV) == null
                        && System.getenv(AiConfig.RUNTIME_ENV) == null,
                "LLM_* environment variables win over init-params");
        org.atmosphere.ai.AiConfigTestSupport.resetSettings();
        AgentRuntimeResolver.reset();
    }

    @AfterEach
    void tearDown() {
        if (framework != null && !framework.isDestroyed()) {
            framework.destroy();
        }
        AiConfig.resetHostConfig();
        org.atmosphere.ai.AiConfigTestSupport.resetSettings();
        AgentRuntimeResolver.reset();
    }

    @Test
    void theInitParamsBuildTheApplicationsEnvironmentAndTheDestroyClosesIt() throws ServletException {
        framework = new AtmosphereFramework();
        // JUnit on the classpath turns the whole-classpath scan off; scan this package.
        framework.addAnnotationPackage(SelectionProbeEndpoint.class);

        framework.init(servletConfig(Map.of(
                AiConfig.TEMPERATURE_PROPERTY, "0.3",
                AiConfig.RUNTIME_PROPERTY, "built-in",
                AiConfig.LLM_MODE, "fake",
                AiConfig.LLM_MODEL, "init-param-model")));

        var environment = AiEnvironment.find(framework.getAtmosphereConfig()).orElseThrow(
                () -> new AssertionError("the @AiEndpoint registration created the application's environment"));
        assertEquals("0.3", environment.knob(AiConfig.TEMPERATURE_PROPERTY).orElseThrow());
        assertEquals("built-in", environment.runtimes().resolve().name());
        var settings = environment.settings();
        // An LLM_MODEL environment variable wins over the init-param, as it does for a knob.
        var operatorModel = System.getenv("LLM_MODEL");
        assertEquals(operatorModel != null && !operatorModel.isBlank() ? operatorModel.trim() : "init-param-model",
                settings.model(), "the init-param coordinates built the settings");
        assertEquals("fake", settings.mode(), "the init-param mode");
        assertEquals(0.3, settings.generation().temperature(), "with the environment's knobs");
        assertTrue(AiEnvironment.live().contains(environment));

        framework.destroy();

        assertTrue(environment.isClosed(), "the framework's destroy closes its environment");
        assertFalse(AiEnvironment.live().contains(environment));
        assertTrue(AiEnvironment.find(framework.getAtmosphereConfig()).isEmpty());
    }

    private static ServletConfig servletConfig(Map<String, String> initParams) {
        var context = mock(ServletContext.class);
        Map<Class<? extends Annotation>, Set<Class<?>>> scanned = new HashMap<>();
        scanned.put(AtmosphereAnnotation.class, new HashSet<>(Set.of(AiEndpointProcessor.class)));
        scanned.put(AiEndpoint.class, new HashSet<>(Set.of(SelectionProbeEndpoint.class)));
        when(context.getAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE)).thenReturn(scanned);
        return new ServletConfig() {
            @Override
            public String getServletName() {
                return "environment";
            }

            @Override
            public ServletContext getServletContext() {
                return context;
            }

            @Override
            public String getInitParameter(String name) {
                return initParams.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(initParams.keySet());
            }
        };
    }
}
