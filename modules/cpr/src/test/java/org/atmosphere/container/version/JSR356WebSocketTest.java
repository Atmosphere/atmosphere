package org.atmosphere.container.version;

import org.atmosphere.cpr.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class JSR356WebSocketTest {

    private JSR356WebSocket webSocket;
    private Session session;
    private RemoteEndpoint.Async asyncRemoteEndpoint;

    @BeforeMethod
    public void setUp() throws Exception {
        session = mock(Session.class);
        asyncRemoteEndpoint = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemoteEndpoint);
        webSocket = new JSR356WebSocket(session, new AtmosphereFramework().getAtmosphereConfig());
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

}