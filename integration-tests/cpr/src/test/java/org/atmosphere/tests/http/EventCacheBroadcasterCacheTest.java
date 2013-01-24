/*
* Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.tests.http;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.cache.EventCacheBroadcasterCache;
import org.atmosphere.container.Jetty7CometSupport;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.HeaderConfig;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public class EventCacheBroadcasterCacheTest {

    private static final Logger logger = LoggerFactory.getLogger(EventCacheBroadcasterCacheTest.class);

    private static final int MAX_CLIENT = 100;

    protected AtmosphereServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;
    protected Server server;
    protected Context root;
    private final static CountDownLatch suspended = new CountDownLatch(MAX_CLIENT);
    private final static CountDownLatch broadcasterReady = new CountDownLatch(MAX_CLIENT);


    public static class TestHelper {

        public static int getEnvVariable(final String varName, int defaultValue) {
            if (null == varName) {
                return defaultValue;
            }
            String varValue = System.getenv(varName);
            if (null != varValue) {
                try {
                    return Integer.parseInt(varValue);
                } catch (NumberFormatException e) {
                    // will return default value bellow
                }
            }
            return defaultValue;
        }
    }

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = BaseTest.TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port;

        server = new Server(port);
        root = new Context(server, "/", Context.SESSIONS);
        atmoServlet = new AtmosphereServlet();
        configureCometSupport();
        root.addServlet(new ServletHolder(atmoServlet), ROOT);
        server.start();
    }

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new Jetty7CometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.framework().destroy();
        server.stop();
        server = null;
    }



    @Test(timeOut = 60000, enabled = true)
    public void testEventCacheBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        logger.info("{}: running test: testEventCacheBroadcasterCache", getClass().getSimpleName());

        atmoServlet.framework().setBroadcasterCacheClassName(EventCacheBroadcasterCache.class.getName());
        final CountDownLatch suspendLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);

        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            public AtomicInteger count = new AtomicInteger(0);

            public void onRequest(AtmosphereResource event) throws IOException {
                try {
                    if (event.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null) {
                        event.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                            @Override
                            public void onSuspend(AtmosphereResourceEvent event) {
                                suspendLatch.countDown();
                            }
                        }).suspend(-1, false);
                        return;
                    }
                    event.getBroadcaster().broadcast("message-" + count.getAndIncrement()).get();
                } catch (InterruptedException e) {
                    logger.error("", e);
                } catch (ExecutionException e) {
                    logger.error("", e);
                }
                event.getResponse().flushBuffer();
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isResuming() || event.isCancelled()) {
                    return;
                }

                if (List.class.isAssignableFrom(event.getMessage().getClass())) {
                    for (String m : (List<String>) event.getMessage()) {
                        event.getResource().getResponse().getOutputStream().write(m.getBytes());
                    }
                }
                event.getResource().resume();
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            //Suspend
            c.prepareGet(urlTarget).addHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, String.valueOf(0)).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    response.set(r);
                    return r;
                }
            });

            try {
                suspendLatch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            // This will resume the connection
            c.prepareGet(urlTarget).execute().get();

            // Generate Cached messages
            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();

            // Cache will be returned with 2 messages in it.
            c.prepareGet(urlTarget).addHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, response.get().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID))
                    .addHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    try {
                        response.set(r);
                        return r;
                    } finally {
                        latch.countDown();
                    }
                }
            });


            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(response.get());
            assertEquals(response.get().getStatusCode(), 200);
            assertEquals(response.get().getResponseBody(), "message-1message-2");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 60000, enabled = true)
    public void testConcurrentEventCacheBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        logger.info("{}: running test: testEventCacheBroadcasterCache", getClass().getSimpleName());

        atmoServlet.framework().setBroadcasterCacheClassName(EventCacheBroadcasterCache.class.getName());
        final CountDownLatch suspendLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch missedBroadcastCount = new CountDownLatch(100);

        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            public int count;

            public void onRequest(AtmosphereResource event) throws IOException {
                if (event.getRequest().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID) != null) {
                    event.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                        @Override
                        public void onSuspend(AtmosphereResourceEvent event) {
                            suspendLatch.countDown();
                        }
                    }).suspend(-1, false);
                    return;
                }
                event.getBroadcaster().broadcast("message-" + count++);
                missedBroadcastCount.countDown();

                event.getResponse().flushBuffer();
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isResuming() || event.isCancelled()) {
                    return;
                }

                if (List.class.isAssignableFrom(event.getMessage().getClass())) {
                    for (String m : (List<String>) event.getMessage()) {
                        event.getResource().getResponse().getOutputStream().write(m.getBytes());
                    }
                }
                event.getResource().resume();
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            //Suspend
            c.prepareGet(urlTarget).addHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, String.valueOf(0)).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    response.set(r);
                    return r;
                }
            });

            try {
                suspendLatch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            // This will resume the connection
            c.prepareGet(urlTarget).execute().get();

            // Generate Cached messages
            for (int i = 0; i < 100; i++) {
                c.prepareGet(urlTarget).execute();
            }

            missedBroadcastCount.await(10, TimeUnit.SECONDS);

            // Cache will be returned with 2 messages in it.
            c.prepareGet(urlTarget).addHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, response.get().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID))
                    .addHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.LONG_POLLING_TRANSPORT).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    try {
                        response.set(r);
                        return r;
                    } finally {
                        latch.countDown();
                    }
                }
            });


            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(response.get());
            assertEquals(response.get().getStatusCode(), 200);
            assertEquals(response.get().getResponseBody(), "message-1message-2message-3message-4message-5message-6message-7message-8message-9message-10message-11message-12message-13message-14message-15message-16message-17message-18message-19message-20message-21message-22message-23message-24message-25message-26message-27message-28message-29message-30message-31message-32message-33message-34message-35message-36message-37message-38message-39message-40message-41message-42message-43message-44message-45message-46message-47message-48message-49message-50message-51message-52message-53message-54message-55message-56message-57message-58message-59message-60message-61message-62message-63message-64message-65message-66message-67message-68message-69message-70message-71message-72message-73message-74message-75message-76message-77message-78message-79message-80message-81message-82message-83message-84message-85message-86message-87message-88message-89message-90message-91message-92message-93message-94message-95message-96message-97message-98message-99message-100");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }
}
