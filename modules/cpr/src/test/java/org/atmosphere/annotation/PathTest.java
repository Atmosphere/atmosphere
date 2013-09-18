/*
 * Copyright 2013 Jean-Francois Arcand
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
package org.atmosphere.annotation;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;


public class PathTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<String> r = new AtomicReference<String>();
    public static int instanceCount = 0;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
        framework.addAnnotationPackage(ManagedPath.class);
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

    @AfterMethod
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/test").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 1);
        assertNotNull(r.get());
        assertEquals(r.get(), "/test");

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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/ah/test2").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 1);
        assertNotNull(r.get());
        assertEquals(r.get(), "/ah/test2");

    }

    @MeteorService(path = "/a/b/{g}")
    public final static class MeteorPath extends HttpServlet {

        public MeteorPath() {
            ++instanceCount;
        }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            r.set(req.getPathInfo());
        }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        }

        @Override
        public void destroy() {

        }

    }

    @Test
    public void testMeteorPath() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/a/b/test2").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 1);
        assertNotNull(r.get());
        assertEquals(r.get(), "/a/b/test2");

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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/foo/bar/yo").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 1);
        assertNotNull(r.get());
        assertEquals(r.get(), "/foo/bar/yo");

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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/ws/bar").method("GET").build();
        processor.open(w, request, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), request, w));
        assertEquals(instanceCount, 1);
        assertNotNull(r.get());
        assertEquals(r.get(), "/ws/bar");
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/singleton/managed/yes").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 0);
        assertNotNull(r.get());
        assertEquals(r.get(), "/singleton/managed/yes");

    }

    @Singleton
    @AtmosphereHandlerService(path = "/singleton/atmospherehandler/{g}")
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/singleton/atmospherehandler/yes").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 0);
        assertNotNull(r.get());
        assertEquals(r.get(), "/singleton/atmospherehandler/yes");

    }

    @Singleton
    @MeteorService(path = "/singleton/meteor/{g}")
    public final static class SingletonMeteorPath extends HttpServlet {

        public SingletonMeteorPath() {
            ++instanceCount;
        }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            r.set(req.getPathInfo());
        }

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        }

        @Override
        public void destroy() {

        }

    }

    @Test
    public void testSingletonMeteorPath() throws IOException, ServletException {
        instanceCount = 0;

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/singleton/meteor/test2").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        assertEquals(instanceCount, 0);
        assertNotNull(r.get());
        assertEquals(r.get(), "/singleton/meteor/test2");

    }

    @Singleton
    @WebSocketHandlerService(path = "/singleton/ws/{g}")
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

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/singleton/ws/bar").method("GET").build();
        processor.open(w, request, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), request, w));
        assertEquals(instanceCount, 0);
        assertNotNull(r.get());
        assertEquals(r.get(), "/singleton/ws/bar");
    }
}
