/*
 * Copyright 2018 Jean-Francois Arcand
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.websocket.WebSocket;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class AtmosphereResourceTest {
    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }


    @Test
    public void testUUID() throws IOException, ServletException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();

        final AtomicReference<String> e = new AtomicReference<String>();
        final AtomicReference<String> e2 = new AtomicReference<String>();

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                e.set(r.uuid());
                e2.set(r.getResponse().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID));
                return Action.CANCELLED;
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }

            @Override
            public void destroy() {

            }
        });
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        assertEquals(e.get(), e2.get());
    }

    @Test
    public void testCancelParentUUID() throws IOException, ServletException, InterruptedException {
        framework.addAtmosphereHandler("/a", new AbstractReflectorAtmosphereHandler() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });

        final AtmosphereRequest parentRequest = new AtmosphereRequestImpl.Builder().pathInfo("/a").queryString(HeaderConfig.WEBSOCKET_X_ATMOSPHERE_TRANSPORT).build();
        final CountDownLatch suspended = new CountDownLatch(1);

        framework.interceptor(new AtmosphereInterceptor() {
            @Override
            public void configure(AtmosphereConfig config) {
            }

            @Override
            public Action inspect(AtmosphereResource r) {
                try {
                    r.getBroadcaster().addAtmosphereResource(r);
                    if (suspended.getCount() == 1) {
                        r.suspend();
                        return Action.SUSPEND;
                    } else {
                        return Action.CONTINUE;
                    }
                } finally {
                    suspended.countDown();
                }
            }

            @Override
            public void destroy() {
            }

            @Override
            public void postInspect(AtmosphereResource r) {
            }
        });

        new Thread() {
            public void run() {
                try {
                    framework.doCometSupport(parentRequest, AtmosphereResponseImpl.newInstance().request(parentRequest));
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }.start();

        suspended.await();
        Map<String, Object> m = new HashMap<String, Object>();
        m.put(SUSPENDED_ATMOSPHERE_RESOURCE_UUID, parentRequest.resource().uuid());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().attributes(m).pathInfo("/a").queryString(HeaderConfig.WEBSOCKET_X_ATMOSPHERE_TRANSPORT).build();
        request.setAttribute(FrameworkConfig.WEBSOCKET_MESSAGE, "true");

        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance().request(request));

        AtmosphereResource r = parentRequest.resource();
        Broadcaster b = r.getBroadcaster();

        assertEquals(b.getAtmosphereResources().size(), 1);

        AtmosphereResourceImpl.class.cast(r).cancel();

        assertEquals(b.getAtmosphereResources().size(), 0);

    }

    @Test
    public void testHashCode(){
        String uuid = UUID.randomUUID().toString();

        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.setAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID,uuid);
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance(request);

        AtmosphereResourceImpl res0 = new AtmosphereResourceImpl();
        res0.initialize(framework.getAtmosphereConfig(),
                        framework.getBroadcasterFactory().get(),
                        request, response, null, null);

        AtmosphereResourceImpl res1 = new AtmosphereResourceImpl();
        res1.initialize(framework.getAtmosphereConfig(),
                        framework.getBroadcasterFactory().get(),
                        request, response, null, null);

        assertEquals(res0, res1);

        HashSet set = new HashSet();
        set.add(res0);
        set.add(res1);

        assertEquals(set.size(),1);
        assertTrue(set.contains(res0));
        assertTrue(set.contains(res1));
        assertEquals(res0,set.iterator().next());
        assertEquals(res1,set.iterator().next());
    }

    @Test
    public void testCloseResponseOutputStream() throws IOException {
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        AsyncIOWriter writer = mock(AsyncIOWriter.class);
        AsyncIOWriter wswriter = mock(WebSocket.class);

        response.asyncIOWriter(writer);
        ServletOutputStream sos = response.getOutputStream();
        sos.close();

        verify(writer, times(1)).close(response);
        reset(writer);

        response.asyncIOWriter(wswriter);
        sos = response.getOutputStream();
        sos.close();
        verify(wswriter, times(0)).close(response);
    }

    @Test
    public void testCloseResponseWriter() throws IOException {
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        AsyncIOWriter writer = mock(AsyncIOWriter.class);
        AsyncIOWriter wswriter = mock(WebSocket.class);

        response.asyncIOWriter(writer);
        PrintWriter pw = response.getWriter();
        pw.close();

        verify(writer, times(1)).close(response);
        reset(writer);

        response.asyncIOWriter(wswriter);
        pw = response.getWriter();
        pw.close();
        verify(wswriter, times(0)).close(response);
    }

    @Test
    public void testCompletionNotAwareForStartAsync() throws IOException {
        verifyTestCompletionAwareForStartAsync(false);
    }

    @Test
    public void testCompletionAwareForStartAsync() throws IOException {
        verifyTestCompletionAwareForStartAsync(true);
    }

    @Test
    public void testCompletionNotAwareForGetAsync() throws IOException {
        verifyTestCompletionAwareForGetAsync(false);
    }

    @Test
    public void testCompletionAwareForGetAsync() throws IOException {
        verifyTestCompletionAwareForGetAsync(true);
    }

    @Test
    public void testCompletionNotAwareForSync() throws IOException, ServletException {
        verifyTestCompletionAwareForSync(false);
    }

    @Test
    public void testCompletionAwareForSync() throws IOException, ServletException {
        verifyTestCompletionAwareForSync(true);
    }

    @Test
    public void testCompletionAwareForSyncButStartAsync() throws IOException, ServletException {
        Servlet s = mock(Servlet.class);
        framework.addInitParameter(ApplicationConfig.RESPONSE_COMPLETION_AWARE, "true");
        ReflectorServletProcessor handler = new ReflectorServletProcessor(s);
        handler.init(framework.getAtmosphereConfig());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        AtmosphereResponseImpl response = mock(AtmosphereResponseImpl.class);
        AtmosphereResourceImpl res = new AtmosphereResourceImpl();
        res.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, null, null);
        res.transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, res);
        request.setAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE, res);

        AsyncContext ac = request.startAsync();
        handler.onRequest(res);
        verify(response, times(0)).onComplete();
        ac.complete();
        verify(response, times(1)).onComplete();
    }

    @Test
    public void testResponseWritingUnbuffered() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder().asyncIOWriter(new TestAsyncIOWriter(baos)).build();
        response.getOutputStream();
        response.write("hello".getBytes());
        // written unbuffered
        assertEquals(baos.toString(), "hello");
        response.write("hello again".getBytes());
        // written unbuffered
        assertEquals(baos.toString(), "hellohello again");
    }

    @Test
    public void testResponseWritingBuffered() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtmosphereRequest request =  mock(AtmosphereRequestImpl.class);
        when(request.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_AWARE)).thenReturn(Boolean.TRUE);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .request(request).asyncIOWriter(new TestAsyncIOWriter(baos)).build();
        response.getOutputStream();
        response.write("hello".getBytes());
        // buffering the data and nothing written
        assertEquals(baos.toString(), "");
        response.write("hello again".getBytes());
        // buffering the new data and writing the previously buffered data
        assertEquals(baos.toString(), "hello");
        ((AtmosphereResponseImpl)response).onComplete();
        // the buffered data is written
        assertEquals(baos.toString(), "hellohello again");
        response.write("bye".getBytes());
        // written unbuffered
        assertEquals(baos.toString(), "hellohello againbye");
    }

    @Test
    public void testResponseWritingBufferedReset() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AtmosphereRequest request =  mock(AtmosphereRequestImpl.class);
        when(request.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_AWARE)).thenReturn(Boolean.TRUE);
        when(request.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_RESET)).thenReturn(Boolean.TRUE);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .request(request).asyncIOWriter(new TestAsyncIOWriter(baos)).build();
        response.getOutputStream();
        response.write("hello".getBytes());
        // buffering the data and nothing written
        assertEquals(baos.toString(), "");
        response.write("hello again".getBytes());
        // buffering the new data and writing the previously buffered data
        assertEquals(baos.toString(), "hello");
        ((AtmosphereResponseImpl)response).onComplete();
        // the buffered data is written
        assertEquals(baos.toString(), "hellohello again");
        response.write("bye".getBytes());
        // written buffered again
        assertEquals(baos.toString(), "hellohello again");
        response.write("bye again".getBytes());
        // the buffered data is written
        assertEquals(baos.toString(), "hellohello againbye");
        ((AtmosphereResponseImpl)response).onComplete();
        // the buffered data is flushed
        assertEquals(baos.toString(), "hellohello againbyebye again");
    }

    private static class TestAsyncIOWriter implements AsyncIOWriter {
        private OutputStream out;
        public TestAsyncIOWriter(OutputStream out) {
            this.out = out;
        }

        @Override
        public AsyncIOWriter redirect(AtmosphereResponse r, String location) throws IOException {
            return this;
        }

        @Override
        public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
            return this;
        }

        @Override
        public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
            return this;
        }

        @Override
        public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
            out.write(data);
            return this;
        }

        @Override
        public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            return this;
        }

        @Override
        public void close(AtmosphereResponse r) throws IOException {
        }

        @Override
        public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
            return this;
        }
    }

    private void verifyTestCompletionAwareForStartAsync(boolean aware) throws IOException {
        if (aware) {
            framework.addInitParameter(ApplicationConfig.RESPONSE_COMPLETION_AWARE, "true");
        }
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        AtmosphereResponseImpl response = mock(AtmosphereResponseImpl.class);
        AtmosphereResourceImpl res = new AtmosphereResourceImpl();
        res.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, null, null);
        res.transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, res);

        AsyncContext ac = request.startAsync();

        verify(response, times(0)).onComplete();
        ac.complete();
        verify(response, times(aware ? 1 : 0)).onComplete();
    }

    private void verifyTestCompletionAwareForGetAsync(boolean aware) throws IOException {
        if (aware) {
            framework.addInitParameter(ApplicationConfig.RESPONSE_COMPLETION_AWARE, "true");
        }
        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        AtmosphereResponseImpl response = mock(AtmosphereResponseImpl.class);
        AtmosphereResourceImpl res = new AtmosphereResourceImpl();
        res.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, null, null);
        res.transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, res);

        AsyncContext ac = request.getAsyncContext();

        verify(response, times(0)).onComplete();
        ac.complete();
        verify(response, times(aware ? 1 : 0)).onComplete();
    }

    private void verifyTestCompletionAwareForSync(boolean aware) throws IOException, ServletException {
        Servlet s = mock(Servlet.class);
        if (aware) {
            framework.addInitParameter(ApplicationConfig.RESPONSE_COMPLETION_AWARE, "true");
        }
        ReflectorServletProcessor handler = new ReflectorServletProcessor(s);
        handler.init(framework.getAtmosphereConfig());

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").build();
        AtmosphereResponseImpl response = mock(AtmosphereResponseImpl.class);
        AtmosphereResourceImpl res = new AtmosphereResourceImpl();
        res.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, null, null);
        res.transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, res);
        request.setAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE, res);

        handler.onRequest(res);
        verify(response, times(aware ? 1 : 0)).onComplete();
    }
}
