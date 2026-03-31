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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.WebTransportProcessorFactory;
import org.atmosphere.util.SimpleBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTransportSpiTest {

    // ── Adapter tests ─────────────────────────────────────────────────────

    @Test
    void handlerAdapterIsNoOp() throws IOException {
        var adapter = new WebTransportHandlerAdapter();
        // All methods should complete without error
        adapter.onOpen(null);
        adapter.onClose(null);
        adapter.onTextMessage(null, "test");
        adapter.onByteMessage(null, new byte[0], 0, 0);
        adapter.onError(null, null);
    }

    @Test
    void processorAdapterIsNoOp() {
        var adapter = new WebTransportProcessorAdapter();
        assertSame(adapter, adapter.configure(null));
        assertSame(adapter, adapter.registerWebTransportHandler("/test", null));
        // All methods should complete without error
        adapter.open(null, null, null);
        adapter.invokeWebTransportProtocol(null, "test");
        adapter.invokeWebTransportProtocol(null, new byte[0], 0, 0);
        adapter.close(null, 0);
        adapter.notifyListener(null, null);
        adapter.destroy();
    }

    // ── Handler proxy delegation ──────────────────────────────────────────

    @Test
    void handlerProxyDelegatesToProxied() throws IOException {
        var openCalled = new AtomicBoolean(false);
        var closeCalled = new AtomicBoolean(false);
        var textRef = new AtomicReference<String>();

        WebTransportHandler handler = new WebTransportHandlerAdapter() {
            @Override
            public void onOpen(WebTransportSession session) {
                openCalled.set(true);
            }

            @Override
            public void onClose(WebTransportSession session) {
                closeCalled.set(true);
            }

            @Override
            public void onTextMessage(WebTransportSession session, String data) {
                textRef.set(data);
            }
        };

        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        proxy.path("/chat");

        assertEquals("/chat", proxy.path());
        assertSame(handler, proxy.proxied());

        proxy.onOpen(null);
        assertTrue(openCalled.get());

        proxy.onTextMessage(null, "hello");
        assertEquals("hello", textRef.get());

        proxy.onClose(null);
        assertTrue(closeCalled.get());
    }

    // ── Exception ─────────────────────────────────────────────────────────

    @Test
    void webTransportExceptionHoldsResponse() {
        var response = AtmosphereResponseImpl.newInstance();
        var ex = new WebTransportProcessor.WebTransportException("test error", response);
        assertEquals("test error", ex.getMessage());
        assertSame(response, ex.response());
    }

    @Test
    void webTransportExceptionWrapsThrowable() {
        var response = AtmosphereResponseImpl.newInstance();
        var cause = new RuntimeException("root cause");
        var ex = new WebTransportProcessor.WebTransportException(cause, response);
        assertSame(cause, ex.getCause());
        assertSame(response, ex.response());
    }

    // ── Event listener ────────────────────────────────────────────────────

    @Test
    void eventTypes() {
        var types = WebTransportEventListener.WebTransportEvent.TYPE.values();
        assertEquals(6, types.length);
    }

    @Test
    void eventToString() {
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "hello", WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, null);
        assertEquals("hello", event.message());
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, event.type());
        assertTrue(event.toString().contains("hello"));
        assertTrue(event.toString().contains("MESSAGE"));
    }

    @Test
    void eventListenerAdapterIsNoOp() {
        var adapter = new WebTransportEventListenerAdapter();
        adapter.onOpen(null);
        adapter.onMessage(null);
        adapter.onClose((WebTransportEventListener.WebTransportEvent<?>) null);
        adapter.onDisconnect((WebTransportEventListener.WebTransportEvent<?>) null);
        adapter.onConnect((WebTransportEventListener.WebTransportEvent<?>) null);
    }

    // ── Factory ───────────────────────────────────────────────────────────

    @Test
    void factoryReturnsSingleton() {
        var f1 = WebTransportProcessorFactory.getDefault();
        var f2 = WebTransportProcessorFactory.getDefault();
        assertSame(f1, f2);
    }

    @Test
    void factoryDefaultsToAdapter() throws Exception {
        var framework = new AtmosphereFramework();
        try {
            var processor = WebTransportProcessorFactory.getDefault()
                    .getWebTransportProcessor(framework);
            assertNotNull(processor);
            assertInstanceOf(WebTransportProcessorAdapter.class, processor);
        } finally {
            framework.destroy();
        }
    }

    @Test
    void factoryCachesPerFramework() throws Exception {
        var framework = new AtmosphereFramework();
        try {
            var p1 = WebTransportProcessorFactory.getDefault()
                    .getWebTransportProcessor(framework);
            var p2 = WebTransportProcessorFactory.getDefault()
                    .getWebTransportProcessor(framework);
            assertSame(p1, p2);
        } finally {
            framework.destroy();
        }
    }

    // ── Session constants ─────────────────────────────────────────────────

    @Test
    void sessionConstants() {
        assertNotNull(WebTransportSession.WEBTRANSPORT_INITIATED);
        assertNotNull(WebTransportSession.WEBTRANSPORT_SUSPEND);
        assertEquals("WebTransport protocol not supported", WebTransportSession.NOT_SUPPORTED);
    }

    // ── Concrete stub for WebTransportSession ────────────────────────────

    static class StubWebTransportSession extends WebTransportSession {
        private boolean open = true;
        private final List<String> writtenStrings = new ArrayList<>();
        private final List<byte[]> writtenBytes = new ArrayList<>();

        StubWebTransportSession(AtmosphereConfig config) {
            super(config);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public WebTransportSession write(String s) {
            writtenStrings.add(s);
            return this;
        }

        @Override
        public WebTransportSession write(byte[] b, int offset, int length) {
            writtenBytes.add(Arrays.copyOfRange(b, offset, offset + length));
            return this;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(int errorCode, String reason) {
            open = false;
        }

        void setOpen(boolean open) {
            this.open = open;
        }
    }

    private AtmosphereFramework sessionFramework;
    private StubWebTransportSession stubSession;

    @BeforeEach
    void setUpSession() {
        sessionFramework = new AtmosphereFramework();
        stubSession = new StubWebTransportSession(sessionFramework.getAtmosphereConfig());
    }

    @AfterEach
    void tearDownSession() {
        if (sessionFramework != null) {
            sessionFramework.destroy();
        }
    }

    // ── WebTransportSession tests ────────────────────────────────────────

    @Test
    void sessionWriteStringDelegatesToAbstract() throws IOException {
        stubSession.write("hello world");
        assertEquals(1, stubSession.writtenStrings.size());
        assertEquals("hello world", stubSession.writtenStrings.getFirst());
    }

    @Test
    void sessionWriteBinaryDelegatesToAbstract() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        stubSession.write(data, 1, 3);
        assertEquals(1, stubSession.writtenBytes.size());
        byte[] written = stubSession.writtenBytes.getFirst();
        assertEquals(3, written.length);
        assertEquals(2, written[0]);
        assertEquals(3, written[1]);
        assertEquals(4, written[2]);
    }

    @Test
    void sessionCloseMarksNotOpen() {
        assertTrue(stubSession.isOpen());
        stubSession.close();
        assertFalse(stubSession.isOpen());
    }

    @Test
    void sessionCloseWithCodeMarksNotOpen() {
        assertTrue(stubSession.isOpen());
        stubSession.close(0, "reason");
        assertFalse(stubSession.isOpen());
    }

    @Test
    void sessionResourceAssociation() {
        assertNull(stubSession.resource());
        stubSession.resource(null);
        assertNull(stubSession.resource());
    }

    @Test
    void sessionUuidDefaultsToNull() {
        assertEquals("NUll", stubSession.uuid());
    }

    @Test
    void sessionAttachment() {
        assertNull(stubSession.attachment());
        var payload = "test-attachment";
        stubSession.attachment(payload);
        assertSame(payload, stubSession.attachment());
    }

    @Test
    void sessionAttachmentCanBeOverwritten() {
        stubSession.attachment("first");
        assertEquals("first", stubSession.attachment());
        stubSession.attachment("second");
        assertEquals("second", stubSession.attachment());
    }

    @Test
    void sessionAttachmentCanBeNull() {
        stubSession.attachment("value");
        assertNotNull(stubSession.attachment());
        stubSession.attachment(null);
        assertNull(stubSession.attachment());
    }

    @Test
    void sessionBroadcastWithoutResourceLogsDebug() {
        // No resource set — broadcast should not throw
        assertDoesNotThrow(() -> stubSession.broadcast("message"));
    }

    @Test
    void sessionNotSupportedSendsError() throws IOException {
        var request = AtmosphereRequestImpl.newInstance();
        var response = AtmosphereResponseImpl.newInstance();
        response.delegateToNativeResponse(false);
        response.asyncIOWriter(new org.atmosphere.util.ByteArrayAsyncWriter());
        WebTransportSession.notSupported(request, response);
        assertEquals("WebTransport protocol not supported",
                response.getHeader("X-Atmosphere-error"));
        assertEquals(501, response.getStatus());
    }

    @Test
    void sessionConfigAccessor() {
        assertNotNull(stubSession.config());
        assertSame(sessionFramework.getAtmosphereConfig(), stubSession.config());
    }

    @Test
    void sessionWebTransportHandler() {
        assertNull(stubSession.webTransportHandler());
        var handler = new WebTransportHandlerAdapter();
        stubSession.webTransportHandler(handler);
        assertSame(handler, stubSession.webTransportHandler());
    }

    @Test
    void sessionLastWriteTimeStamp() {
        // lastWrite defaults to 0; lastWriteTimeStampInMilliseconds returns 0 or current millis
        long ts = stubSession.lastWriteTimeStampInMilliseconds();
        assertTrue(ts >= 0, "lastWriteTimeStampInMilliseconds should return a non-negative value");
    }

    @Test
    void sessionWriteFullByteArray() throws IOException {
        byte[] data = {10, 20, 30};
        stubSession.write(data);
        assertEquals(1, stubSession.writtenBytes.size());
        byte[] written = stubSession.writtenBytes.getFirst();
        assertEquals(3, written.length);
        assertEquals(10, written[0]);
        assertEquals(20, written[1]);
        assertEquals(30, written[2]);
    }

    @Test
    void sessionCleanCloseConstant() {
        assertEquals("Clean_Close", WebTransportSession.CLEAN_CLOSE);
    }

    // ── WebTransportProcessor.WebTransportHandlerProxy tests ─────────────

    @Test
    void processorHandlerProxyWithCustomBroadcaster() {
        var handler = new WebTransportHandlerAdapter();
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(
                SimpleBroadcaster.class, handler);
        assertSame(SimpleBroadcaster.class, proxy.broadcasterClazz);
        assertSame(handler, proxy.proxied());
    }

    @Test
    void processorHandlerProxyDefaultBroadcaster() {
        var handler = new WebTransportHandlerAdapter();
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        assertSame(SimpleBroadcaster.class, proxy.broadcasterClazz);
    }

    @Test
    void processorHandlerProxyPathRoundTrip() {
        var handler = new WebTransportHandlerAdapter();
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        assertNull(proxy.path());
        proxy.path("/stream");
        assertEquals("/stream", proxy.path());
    }

    @Test
    void processorHandlerProxyDelegatesOnError() {
        var errorRef = new AtomicReference<WebTransportProcessor.WebTransportException>();
        WebTransportHandler handler = new WebTransportHandlerAdapter() {
            @Override
            public void onError(WebTransportSession session,
                                WebTransportProcessor.WebTransportException t) {
                errorRef.set(t);
            }
        };
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        var exception = new WebTransportProcessor.WebTransportException(
                "test", AtmosphereResponseImpl.newInstance());
        proxy.onError(null, exception);
        assertSame(exception, errorRef.get());
    }

    @Test
    void processorHandlerProxyDelegatesOnByteMessage() throws IOException {
        var received = new AtomicReference<byte[]>();
        WebTransportHandler handler = new WebTransportHandlerAdapter() {
            @Override
            public void onByteMessage(WebTransportSession session, byte[] data,
                                      int offset, int length) {
                received.set(Arrays.copyOfRange(data, offset, offset + length));
            }
        };
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        byte[] data = {1, 2, 3, 4, 5};
        proxy.onByteMessage(null, data, 1, 3);
        assertNotNull(received.get());
        assertEquals(3, received.get().length);
        assertEquals(2, received.get()[0]);
    }

    // ── WebTransportEventListener tests ──────────────────────────────────

    @Test
    void allEventTypesExist() {
        var types = WebTransportEventListener.WebTransportEvent.TYPE.values();
        assertEquals(6, types.length);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.CONNECT, types[0]);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.OPEN, types[1]);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.CLOSE, types[2]);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, types[3]);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.DISCONNECT, types[4]);
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.EXCEPTION, types[5]);
    }

    @Test
    void eventSessionAccessor() {
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "msg", WebTransportEventListener.WebTransportEvent.TYPE.OPEN, stubSession);
        assertSame(stubSession, event.session());
    }

    @Test
    void eventMessageAccessor() {
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "payload", WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, null);
        assertEquals("payload", event.message());
    }

    @Test
    void eventToStringContainsTypeAndMessage() {
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "data", WebTransportEventListener.WebTransportEvent.TYPE.CLOSE, null);
        var str = event.toString();
        assertTrue(str.contains("CLOSE"), "toString should contain the type");
        assertTrue(str.contains("data"), "toString should contain the message");
    }

    @Test
    void eventWithNullMessage() {
        var event = new WebTransportEventListener.WebTransportEvent<String>(
                null, WebTransportEventListener.WebTransportEvent.TYPE.DISCONNECT, null);
        assertNull(event.message());
        assertEquals(WebTransportEventListener.WebTransportEvent.TYPE.DISCONNECT, event.type());
    }

    @Test
    void eventWithNullSession() {
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "msg", WebTransportEventListener.WebTransportEvent.TYPE.EXCEPTION, null);
        assertNull(event.session());
    }

    @Test
    void eventWithBinaryMessage() {
        byte[] binary = {1, 2, 3};
        var event = new WebTransportEventListener.WebTransportEvent<>(
                binary, WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, null);
        assertSame(binary, event.message());
    }

    // ── WebTransportProcessorFactory tests ───────────────────────────────

    @Test
    void factoryDestroysCachedProcessors() throws Exception {
        var framework = new AtmosphereFramework();
        try {
            var factory = WebTransportProcessorFactory.getDefault();
            factory.getWebTransportProcessor(framework);
            assertFalse(factory.processors().isEmpty());
            factory.destroy();
            assertTrue(factory.processors().isEmpty());
        } finally {
            framework.destroy();
        }
    }

    @Test
    void factoryWithDifferentFrameworks() throws Exception {
        var fw1 = new AtmosphereFramework();
        var fw2 = new AtmosphereFramework();
        try {
            var factory = WebTransportProcessorFactory.getDefault();
            var p1 = factory.getWebTransportProcessor(fw1);
            var p2 = factory.getWebTransportProcessor(fw2);
            assertNotSame(p1, p2, "Different frameworks should get different processors");
        } finally {
            WebTransportProcessorFactory.getDefault().destroy();
            fw1.destroy();
            fw2.destroy();
        }
    }

    // ── WebTransportProcessorAdapter additional tests ────────────────────

    @Test
    void processorAdapterConfigureReturnsSelf() {
        var adapter = new WebTransportProcessorAdapter();
        var result = adapter.configure(sessionFramework.getAtmosphereConfig());
        assertSame(adapter, result);
    }

    @Test
    void processorAdapterRegisterReturnsSelf() {
        var adapter = new WebTransportProcessorAdapter();
        var handler = new WebTransportHandlerAdapter();
        var proxy = new WebTransportProcessor.WebTransportHandlerProxy(handler);
        var result = adapter.registerWebTransportHandler("/path", proxy);
        assertSame(adapter, result);
    }

    @Test
    void processorAdapterOpenDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        assertDoesNotThrow(() -> adapter.open(stubSession,
                AtmosphereRequestImpl.newInstance(), AtmosphereResponseImpl.newInstance()));
    }

    @Test
    void processorAdapterInvokeTextDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        assertDoesNotThrow(() -> adapter.invokeWebTransportProtocol(stubSession, "text"));
    }

    @Test
    void processorAdapterInvokeBinaryDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        assertDoesNotThrow(() -> adapter.invokeWebTransportProtocol(
                stubSession, new byte[]{1, 2, 3}, 0, 3));
    }

    @Test
    void processorAdapterCloseDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        assertDoesNotThrow(() -> adapter.close(stubSession, 0));
    }

    @Test
    void processorAdapterNotifyListenerDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "msg", WebTransportEventListener.WebTransportEvent.TYPE.MESSAGE, stubSession);
        assertDoesNotThrow(() -> adapter.notifyListener(stubSession, event));
    }

    @Test
    void processorAdapterDestroyDoesNotThrow() {
        var adapter = new WebTransportProcessorAdapter();
        assertDoesNotThrow(adapter::destroy);
    }

    // ── WebTransportException additional tests ───────────────────────────

    @Test
    void webTransportExceptionMessageAndResponse() {
        var response = AtmosphereResponseImpl.newInstance();
        var ex = new WebTransportProcessor.WebTransportException("detail", response);
        assertEquals("detail", ex.getMessage());
        assertSame(response, ex.response());
        assertNull(ex.getCause());
    }

    @Test
    void webTransportExceptionWithNullResponse() {
        var ex = new WebTransportProcessor.WebTransportException("msg", null);
        assertEquals("msg", ex.getMessage());
        assertNull(ex.response());
    }

    @Test
    void webTransportExceptionCauseChain() {
        var root = new IllegalStateException("root");
        var response = AtmosphereResponseImpl.newInstance();
        var ex = new WebTransportProcessor.WebTransportException(root, response);
        assertSame(root, ex.getCause());
        assertSame(response, ex.response());
    }

    // ── WebTransportHandlerAdapter additional tests ──────────────────────

    @Test
    void handlerAdapterOnErrorIsNoOp() {
        var adapter = new WebTransportHandlerAdapter();
        var exception = new WebTransportProcessor.WebTransportException(
                "err", AtmosphereResponseImpl.newInstance());
        assertDoesNotThrow(() -> adapter.onError(null, exception));
    }

    @Test
    void handlerAdapterAllMethodsWithNonNullSession() throws IOException {
        var adapter = new WebTransportHandlerAdapter();
        assertDoesNotThrow(() -> adapter.onOpen(stubSession));
        assertDoesNotThrow(() -> adapter.onClose(stubSession));
        assertDoesNotThrow(() -> adapter.onTextMessage(stubSession, "text"));
        assertDoesNotThrow(() -> adapter.onByteMessage(stubSession, new byte[]{1}, 0, 1));
    }

    // ── WebTransportEventListenerAdapter additional tests ────────────────

    @Test
    void eventListenerAdapterAllMethodsAreNoOp() {
        var adapter = new WebTransportEventListenerAdapter();
        var event = new WebTransportEventListener.WebTransportEvent<>(
                "msg", WebTransportEventListener.WebTransportEvent.TYPE.CONNECT, stubSession);
        assertDoesNotThrow(() -> adapter.onConnect(event));
        assertDoesNotThrow(() -> adapter.onOpen(event));
        assertDoesNotThrow(() -> adapter.onMessage(event));
        assertDoesNotThrow(() -> adapter.onClose(event));
        assertDoesNotThrow(() -> adapter.onDisconnect(event));
    }

    @Test
    void eventListenerAdapterResourceEventMethodsAreNoOp() {
        var adapter = new WebTransportEventListenerAdapter();
        assertDoesNotThrow(() -> adapter.onPreSuspend(null));
        assertDoesNotThrow(() -> adapter.onSuspend(null));
        assertDoesNotThrow(() -> adapter.onResume(null));
        assertDoesNotThrow(() -> adapter.onHeartbeat(null));
        assertDoesNotThrow(() -> adapter.onDisconnect((org.atmosphere.cpr.AtmosphereResourceEvent) null));
        assertDoesNotThrow(() -> adapter.onBroadcast(null));
        assertDoesNotThrow(() -> adapter.onThrowable(null));
        assertDoesNotThrow(() -> adapter.onClose((org.atmosphere.cpr.AtmosphereResourceEvent) null));
    }
}
