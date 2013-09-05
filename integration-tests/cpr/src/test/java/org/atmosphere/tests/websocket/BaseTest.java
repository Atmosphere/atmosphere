/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.tests.websocket;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.tests.http.AbstractHttpAtmosphereHandler;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class BaseTest {

    protected static final Logger logger = LoggerFactory.getLogger(BaseTest.class);

    protected AtmosphereServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;

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
    abstract public void startServer() throws Exception;

    abstract public void configureCometSupport();

    @AfterMethod(alwaysRun = true)
    abstract public void unsetAtmosphereHandler() throws Exception;

    @Test(timeOut = 60000, enabled = true)
    public void testSuspendTimeout() {
        logger.info("{}: running test: testSuspendTimeout", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            public void onRequest(AtmosphereResource event) throws IOException {
                event.suspend(5000);
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                event.getResource().getResponse().getOutputStream().write("resume".getBytes());
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<String> response = new AtomicReference<String>(null);
            c.prepareGet(urlTarget).execute(new WebSocketUpgradeHandler.Builder().build())
                    .get().addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    // There is a regression in Jetty or AHC as we are getting some junk .
                    if (response.get() == null) {
                        response.set(message);
                    }
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                    fail();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            });

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            assertNotNull(response.get());
            assertEquals(response.get(), "resume");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = true)
    public void sessionTest() {
        logger.info("{}: running test: sessionTest", getClass().getSimpleName());

        final AtomicReference<HttpSession> session = new AtomicReference<HttpSession>();
        final AtomicReference<HttpSession> sessionOnPost = new AtomicReference<HttpSession>();

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            boolean isSuspended = false;

            public void onRequest(AtmosphereResource r) throws IOException {
                if (!isSuspended) {
                    session.set(r.suspend().session(true));
                    isSuspended = true;
                } else {
                    String message = r.getRequest().getReader().readLine();
                    r.getBroadcaster().broadcast(message);
                }
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isSuspended()) {
                    sessionOnPost.set(event.getResource().session());
                    event.getResource().write(event.getMessage().toString().getBytes());
                }
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<String> response = new AtomicReference<String>(null);
            WebSocket w = c.prepareGet(urlTarget).execute(new WebSocketUpgradeHandler.Builder().build())
                    .get();
            w.addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    response.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                    fail();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            }).sendTextMessage("echo");

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(response.get());
            assertEquals(response.get(), "echo");
            assertEquals(session.get().getId(), sessionOnPost.get().getId());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = true)
    public void echoTest() {
        logger.info("{}: running test: echoTest", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            boolean isSuspended = false;

            public void onRequest(AtmosphereResource r) throws IOException {
                if (!isSuspended) {
                    r.suspend();
                    isSuspended = true;
                } else {
                    String message = r.getRequest().getReader().readLine();
                    r.getBroadcaster().broadcast(message);
                }
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isSuspended()) {
                    event.getResource().write(event.getMessage().toString().getBytes());
                }
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<String> response = new AtomicReference<String>(null);
            WebSocket w = c.prepareGet(urlTarget).execute(new WebSocketUpgradeHandler.Builder().build())
                    .get();
            w.addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    response.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                    fail();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            }).sendTextMessage("echo");

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            assertNotNull(response.get());
            assertEquals(response.get(), "echo");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    /**
     * Output message when Atmosphere suspend a connection.
     *
     * @return message when Atmosphere suspend a connection.
     */
    public static String createStreamingPadding(String padding) {
        StringBuilder s = new StringBuilder();

        for (int i = 0; i < 4096; i++) {
            s.append(" ");
        }
        return s.toString();
    }

    @Test(timeOut = 60000, enabled = true)
    public void onCloseOnDisconnectTest() {
        logger.info("{}: running test: onDisconnectTest", getClass().getSimpleName());

        final AtomicBoolean onDisconnect = new AtomicBoolean();
        final AtomicBoolean onClose = new AtomicBoolean();
        final CountDownLatch eventReceived = new CountDownLatch(2);

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            boolean isSuspended = false;

            public void onRequest(AtmosphereResource r) throws IOException {
                r.addEventListener(new WebSocketEventListenerAdapter() {

                    @Override
                    public void onDisconnect(WebSocketEvent event) {
                        onDisconnect.set(true);
                        eventReceived.countDown();
                    }


                    @Override
                    public void onClose(WebSocketEvent event) {
                        onClose.set(true);
                        eventReceived.countDown();
                    }
                });

                if (!isSuspended) {
                    r.suspend();
                    isSuspended = true;
                } else {
                    String message = r.getRequest().getReader().readLine();
                    r.getBroadcaster().broadcast(message);
                }
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isSuspended()) {
                    event.getResource().write(event.getMessage().toString().getBytes());
                }
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<String> response = new AtomicReference<String>(null);
            WebSocket w = c.prepareGet(urlTarget).execute(new WebSocketUpgradeHandler.Builder().build())
                    .get();
            w.addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    response.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                    fail();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            }).sendTextMessage("echo");

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            c.close();

            eventReceived.await(10, TimeUnit.SECONDS);
            assertNotNull(response.get());
            assertEquals(response.get(), "echo");
            assertTrue(onClose.get());
            assertTrue(onDisconnect.get());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
    }

    @Test(timeOut = 60000, enabled = true)
    public void onResumeWithTimerTest() {
        logger.info("{}: running test: onResumeWithTimerTest", getClass().getSimpleName());

        final AtomicBoolean onResume = new AtomicBoolean();
        final CountDownLatch eventReceived = new CountDownLatch(1);
        final CountDownLatch suspended = new CountDownLatch(1);
        final AtomicReference<AtmosphereResource> resource = new AtomicReference<AtmosphereResource>();

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.framework().addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            public void onRequest(final AtmosphereResource r) throws IOException {
                r.addEventListener(new WebSocketEventListenerAdapter() {

                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {
                        logger.trace("{}", event);
                        suspended.countDown();
                    }

                    @Override
                    public void onResume(AtmosphereResourceEvent event) {
                        onResume.set(true);
                        eventReceived.countDown();
                    }
                });

                if (suspended.getCount() != 0) {
                    resource.set(r.suspend());
                } else {
                    try {
                        suspended.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    String message = r.getRequest().getReader().readLine();
                    r.getBroadcaster().broadcast(message);
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            resource.get().resume();
                        }
                    }, 2000);
                }
            }

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                if (event.isSuspended()) {
                    event.getResource().write(event.getMessage().toString().getBytes());
                }
            }
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<String> response = new AtomicReference<String>(null);
            WebSocket w = c.prepareGet(urlTarget).execute(new WebSocketUpgradeHandler.Builder().build())
                    .get();
            w.addWebSocketListener(new WebSocketTextListener() {

                @Override
                public void onMessage(String message) {
                    response.set(message);
                    latch.countDown();
                }

                @Override
                public void onFragment(String fragment, boolean last) {
                    fail();
                }

                @Override
                public void onOpen(WebSocket websocket) {
                }

                @Override
                public void onClose(WebSocket websocket) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            }).sendTextMessage("echo");

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            eventReceived.await(10, TimeUnit.SECONDS);
            assertNotNull(response.get());
            assertEquals(response.get(), "echo");
            assertTrue(onResume.get());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }
}