/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://jersey.dev.java.net/CDDL+GPL.html
 * or jersey/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at jersey/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.tests;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.cache.HeaderBroadcasterCache;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.util.StringFilterAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class BaseTest {

    protected static final Logger logger = LoggerFactory.getLogger(BaseTest.class);

    protected AtmosphereServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;

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
    abstract public void startServer() throws Exception;

    abstract public void configureCometSupport();

    @AfterMethod(alwaysRun = true)
    abstract public void unsetAtmosphereHandler() throws Exception;

    @Test(timeOut = 60000, enabled = false)
    public void testSuspendTimeout() {
        logger.info("{}: running test: testSuspendTimeout", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                currentTime = System.currentTimeMillis();
                event.suspend(5000, false);
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {

                try {
                    event.getResource().getResponse().getOutputStream().write("resume".getBytes());
                    assertTrue(event.isResumedOnTimeout());
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 5000 && time < 15000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "resume");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testSuspendWithCommentsTimeout() {
        logger.info("{}: running test: testSuspendWithCommentsTimeout", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                currentTime = System.currentTimeMillis();
                event.suspend(5000);
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                try {
                    assertTrue(event.isResumedOnTimeout());
                    long time = System.currentTimeMillis() - currentTime;

                    if (time > 5000 && time < 15000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, AtmosphereResourceImpl.createCompatibleStringJunk());
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testProgrammaticDisconnection() {
        logger.info("{}: running test: testProgrammaticDisconnection", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                currentTime = System.currentTimeMillis();
                event.suspend();
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                try {
                    assertTrue(event.isCancelled());
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 20000 && time < 25000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget).execute().get();

            if (latch.getCount() != 0) {
                fail("timedout");
            }
            assertNotNull(r);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testProgrammaticResume() {
        logger.info("{}: running test: testProgrammaticResume", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            AtmosphereResource<HttpServletRequest, HttpServletResponse> suspendedEvent;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        suspendedEvent = event;
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    suspendedEvent.getResponse().flushBuffer();
                    suspendedEvent.resume();
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {

                try {
                    assertTrue(event.isResuming());
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testResumeOnBroadcast() {
        logger.info("{}: running test: testResumeOnBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    event.getBroadcaster().broadcast("foo");
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "foo");
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testDelayBroadcast() {
        logger.info("{}: running test: testDelayBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    currentTime = System.currentTimeMillis();
                    event.getBroadcaster().delayBroadcast("foo", 5, TimeUnit.SECONDS);
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 5000 && time < 6000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "foo");
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testDelayNextBroadcast() {
        logger.info("{}: running test: testDelayNextBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            AtomicInteger count = new AtomicInteger(0);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    event.suspend(-1, false);

                } else {
                    currentTime = System.currentTimeMillis();

                    if (count.get() < 4) {
                        event.getBroadcaster().delayBroadcast("message-" + count.getAndIncrement() + " ");
                    } else {
                        event.getBroadcaster().broadcast("message-final");
                    }
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    event.getResource().getResponse().getWriter().write((String) event.getMessage());
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } catch (Exception ex) {
                    logger.error("failure resuming resource", ex);
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Future<Response> f = c.prepareGet(urlTarget).execute();

            latch.await(5, TimeUnit.SECONDS);

            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();

            c.prepareGet(urlTarget).execute().get();

            Response r = f.get(10, TimeUnit.SECONDS);

            assertNotNull(r);
            assertEquals(r.getResponseBody(), "message-0 message-1 message-2 message-3 message-final");
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testScheduleBroadcast() {
        logger.info("{}: running test: testScheduleBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    currentTime = System.currentTimeMillis();
                    event.getBroadcaster().scheduleFixedBroadcast("foo", 0, 5, TimeUnit.SECONDS);
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 5000 && time < 6000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "foo");
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });
            suspended.await(20, TimeUnit.SECONDS);

            Response r = c.prepareGet(urlTarget).execute().get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testDelayScheduleBroadcast() {
        logger.info("{}: running test: testDelayScheduleBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    currentTime = System.currentTimeMillis();
                    event.getBroadcaster().scheduleFixedBroadcast("foo", 10, 5, TimeUnit.SECONDS);
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 15000 && time < 20000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "foo");
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testBroadcastFilter() {
        logger.info("{}: running test: testBroadcastFilter", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    event.getBroadcaster().getBroadcasterConfig().addFilter(new BroadcastFilter() {

                        public BroadcastAction filter(Object o, Object message) {
                            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, "boo" + message);
                        }
                    });

                    event.getBroadcaster().broadcast("foo");
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "boofoo");
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testAggregateFilter() {
        logger.info("{}: running test: testAggregateFilter", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    // Will take 3 broadcast before it get pushed back.
                    StringFilterAggregator a = new StringFilterAggregator(25);
                    event.getBroadcaster().getBroadcasterConfig().addFilter(a);
                    try {
                        event.suspend();
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    event.getBroadcaster().broadcast("12345678910");
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                assertFalse(event.isCancelled());
                assertNotNull(event.getMessage());
                assertEquals(event.getMessage(), "123456789101234567891012345678910");
                event.getResource().getResponse().flushBuffer();
                event.getResource().resume();
                latch.countDown();
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });

            suspended.await(20, TimeUnit.SECONDS);
            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testHeaderBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        logger.info("{}: running test: testHeaderBroadcasterCache", getClass().getSimpleName());

        atmoServlet.setBroadcasterCacheClassName(HeaderBroadcasterCache.class.getName());
        final CountDownLatch latch = new CountDownLatch(1);

        long t1 = System.currentTimeMillis();
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                try {
                    if (event.getRequest().getHeader(HeaderBroadcasterCache.HEADER_CACHE) != null) {
                        event.suspend(-1, false);
                        return;
                    }
                    event.getBroadcaster().broadcast("12345678910").get();
                } catch (InterruptedException e) {
                    logger.error("", e);
                } catch (ExecutionException e) {
                    logger.error("", e);
                }
                event.getResponse().flushBuffer();
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                try {
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    if (List.class.isAssignableFrom(event.getMessage().getClass())) {
                        for (String m : (List<String>) event.getMessage()) {
                            event.getResource().getResponse().getOutputStream().write(m.getBytes());
                        }
                    }
                    event.getResource().getResponse().flushBuffer();
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute().get();
            c.prepareGet(urlTarget).execute().get();

            //Suspend
            Response r = c.prepareGet(urlTarget).addHeader(HeaderBroadcasterCache.HEADER_CACHE, String.valueOf(t1)).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    try {
                        return r;
                    } finally {
                        latch.countDown();
                    }
                }
            }).get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBody(), "1234567891012345678910");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testSuspendRejectPolicy() {
        logger.info("{}: running test: testSuspendTimeout", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                currentTime = System.currentTimeMillis();
                event.getBroadcaster().setSuspendPolicy(1, Broadcaster.POLICY.REJECT);
                event.suspend(5000, false);
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {

                try {
                    event.getResource().getResponse().getOutputStream().write("resume".getBytes());
                    assertTrue(event.isResumedOnTimeout());
                    long time = System.currentTimeMillis() - currentTime;
                    if (time > 5000 && time < 15000) {
                        assertTrue(true);
                    } else {
                        assertFalse(false);
                    }
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget).execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "resume");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testBroadcastOnResume() {
        logger.info("{}: running test: testScheduleBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                        event.getBroadcaster().broadcastOnResume("broadcastOnResume");
                    } finally {
                        suspended.countDown();
                    }
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    assertFalse(event.isCancelled());
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "broadcastOnResume");
                    event.getResource().getResponse().flushBuffer();
                    return;
                }
                try {
                    event.getResource().resume();
                } finally {
                    latch.countDown();
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });
            suspended.await(20, TimeUnit.SECONDS);

            Response r = c.prepareGet(urlTarget).execute().get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testBroadcastOnResumeMsg() {
        logger.info("{}: running test: testBroadcastOnResumeMsg", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        final CountDownLatch suspended = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend();
                        event.getBroadcaster().broadcastOnResume("broadcastOnResume");
                    } finally {
                        suspended.countDown();
                    }
                } else {
                    event.resume();
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    assertNotNull(event.getMessage());
                    assertEquals(event.getMessage(), "broadcastOnResume");
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<String>() {

                @Override
                public String onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getResponseBody(),
                                AtmosphereResourceImpl.createCompatibleStringJunk());
                    } finally {
                        latch.countDown();
                    }
                    return null;
                }
            });
            suspended.await(10, TimeUnit.SECONDS);

            Response r = c.prepareGet(urlTarget).execute().get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000, enabled = false)
    public void testBroadcastFactoryNewBroadcasterTimeout() {
        logger.info("{}: running test: testBroadcastFactoryTimeout", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);
        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            private long currentTime;

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                currentTime = System.currentTimeMillis();
                event.suspend(5000, false);
                try {
                    Broadcaster b = BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, "ExternalBroadcaster", true);
                    b.addAtmosphereResource(event);
                    b.broadcast("Outer broadcast").get();


                    event.getBroadcaster().broadcast("Inner broadcast").get();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {

                if (event.isResuming()) {
                    return;
                }

                try {
                    event.getResource().getResponse().getWriter().write(event.getMessage().toString());
                } finally {
                    latch.countDown();
                    if (latch.getCount() == 0) {
                        event.getResource().resume();
                    }
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Future<Response> f = c.prepareGet(urlTarget).execute();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            Response r = f.get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "Outer broadcastInner broadcast");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testConcurrentBroadcast() {
        logger.info("{}: running test: testConcurrentBroadcast", getClass().getSimpleName());

        final AtomicInteger broadcastCount = new AtomicInteger(0);
        final AtomicReference<Response> response = new AtomicReference<Response>();
        final CountDownLatch latch = new CountDownLatch(1);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);

            public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (!b.getAndSet(true)) {
                    try {
                        event.suspend(-1, false);
                    } finally {
                    }
                } else {
                    event.getBroadcaster().broadcast("Message-1 ");
                    event.getBroadcaster().broadcast("Message-2 ");
                    event.getBroadcaster().broadcast("Message-3 ");
                    event.getBroadcaster().broadcast("Message-4");
                }
            }

            public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
                if (event.isResuming()) {
                    return;
                }
                PrintWriter writer = event.getResource().getResponse().getWriter();
                writer.write(event.getMessage().toString());
                writer.flush();
                try {
                    broadcastCount.incrementAndGet();
                } finally {
                    if (broadcastCount.get() == 4) {
                        event.getResource().resume();
                    }
                }
            }
        }, new RecyclableBroadcaster("suspend"));

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Object>() {
                @Override
                public Object onCompleted(Response r) throws Exception {
                    response.set(r);
                    latch.countDown();
                    return null;
                }
            });

            c.prepareGet(urlTarget).execute();
            latch.await(10, TimeUnit.SECONDS);

            assertNotNull(response.get());
            assertEquals(response.get().getStatusCode(), 200);
            assertEquals(response.get().getResponseBody(), "Message-1 Message-2 Message-3 Message-4");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        } finally {
            c.close();
        }
    }

}