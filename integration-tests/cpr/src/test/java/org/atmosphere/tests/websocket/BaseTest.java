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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
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
                event.suspend(5000, false);
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
                    response.set(message);
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
                    event.write(event.getMessage().toString().getBytes());
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

}