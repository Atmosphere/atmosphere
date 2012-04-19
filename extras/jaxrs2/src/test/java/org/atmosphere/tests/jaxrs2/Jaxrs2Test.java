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
package org.atmosphere.tests.jaxrs2;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public class Jaxrs2Test {

    protected static final Logger logger = LoggerFactory.getLogger(Jaxrs2Test.class);

    protected static final String ROOT = "/*";

    protected AtmosphereServlet atmoServlet;
    public String urlTarget;
    public int port;
    protected Server server;
    private Context root;

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new JettyCometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    public void startServer() throws Exception {
        server = new Server(port);
        root = new Context(server, "/", Context.SESSIONS);
        root.addServlet(new ServletHolder(atmoServlet), ROOT);

        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port);
        server.addConnector(listener);

        server.start();
    }

    public void stopServer() throws Exception {
        server.stop();
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
    public void setUpGlobal() throws Exception {
        port = findFreePort();
        urlTarget = getUrlTarget(port);
        atmoServlet = new AtmosphereServlet();
        atmoServlet.framework().addInitParameter("com.sun.jersey.config.property.packages", this.getClass().getPackage().getName());

        configureCometSupport();
        startServer();
    }

    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/test";
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        if (atmoServlet != null) atmoServlet.destroy();
        stopServer();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testSuspend() {
        logger.info("{}: running test:  testSuspend", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "Atmosphere!");
            long current = System.currentTimeMillis() - t1;
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 60000, enabled = true)
    public void testProgrammaticSuspend() {
        logger.info("{}: running test:  testProgrammaticSuspend", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget + "/p").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "Atmosphere!");
            long current = System.currentTimeMillis() - t1;
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 60000, enabled = true)
    public void testWriteOnResume() {
        logger.info("{}: running test:  testWriteOnResume", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget + "/p2").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "Atmosphere!");
            long current = System.currentTimeMillis() - t1;
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }
}
