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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class BroadcasterLifecycleTest {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterLifecycleTest.class);

    protected AtmosphereServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;
    protected Server server;
    protected Context root;
    private final static AtomicReference<AtmosphereResource> ref = new AtomicReference<AtmosphereResource>();
    private final static CountDownLatch suspended = new CountDownLatch(2);
    private final static CountDownLatch broadcasterCreated = new CountDownLatch(1);


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

        atmoServlet.framework().addAtmosphereHandler("/suspend", new LifeCycleTestHandler());
        atmoServlet.framework().addAtmosphereHandler("/destroy", new Destroy());
        atmoServlet.framework().addAtmosphereHandler("/invoke", new Invoker());

    }

    public void configureCometSupport() {
        atmoServlet.framework().setCometSupport(new JettyCometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.framework().destroy();
        server.stop();
        server = null;
    }

    @Test(timeOut = 20000, enabled = true)
    public void testBroadcasterLifecylePolicy() {
        logger.info("Running testBroadcasterLifecylePolicy");

        AsyncHttpClient c = new AsyncHttpClient();
        Broadcaster b = null;
        try {
            final AtomicReference<Response> r = new AtomicReference();
            c.prepareGet(urlTarget + "/suspend").execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    r.set(response);
                    suspended.countDown();
                    return response;
                }
            });

            // Destroy the Broadcaster
            Response response = c.prepareGet(urlTarget + "/destroy").execute().get();
            assertEquals(response.getStatusCode(), 200);

            response = c.prepareGet(urlTarget + "/invoke").execute().get();
            assertEquals(response.getStatusCode(), 200);

            suspended.await(20, TimeUnit.SECONDS);

            assertEquals(r.get().getResponseBody(), "Recovering a dead broadcasted");

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        } finally {
            if (b != null) b.destroy();
        }
        c.close();
    }

    private static final class LifeCycleTestHandler implements AtmosphereHandler {

        private Broadcaster broadcaster;

        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            broadcaster = BroadcasterFactory.getDefault().get("Test-Destroy");
            r.setBroadcaster(broadcaster);
            ref.set(r);
            r.suspend(-1, false);
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent r) throws IOException {
            if (r.isSuspended()) {
                r.getResource().getResponse().getWriter().print(r.getMessage());
                r.getResource().getResponse().getWriter().flush();
                r.getResource().resume();
                suspended.countDown();
            }
        }

        @Override
        public void destroy() {
        }
    }

    private static final class Destroy implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            try {
                broadcasterCreated.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }

            BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, "Test-Destroy").destroy();
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent r) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }

    private static final class Invoker implements AtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            try {
                ref.get().getBroadcaster().broadcast("Recovering a dead broadcasted").get();
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
            ref.get().resume();
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent r) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }
}
