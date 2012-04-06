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
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class ConcurrentBroadcastTest {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrentBroadcastTest.class);

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
        atmoServlet.framework().addAtmosphereHandler("/suspend", new SuspendAndResume());
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

    @Test(timeOut = 60000, enabled = true)
    public void testConcurrentSuspendAndBroadcast() {
        logger.info("Running testConcurrentSuspendAndBroadcast");

        AsyncHttpClient c = new AsyncHttpClient();
        Broadcaster b = null;
        try {
            final AtomicReference<StringBuffer> r = new AtomicReference<StringBuffer>(new StringBuffer());
            for (int i = 0; i < MAX_CLIENT; i++) {

                c.prepareGet(urlTarget + "/suspend").execute(new AsyncCompletionHandler<Response>() {

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        r.get().append(response.getResponseBody());
                        suspended.countDown();
                        logger.info("suspendedCount" + suspended.getCount());
                        return response;
                    }
                });
            }

            broadcasterReady.await(10, TimeUnit.SECONDS);

            BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, "/suspend").broadcast("foo").get();

            suspended.await(60, TimeUnit.SECONDS);

            StringBuffer b2 = new StringBuffer();
            for (int i=0; i < MAX_CLIENT; i++) {
                b2.append("foo");
            }

            assertEquals(r.get().toString(), b2.toString());

        } catch (Exception e) {
            logger.error("test failed", e);
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (b != null) b.destroy();
        }
        c.close();
    }

    private static final class SuspendAndResume implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    broadcasterReady.countDown();
                }
            });
            r.suspend(-1, false);
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent r) throws IOException {
            if (r.isSuspended() && !r.isResuming()) {
                logger.info("Resumed");
                r.getResource().getResponse().getWriter().print(r.getMessage());
                r.getResource().getResponse().getWriter().flush();
                r.getResource().resume();
            }
        }

        @Override
        public void destroy() {
        }
    }

}
