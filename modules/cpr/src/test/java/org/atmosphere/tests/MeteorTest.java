
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
package org.atmosphere.tests;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.cpr.MeteorServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class MeteorTest {

    private static final Logger logger = LoggerFactory.getLogger(MeteorTest.class);

    protected MeteorServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;
    protected Server server;
    protected Context root;
    private static CountDownLatch servletLatch;

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

    public static class Meteor1 extends HttpServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            final Meteor m = Meteor.build(req);
            req.getSession().setAttribute("meteor", m);
            m.suspend(5000, false);

            m.broadcast("resume");
            m.addListener(new AtmosphereResourceEventListener() {

                @Override
                public void onSuspend(final AtmosphereResourceEvent event) {
                }

                @Override
                public void onResume(AtmosphereResourceEvent event) {
                }

                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                }

                @Override
                public void onBroadcast(AtmosphereResourceEvent event) {
                    event.getResource().getRequest().setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, "true");
                }

                @Override
                public void onThrowable(AtmosphereResourceEvent event) {

                }
            });

            if (servletLatch != null) {
                servletLatch.countDown();
            }
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = org.atmosphere.tests.BaseTest.TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";

        server = new Server(port);
        root = new Context(server, "/", Context.SESSIONS);
        atmoServlet = new MeteorServlet();
        atmoServlet.framework().addInitParameter("org.atmosphere.servlet", Meteor1.class.getName());
        configureCometSupport();
        root.addServlet(new ServletHolder(atmoServlet), ROOT);
        server.start();
    }

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new JettyCometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.framework().destroy();
        server.stop();
        server = null;
    }

    @Test(timeOut = 20000, enabled = true)
    public void testSuspendTimeout() {
        logger.info("running test: testSuspendTimeout");

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long currentTime = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget).execute().get();

            long time = System.currentTimeMillis() - currentTime;
            if (time > 5000 && time < 15000) {
                assertTrue(true);
            } else {
                assertFalse(false);
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

    @Test(timeOut = 60000, enabled = true)
    public void testResumeOnBroadcast() {
        logger.info("running test: testResumeOnBroadcast");

        final CountDownLatch latch = new CountDownLatch(1);
        servletLatch = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long currentTime = System.currentTimeMillis();
            final AtomicReference<Response> r = new AtomicReference();
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    r.set(response);
                    latch.countDown();
                    return response;
                }
            });

            servletLatch.await();

            Broadcaster b = BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, "/*");
            assertNotNull(b);
            b.broadcast("resume").get();

            try {
                latch.await();
            } catch (InterruptedException e) {
                fail(e.getMessage());
                return;
            }

            long time = System.currentTimeMillis() - currentTime;
            if (time < 5000) {
                assertTrue(true);
            } else {
                assertFalse(false);
            }
            assertNotNull(r.get());
            assertEquals(r.get().getStatusCode(), 200);
            String resume = r.get().getResponseBody();
            assertEquals(resume, "resumeresume");
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }

}
