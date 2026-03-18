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
package org.atmosphere.a2a;

import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.a2a.annotation.A2aServer;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.processor.A2aServerProcessor;
import org.atmosphere.a2a.runtime.A2aHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aServerProcessorTest {

    private AtmosphereFramework framework;
    private A2aServerProcessor processor;

    @A2aServer(name = "test-agent", description = "A test agent",
            version = "2.0.0", endpoint = "/test/a2a")
    static class AnnotatedAgent {
        @A2aSkill(id = "hello", name = "Hello", description = "Say hello")
        @A2aTaskHandler
        public void hello(TaskContext task, @A2aParam(name = "name") String name) {
            task.addArtifact(Artifact.text("Hello, " + name + "!"));
            task.complete("Greeted");
        }

        @A2aSkill(id = "farewell", name = "Farewell", description = "Say goodbye")
        @A2aTaskHandler
        public void farewell(TaskContext task) {
            task.addArtifact(Artifact.text("Goodbye!"));
            task.complete("Farewelled");
        }
    }

    // Class without @A2aServer annotation
    static class NonAnnotatedClass {
        public void doSomething() {
        }
    }

    @A2aServer(name = "empty-agent", endpoint = "/empty")
    static class EmptyAgent {
        // No skills defined
    }

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereConfig()).thenReturn(mock(AtmosphereConfig.class));
        processor = new A2aServerProcessor();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorRegistersHandler() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new AnnotatedAgent());

        processor.handle(framework, (Class<Object>) (Class<?>) AnnotatedAgent.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(
                eq("/test/a2a"), handlerCaptor.capture(),
                any(List.class));

        var registeredHandler = handlerCaptor.getValue();
        assertNotNull(registeredHandler);
        assertInstanceOf(A2aHandler.class, registeredHandler);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorIgnoresNonAnnotated() throws Exception {
        processor.handle(framework, (Class<Object>) (Class<?>) NonAnnotatedClass.class);

        // Should not attempt to create an instance or register a handler
        verify(framework, never()).newClassInstance(any(), any());
        verify(framework, never()).addAtmosphereHandler(
                anyString(), any(AtmosphereHandler.class), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorHandlesInstantiationError() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenThrow(new RuntimeException("Cannot instantiate"));

        // Should not throw, but log the error
        assertDoesNotThrow(() ->
                processor.handle(framework, (Class<Object>) (Class<?>) AnnotatedAgent.class));

        // Should not register a handler since instantiation failed
        verify(framework, never()).addAtmosphereHandler(
                anyString(), any(AtmosphereHandler.class), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorRegistersAtCorrectEndpoint() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new AnnotatedAgent());

        processor.handle(framework, (Class<Object>) (Class<?>) AnnotatedAgent.class);

        // Verify the endpoint matches the annotation
        verify(framework).addAtmosphereHandler(
                eq("/test/a2a"), any(AtmosphereHandler.class),
                any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorRegistersEmptyAgent() throws Exception {
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(new EmptyAgent());

        processor.handle(framework, (Class<Object>) (Class<?>) EmptyAgent.class);

        // Even with no skills, the handler should still be registered
        verify(framework).addAtmosphereHandler(
                eq("/empty"), any(AtmosphereHandler.class),
                any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorUsesAnnotationMetadata() throws Exception {
        var agentInstance = new AnnotatedAgent();
        when(framework.newClassInstance(eq(Object.class), any()))
                .thenReturn(agentInstance);

        processor.handle(framework, (Class<Object>) (Class<?>) AnnotatedAgent.class);

        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(
                eq("/test/a2a"), handlerCaptor.capture(),
                any(List.class));

        // The handler should be an A2aHandler wrapping the protocol handler
        assertInstanceOf(A2aHandler.class, handlerCaptor.getValue());
    }
}
