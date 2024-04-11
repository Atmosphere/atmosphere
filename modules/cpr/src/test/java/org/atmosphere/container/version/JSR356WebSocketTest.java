/*
 * Copyright 2018 Jason Burgess
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
package org.atmosphere.container.version;

import org.atmosphere.container.JSR356Endpoint;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.websocket.WebSocketProcessor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.websocket.EndpointConfig;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import static org.atmosphere.cpr.ApplicationConfig.JSR356_MAPPING_PATH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class JSR356WebSocketTest {

    private JSR356WebSocket webSocket;
    private Session session;
    private RemoteEndpoint.Async asyncRemoteEndpoint;

    @BeforeMethod
    public void setUp() throws Exception {
        session = mock(Session.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getRequestURI()).thenReturn(URI.create("/"));

        asyncRemoteEndpoint = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemoteEndpoint);
        webSocket = new JSR356WebSocket(session, new AtmosphereFramework().getAtmosphereConfig()) {
            @Override
            public boolean isOpen() {
                return true;
            }
        };
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_successful_write() throws Exception {
        mockWriteResult(new SendResult());

        webSocket.write("Hello");
        webSocket.write("Hello");

        verify(asyncRemoteEndpoint, times(2)).sendText(eq("Hello"), any(SendHandler.class));
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_failing_write() throws Exception {
        mockWriteResult(new SendResult(new RuntimeException("Fails")));

        webSocket.write("Hello");
        webSocket.write("Hello");

        verify(asyncRemoteEndpoint, times(2)).sendText(eq("Hello"), any(SendHandler.class));
    }

    @Test(timeOut = 1000)
    public void test_semaphore_is_released_in_case_of_NPE_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new NullPointerException()).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    @Test(timeOut = 1000, expectedExceptions = RuntimeException.class)
    public void test_semaphore_is_released_in_case_of_ERROR_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new Error("Unexpected error")).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    @Test(timeOut = 1000, expectedExceptions = RuntimeException.class)
    public void test_semaphore_is_released_in_case_of_RuntimeException_in_getAsyncRemote() throws Exception {
        when(session.getAsyncRemote()).thenThrow(new IllegalArgumentException("Invalid argument")).thenReturn(asyncRemoteEndpoint);
        webSocket.write("Hello1");
        webSocket.write("Hello2");

        verify(asyncRemoteEndpoint).sendText(eq("Hello2"), any(SendHandler.class));
    }

    private void mockWriteResult(final SendResult sendResult) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(final InvocationOnMock invocationOnMock) throws Throwable {
                new Thread() {
                    @Override
                    public void run() {
                        ((SendHandler) invocationOnMock.getArguments()[1]).onResult(sendResult);
                    }
                }.start();
                return null;
            }
        }).when(asyncRemoteEndpoint).sendText(anyString(), any(SendHandler.class));
    }
    
    @Test
    public void testAttributePropagationFromHandshakeSessionToAtmosphereRequest() throws NoSuchFieldException, IllegalAccessException {
        HandshakeRequest mockHandshakeRequest = mock(HandshakeRequest.class);
        HttpSession mockHttpSession = mock(HttpSession.class);

        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("attribute1", "value1");
        sessionAttributes.put("attribute2", "value2");

        when(mockHandshakeRequest.getHttpSession()).thenReturn(mockHttpSession);
        when(mockHttpSession.getAttributeNames()).thenReturn(new Vector<>(sessionAttributes.keySet()).elements());
        when(mockHttpSession.getAttribute("attribute1")).thenReturn(sessionAttributes.get("attribute1"));
        when(mockHttpSession.getAttribute("attribute2")).thenReturn(sessionAttributes.get("attribute2"));

        EndpointConfig mockEndpointConfig = mock(EndpointConfig.class);
        when(mockEndpointConfig.getUserProperties()).thenReturn(new HashMap<String, Object>() {{
          put("handshakeRequest", mockHandshakeRequest);
        }});

        AtmosphereFramework mockFramework = mock(AtmosphereFramework.class);
        AtmosphereConfig mockConfig = mock(AtmosphereConfig.class);

        when(mockFramework.getAtmosphereConfig()).thenReturn(mockConfig);
        when(mockFramework.getServletContext()).thenReturn(mock(ServletContext.class));
        when(mockConfig.getInitParameter(JSR356_MAPPING_PATH)).thenReturn("/");
        when(mockConfig.getServletContext()).thenReturn(mock(ServletContext.class));

        WebSocketProcessor webSocketProcessor = mock(WebSocketProcessor.class);

        JSR356Endpoint endpoint = new JSR356Endpoint(mockFramework, webSocketProcessor);
        endpoint.handshakeRequest(mockHandshakeRequest);
        endpoint.onOpen(session, mockEndpointConfig);

        Field requestField = JSR356Endpoint.class.getDeclaredField("request");
        requestField.setAccessible(true);
        AtmosphereRequest request = (AtmosphereRequest) requestField.get(endpoint);

        Enumeration<String> attributeNames = request.getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();
            assertEquals(request.getAttribute(attributeName), sessionAttributes.get(attributeName),
                    "Attribute value should match the value from the HttpSession");
        }
    }

    @Test
    public void testOnOpenWithNullHandshakeSession() throws IOException {
        Session mockSession = Mockito.mock(Session.class);
        HandshakeRequest mockHandshakeRequest = Mockito.mock(HandshakeRequest.class);
        ServerEndpointConfig mockConfig = Mockito.mock(ServerEndpointConfig.class);
        WebSocketProcessor mockProcessor = Mockito.mock(WebSocketProcessor.class);
        RemoteEndpoint.Async mockAsyncRemote = Mockito.mock(RemoteEndpoint.Async.class);

        AtmosphereFramework mockFramework = mock(AtmosphereFramework.class);
        AtmosphereConfig mockAtmosphereConfig = mock(AtmosphereConfig.class);

        when(mockProcessor.handshake(Mockito.any(AtmosphereRequest.class))).thenReturn(true);
        when(mockFramework.getAtmosphereConfig()).thenReturn(mockAtmosphereConfig);
        when(mockFramework.getServletContext()).thenReturn(mock(ServletContext.class));
        when(mockAtmosphereConfig.getInitParameter(JSR356_MAPPING_PATH)).thenReturn("/");
        when(mockAtmosphereConfig.getServletContext()).thenReturn(mock(ServletContext.class));
        when(mockFramework.getAtmosphereConfig()).thenReturn(mockAtmosphereConfig);
        when(mockFramework.getServletContext()).thenReturn(mock(ServletContext.class));
        when(mockAtmosphereConfig.getInitParameter(JSR356_MAPPING_PATH)).thenReturn("/");
        when(mockAtmosphereConfig.getServletContext()).thenReturn(mock(ServletContext.class));

        JSR356Endpoint endpoint = new JSR356Endpoint(mockFramework, mockProcessor);

        when(mockSession.getAsyncRemote()).thenReturn(mockAsyncRemote);
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getRequestURI()).thenReturn(URI.create("/"));

        when(mockHandshakeRequest.getHttpSession()).thenReturn(null);
        when(mockHandshakeRequest.getHeaders()).thenReturn(Collections.emptyMap());

        endpoint.handshakeRequest(mockHandshakeRequest);

        endpoint.onOpen(mockSession, mockConfig);

        verify(mockSession, never()).close(Mockito.any());

    }
}