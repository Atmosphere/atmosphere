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
package org.atmosphere.initfailure;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInitializationException;
import org.atmosphere.util.SimpleBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * A {@link Processor} that throws {@link AtmosphereInitializationException}
 * fails the framework's initialization, where any other exception it throws is
 * logged and the framework starts without the class. Scanned through the
 * bytecode annotation detector, the path a container without the annotation
 * map takes. Kept in a package no other test scans, since every framework
 * scanning it would fail.
 */
class ProcessorInitFailureTest {

    static final AtmosphereInitializationException FAILURE =
            new AtmosphereInitializationException("the configuration names nothing that exists");

    private AtmosphereFramework framework;

    @AfterEach
    void tearDown() {
        if (framework != null) {
            framework.destroy();
        }
    }

    @Test
    void aProcessorFailingTheInitializationFailsTheFrameworkInit() {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
        framework.addAnnotationPackage(FailingAnnotation.class);

        var failure = assertThrows(ServletException.class, () -> framework.init(servletConfig()));

        assertSame(FAILURE, failure.getCause());
    }

    /**
     * The no-argument {@code init()}, which embedded hosts (the gRPC server,
     * {@code EmbeddedWebSocketHandler}) call, logs other initialization
     * failures; a processor failing the initialization reaches the caller
     * instead of the host starting on a framework that never initialized.
     */
    @Test
    void aProcessorFailingTheInitializationFailsTheNoArgumentInit() {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
        framework.addAnnotationPackage(FailingAnnotation.class);
        framework.addInitParameter(ApplicationConfig.CUSTOM_ANNOTATION_PACKAGE,
                ProcessorInitFailureTest.class.getPackage().getName());

        var failure = assertThrows(AtmosphereInitializationException.class, () -> framework.init());

        assertSame(FAILURE, failure);
        assertFalse(framework.initialized(), "the framework did not initialize");
    }

    private static ServletConfig servletConfig() {
        var context = mock(ServletContext.class);
        return new ServletConfig() {
            @Override
            public String getServletName() {
                return "init-failure";
            }

            @Override
            public ServletContext getServletContext() {
                return context;
            }

            @Override
            public String getInitParameter(String name) {
                // Outside org.atmosphere.annotation, a package's processors
                // are only scanned when it is named a custom-annotation package.
                return ApplicationConfig.CUSTOM_ANNOTATION_PACKAGE.equals(name)
                        ? ProcessorInitFailureTest.class.getPackage().getName() : null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }
        };
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FailingAnnotation {
    }

    @AtmosphereAnnotation(FailingAnnotation.class)
    public static final class FailingProcessor implements Processor<Object> {

        @Override
        public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
            throw FAILURE;
        }
    }

    @FailingAnnotation
    public static final class Annotated {
    }
}
