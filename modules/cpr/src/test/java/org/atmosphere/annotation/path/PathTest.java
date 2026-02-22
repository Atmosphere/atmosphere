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
package org.atmosphere.annotation.path;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PathTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<String> r = new AtomicReference<String>();
    public static int instanceCount = 0;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(AtmosphereHandlerPath.class);
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            @Override
            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init();
    }

    @AfterEach
    public void after() {
        r.set(null);
        framework.destroy();
    }

    @ManagedService(path = "/{f}")
    public final static class ManagedPath {

        public ManagedPath() {
            ++instanceCount;
        }

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource.getRequest().getPathInfo());
        }
    }

    @Test
    public void testManagedPathMessage() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/test").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/test", r.get());

    }

    @AtmosphereHandlerService(path = "/ah/{g}")
    public final static class AtmosphereHandlerPath implements AtmosphereHandler {

        public AtmosphereHandlerPath() {
            ++instanceCount;
        }

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            r.set(resource.getRequest().getPathInfo());
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        @Override
        public void destroy() {

        }

    }

    @Test
    public void testAtmosphereHandlerPath() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/ah/test2").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/ah/test2", r.get());

    }

    @ManagedService(path = "/foo/{g}/{h}")
    public final static class ManagedDoublePath {

        public ManagedDoublePath() {
            ++instanceCount;
        }

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource.getRequest().getPathInfo());
        }
    }

    @Test
    public void testManagedManagedDoublePathMessage() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/foo/bar/yo").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/foo/bar/yo", r.get());

    }

    @WebSocketHandlerService(path = "/ws/{g}")
    public final static class WebSocketHandlerPath extends WebSocketHandlerAdapter {

        public WebSocketHandlerPath() {
            ++instanceCount;
        }

        public void onOpen(WebSocket webSocket) throws IOException {
            r.set(webSocket.resource().getRequest().getPathInfo());
        }
    }

    @Test
    public void testManagedWebSocketPathMessage() throws IOException, ServletException {
        instanceCount = 0;

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/ws/bar").method("GET").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/ws/bar", r.get());
    }

    public final class ArrayBaseWebSocket extends WebSocket {

        private final OutputStream outputStream;

        public ArrayBaseWebSocket(OutputStream outputStream) {
            super(framework.getAtmosphereConfig());
            this.outputStream = outputStream;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public WebSocket write(String s) throws IOException {
            outputStream.write(s.getBytes());
            return this;
        }

        @Override
        public WebSocket write(byte[] b, int offset, int length) throws IOException {
            outputStream.write(b, offset, length);
            return this;
        }

        @Override
        public void close() {
        }
    }

    @Singleton
    @ManagedService(path = "/singleton/managed/{a}")
    public final static class SingletonManagedPath {

        public SingletonManagedPath() {
            ++instanceCount;
        }

        @Get
        public void get(AtmosphereResource resource) {
            r.set(resource.getRequest().getPathInfo());
        }
    }

    @Test
    public void testSingletonManaged() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/singleton/managed/yes").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(0, instanceCount);
        assertNotNull(r.get());
        assertEquals("/singleton/managed/yes", r.get());

    }

    @Singleton
    @AtmosphereHandlerService(path = "/singleton/atmospherehandler/{g}", interceptors = {OnDisconnectInterceptor.class})
    public final static class SingletonAtmosphereHandlerPath implements AtmosphereHandler {

        public SingletonAtmosphereHandlerPath() {
            ++instanceCount;
        }

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            r.set(resource.getRequest().getPathInfo());
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        @Override
        public void destroy() {

        }

    }

    @Test
    public void testSingletonAtmosphereHandler() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/singleton/atmospherehandler/yes").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(0, instanceCount);
        assertNotNull(r.get());
        assertEquals("/singleton/atmospherehandler/yes", r.get());

    }

    public final static class MyInterceptor implements AtmosphereInterceptor {

        private static int invokationCount = 0;

        @Override
        public Action inspect(AtmosphereResource r) {
            invokationCount++;
            return Action.CONTINUE;
        }

        @Override
        public void postInspect(AtmosphereResource r) {

        }

        @Override
        public void destroy() {

        }

        @Override
        public void configure(AtmosphereConfig config) {

        }
    }

    @Singleton
    @WebSocketHandlerService(path = "/singleton/ws/{g}", interceptors = MyInterceptor.class)
    public final static class SingletonWebSocketHandlerPath extends WebSocketHandlerAdapter {

        public SingletonWebSocketHandlerPath() {
            ++instanceCount;
        }

        public void onOpen(WebSocket webSocket) throws IOException {
            r.set(webSocket.resource().getRequest().getPathInfo());
        }
    }

    @Test
    public void testSingletonManagedWebSocketPathMessage() throws IOException, ServletException {
        instanceCount = 0;

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        final WebSocket w = new ArrayBaseWebSocket(b);
        final WebSocketProcessor processor = WebSocketProcessorFactory.getDefault()
                .getWebSocketProcessor(framework);

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/singleton/ws/bar").method("GET").build();
        processor.open(w, request, AtmosphereResponseImpl.newInstance(framework.getAtmosphereConfig(), request, w));
        assertEquals(0, instanceCount);
        assertNotNull(r.get());
        assertEquals(1, MyInterceptor.invokationCount);
        assertEquals("/singleton/ws/bar", r.get());
    }

    @ManagedService(path = "/pathVar/{a}/pathTest/{b}")
    public final static class PathVar {

        public PathVar() {
            ++instanceCount;
        }

        @PathParam
        private String a;

        @PathParam("b")
        private String b1;

        @Get
        public void get(AtmosphereResource resource) {
            r.set(a + "#" + b1);
        }
    }

    @Test
    public void testPathVar() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/pathVar/aaa/pathTest/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("aaa#b123", r.get());

    }

    @ManagedService(path = "/inject/{inject}")
    public final static class InjectRuntime {

        public InjectRuntime() {
            ++instanceCount;
        }

        @Inject
        @Named("/{inject}")
        private Broadcaster b;

        @Get
        public void get(AtmosphereResource resource) {
            r.set(b.getID());
        }
    }

    @Test
    public void testNamedInjection() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/inject/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/b123", r.get());

    }

    @ManagedService(path = "/resource/{inject}")
    public final static class InjectAtmosphereResource {

        public InjectAtmosphereResource() {
            ++instanceCount;
        }

        @Inject
        private AtmosphereResource resource;

        @Get
        public void get() {
            r.set(resource.getRequest().getPathInfo());
        }
    }

    @Test
    public void testAtmosphereResourceInjection() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/resource/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/resource/b123", r.get());

    }

    @ManagedService(path = "/request/{inject}")
    public final static class InjectAtmosphereRequest {

        public InjectAtmosphereRequest() {
            ++instanceCount;
        }

        @Inject
        private AtmosphereRequest request;

        @Get
        public void get() {
            r.set(request.getPathInfo());
        }
    }

    @Test
    public void testAtmosphereRequestInjection() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/request/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/request/b123", r.get());

    }

    @ManagedService(path = "/response/{inject}")
    public final static class InjectAtmosphereResponse {

        public InjectAtmosphereResponse() {
            ++instanceCount;
        }

        @Inject
        private AtmosphereResponse response;

        @Get
        public void get() {
            r.set(response.request().getPathInfo());
        }
    }

    @Test
    public void testAtmosphereResponsetInjection() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/response/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/response/b123", r.get());

    }

    @ManagedService(path = "/resourceEvent/{inject}")
    public final static class InjectAtmosphereResourceEvent{

        public InjectAtmosphereResourceEvent() {
            ++instanceCount;
        }

        @Inject
        private AtmosphereResourceEvent event;

        @Get
        public void get() {
            r.set(event.getResource().getRequest().getPathInfo());
        }
    }

    @Test
    public void testAtmosphereResourceEventInjection() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/resourceEvent/b123").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        assertEquals(1, instanceCount);
        assertNotNull(r.get());
        assertEquals("/resourceEvent/b123", r.get());

    }
}
