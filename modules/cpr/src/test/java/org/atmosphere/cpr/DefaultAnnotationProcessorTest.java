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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletContext;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAnnotationProcessorTest {

    private DefaultAnnotationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DefaultAnnotationProcessor();
    }

    @Test
    void constructorCreatesInstance() {
        assertNotNull(processor);
        assertInstanceOf(AnnotationProcessor.class, processor);
    }

    @Test
    void destroyBeforeConfigureDoesNotThrow() {
        assertDoesNotThrow(() -> processor.destroy());
    }

    @Test
    void destroyIsIdempotent() {
        assertDoesNotThrow(() -> {
            processor.destroy();
            processor.destroy();
        });
    }

    @Test
    void configureWithByteCodeProcessorFallback() {
        AtmosphereFramework framework = mock(AtmosphereFramework.class);
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        ServletContext servletContext = mock(ServletContext.class);

        when(config.framework()).thenReturn(framework);
        when(framework.getServletContext()).thenReturn(servletContext);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(servletContext.getAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.BYTECODE_PROCESSOR, false)).thenReturn(false);
        when(framework.customAnnotationPackages()).thenReturn(java.util.List.of());
        when(framework.getHandlersPath()).thenReturn("/WEB-INF/classes/");
        when(framework.getLibPath()).thenReturn("/WEB-INF/lib/");

        assertDoesNotThrow(() -> processor.configure(config));
        processor.destroy();
    }

    @Test
    void annotationAttributeConstant() {
        assertNotNull(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE);
    }

    @Test
    void scanWithPackageNameAfterConfigure() throws IOException {
        AtmosphereFramework framework = mock(AtmosphereFramework.class);
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        ServletContext servletContext = mock(ServletContext.class);

        when(config.framework()).thenReturn(framework);
        when(framework.getServletContext()).thenReturn(servletContext);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(servletContext.getAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE)).thenReturn(null);
        when(config.getInitParameter(ApplicationConfig.BYTECODE_PROCESSOR, false)).thenReturn(false);
        when(framework.customAnnotationPackages()).thenReturn(java.util.List.of());
        when(framework.getHandlersPath()).thenReturn("/WEB-INF/classes/");
        when(framework.getLibPath()).thenReturn("/WEB-INF/lib/");

        processor.configure(config);

        AnnotationProcessor result = processor.scan("org.atmosphere.nonexistent");
        assertNotNull(result);
        processor.destroy();
    }
}
