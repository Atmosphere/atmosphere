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
import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgUiHandlerTest {

    public static class TestEndpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            session.send("Hello, " + run.lastUserMessage());
            session.complete("Done");
        }
    }

    public static class ErrorEndpoint {
        @AgUiAction
        public void onRun(RunContext run, StreamingSession session) {
            throw new RuntimeException("Intentional failure");
        }
    }

    /**
     * Bundles the mock resource with the StringWriter used to capture SSE output.
     */
    record MockedResource(AtmosphereResource resource, StringWriter outputCapture,
                           jakarta.servlet.http.HttpServletResponse servletResponse) {
    }

    private MockedResource mockResource(String method, String body) throws Exception {
        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);
        when(resource.uuid()).thenReturn("test-uuid");
        when(request.getMethod()).thenReturn(method);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        var outputCapture = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(outputCapture));
        // Mock the raw servlet response for direct SSE writing
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.getResponse()).thenReturn(servletResponse);
        when(servletResponse.getWriter()).thenReturn(new PrintWriter(outputCapture));
        return new MockedResource(resource, outputCapture, servletResponse);
    }

    private AgUiHandler createHandler(Class<?> endpointClass) throws Exception {
        Object endpoint = endpointClass.getDeclaredConstructor().newInstance();
        Method actionMethod = null;
        for (var m : endpointClass.getDeclaredMethods()) {
            if (m.isAnnotationPresent(AgUiAction.class)) {
                actionMethod = m;
                break;
            }
        }
        if (actionMethod == null) {
            throw new IllegalStateException("No @AgUiAction method found");
        }
        return new AgUiHandler(endpoint, actionMethod);
    }

    @Test
    void testPostStartsRunAndEmitsRunStarted() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST",
                "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");

        handler.onRequest(mocked.resource());

        // Verify SSE headers were set on the raw servlet response
        var servletResponse = mocked.servletResponse();
        verify(servletResponse).setContentType("text/event-stream");
        verify(servletResponse).setCharacterEncoding("UTF-8");
        verify(servletResponse).setHeader("Cache-Control", "no-cache");
        verify(servletResponse).setHeader("Connection", "keep-alive");

        // POST-based SSE writes directly to servlet response (no suspend needed)

        // Give virtual thread time to execute
        Thread.sleep(200);

        // Verify RunStarted was written
        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("event: RUN_STARTED"), "Should emit RunStarted event");
        assertTrue(output.contains("\"runId\":\"r1\""), "Should contain the runId");
        assertTrue(output.contains("\"threadId\":\"t1\""), "Should contain the threadId");
    }

    @Test
    void testPostEmptyBodyReturns400() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST", "");

        handler.onRequest(mocked.resource());

        verify(mocked.resource().getResponse()).setStatus(400);
        // Should NOT suspend the resource
        verify(mocked.resource(), never()).suspend();
    }

    @Test
    void testPostInvalidJsonReturns400() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST", "not json at all {{{");

        handler.onRequest(mocked.resource());

        verify(mocked.resource().getResponse()).setStatus(400);
        verify(mocked.resource(), never()).suspend();
    }

    @Test
    void testGetSuspends() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("GET", "");

        handler.onRequest(mocked.resource());

        verify(mocked.resource()).suspend();
        verify(mocked.resource().getResponse()).setContentType("text/event-stream");
        verify(mocked.resource().getResponse()).setCharacterEncoding("UTF-8");
    }

    @Test
    void testUnsupportedMethodReturns405() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("PUT", "{}");

        handler.onRequest(mocked.resource());

        verify(mocked.resource().getResponse()).setStatus(405);
        verify(mocked.resource(), never()).suspend();
    }

    @Test
    void testDeleteMethodReturns405() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("DELETE", "");

        handler.onRequest(mocked.resource());

        verify(mocked.resource().getResponse()).setStatus(405);
    }

    @Test
    void testPostGeneratesRunIdWhenMissing() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}");

        handler.onRequest(mocked.resource());

        verify(mocked.servletResponse()).setContentType("text/event-stream");

        Thread.sleep(200);

        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("event: RUN_STARTED"), "Should emit RunStarted with generated runId");
    }

    @Test
    void testPostWithBlankRunIdGeneratesNewOne() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST",
                "{\"threadId\":\"t1\",\"runId\":\"\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}");

        handler.onRequest(mocked.resource());

        Thread.sleep(200);

        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("event: RUN_STARTED"), "Should emit RunStarted with generated runId");
    }

    @Test
    void testOnStateChangeHandlesCancelled() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var event = mock(org.atmosphere.cpr.AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        when(event.isCancelled()).thenReturn(true);
        when(event.getResource()).thenReturn(resource);
        when(resource.uuid()).thenReturn("test-uuid");

        // Should not throw
        handler.onStateChange(event);
    }

    @Test
    void testOnStateChangeHandlesClosedByClient() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var event = mock(org.atmosphere.cpr.AtmosphereResourceEvent.class);
        var resource = mock(AtmosphereResource.class);
        when(event.isClosedByClient()).thenReturn(true);
        when(event.getResource()).thenReturn(resource);
        when(resource.uuid()).thenReturn("test-uuid");

        // Should not throw
        handler.onStateChange(event);
    }

    @Test
    void testDestroyDoesNotThrow() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        handler.destroy();
        // No exception = success
    }

    @Test
    void testPostSetsCorrectResponseHeaders() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST",
                "{\"threadId\":\"t1\",\"runId\":\"r1\",\"messages\":[]}");

        handler.onRequest(mocked.resource());

        verify(mocked.servletResponse()).setContentType("text/event-stream");
        verify(mocked.servletResponse()).setCharacterEncoding("UTF-8");
        verify(mocked.servletResponse()).setHeader("Cache-Control", "no-cache");
        verify(mocked.servletResponse()).setHeader("Connection", "keep-alive");
    }

    @Test
    void testGetDoesNotSetCacheControlHeader() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("GET", "");

        handler.onRequest(mocked.resource());

        // GET handler uses Atmosphere response, not raw servlet response
        verify(mocked.resource().getResponse()).setContentType("text/event-stream");
        verify(mocked.resource().getResponse()).setCharacterEncoding("UTF-8");
        verify(mocked.servletResponse(), never()).setHeader("Cache-Control", "no-cache");
    }

    @Test
    void testPostWritesErrorMessageForEmptyBody() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST", "");

        handler.onRequest(mocked.resource());

        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("Empty body"), "Should write error message about empty body");
    }

    @Test
    void testPostWritesErrorMessageForInvalidJson() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("POST", "{{invalid");

        handler.onRequest(mocked.resource());

        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("Invalid request"), "Should write error about invalid JSON");
    }

    @Test
    void testUnsupportedMethodWritesErrorMessage() throws Exception {
        var handler = createHandler(TestEndpoint.class);
        var mocked = mockResource("PATCH", "");

        handler.onRequest(mocked.resource());

        var output = mocked.outputCapture().toString();
        assertTrue(output.contains("Method not allowed"), "Should write 'Method not allowed' error");
    }
}
