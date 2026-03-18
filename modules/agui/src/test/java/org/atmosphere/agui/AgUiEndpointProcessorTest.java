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
package org.atmosphere.agui;

import org.atmosphere.agui.annotation.AgUiAction;
import org.atmosphere.agui.annotation.AgUiEndpoint;
import org.atmosphere.agui.processor.AgUiEndpointProcessor;
import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.agui.runtime.AgUiStreamingSession;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgUiEndpointProcessorTest {

    private AtmosphereFramework framework;
    private AgUiEndpointProcessor processor;

    @AgUiEndpoint(path = "/agui-test")
    static class ValidEndpoint {
        @AgUiAction
        public void onRun(RunContext run, AgUiStreamingSession session) {
            session.send("test");
            session.complete();
        }
    }

    @AgUiEndpoint(path = "/agui-no-action")
    static class NoActionEndpoint {
        public void notAnnotated(RunContext run, AgUiStreamingSession session) {
            // Missing @AgUiAction
        }
    }

    // Not annotated with @AgUiEndpoint
    static class NotAnEndpoint {
        @AgUiAction
        public void onRun(RunContext run, AgUiStreamingSession session) {
            session.send("test");
        }
    }

    @AgUiEndpoint(path = "/agui-multi")
    static class MultiActionEndpoint {
        @AgUiAction
        public void firstAction(RunContext run, AgUiStreamingSession session) {
            session.send("first");
        }

        @AgUiAction
        public void secondAction(RunContext run, AgUiStreamingSession session) {
            session.send("second");
        }
    }

    @BeforeEach
    void setUp() {
        framework = mock(AtmosphereFramework.class);
        processor = new AgUiEndpointProcessor();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorRegistersHandlerAtConfiguredPath() throws Exception {
        when(framework.newClassInstance(any(), any()))
                .thenReturn(new ValidEndpoint());

        processor.handle(framework, (Class<Object>) (Class<?>) ValidEndpoint.class);

        var pathCaptor = ArgumentCaptor.forClass(String.class);
        var handlerCaptor = ArgumentCaptor.forClass(AtmosphereHandler.class);
        var interceptorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(framework).addAtmosphereHandler(
                pathCaptor.capture(), handlerCaptor.capture(), interceptorsCaptor.capture());

        assertEquals("/agui-test", pathCaptor.getValue());
        assertInstanceOf(AgUiHandler.class, handlerCaptor.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorIgnoresNonAnnotated() {
        // NotAnEndpoint has no @AgUiEndpoint annotation
        processor.handle(framework, (Class<Object>) (Class<?>) NotAnEndpoint.class);

        // Should not register any handler
        verify(framework, never()).addAtmosphereHandler(
                any(String.class), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorFailsWithoutAgUiAction() throws Exception {
        when(framework.newClassInstance(any(), any()))
                .thenReturn(new NoActionEndpoint());

        processor.handle(framework, (Class<Object>) (Class<?>) NoActionEndpoint.class);

        // Should NOT register any handler since no @AgUiAction method found
        verify(framework, never()).addAtmosphereHandler(
                any(String.class), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorHandlesInstantiationException() throws Exception {
        when(framework.newClassInstance(any(), any()))
                .thenThrow(new InstantiationException("Cannot instantiate"));

        processor.handle(framework, (Class<Object>) (Class<?>) ValidEndpoint.class);

        // Should not throw, should log error and not register
        verify(framework, never()).addAtmosphereHandler(
                any(String.class), any(AtmosphereHandler.class), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorRegistersFirstActionMethod() throws Exception {
        when(framework.newClassInstance(any(), any()))
                .thenReturn(new MultiActionEndpoint());

        processor.handle(framework, (Class<Object>) (Class<?>) MultiActionEndpoint.class);

        // Should register a handler (using the first @AgUiAction found)
        verify(framework).addAtmosphereHandler(
                eq("/agui-multi"), any(AgUiHandler.class), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProcessorPassesEmptyInterceptorList() throws Exception {
        when(framework.newClassInstance(any(), any()))
                .thenReturn(new ValidEndpoint());

        processor.handle(framework, (Class<Object>) (Class<?>) ValidEndpoint.class);

        @SuppressWarnings("rawtypes")
        var interceptorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(framework).addAtmosphereHandler(
                any(String.class), any(AtmosphereHandler.class), interceptorsCaptor.capture());

        var interceptors = interceptorsCaptor.getValue();
        assertEquals(0, interceptors.size(), "Interceptor list should be empty");
    }
}
