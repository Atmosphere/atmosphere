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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;


public class BlockingIOCometSupportTest extends BaseTest {
    protected Server server;
    protected Context root;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";

        server = new Server();
        root = new Context(server, "/", Context.SESSIONS);
        atmoServlet = new AtmosphereServlet();
        configureCometSupport();
        setConnector(port);
        root.addServlet(new ServletHolder(atmoServlet), ROOT);
        server.start();
    }

    public void setConnector(int port) throws Exception {
        Connector listener = new SocketConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port);
        server.addConnector(listener);
    }

    public void configureCometSupport() {
        atmoServlet.setCometSupport(new BlockingIOCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.destroy();
        server.stop();
        server = null;
    }

    @Test(timeOut = 60000, enabled = true)
    public void testDelayNextBroadcast() {
        logger.info("{}: running test: testDelayNextBroadcast", getClass().getSimpleName());

        final CountDownLatch latch = new CountDownLatch(2);

        atmoServlet.addAtmosphereHandler(ROOT, new AbstractHttpAtmosphereHandler() {

            AtomicBoolean b = new AtomicBoolean(false);
            AtomicInteger count = new AtomicInteger(0);
            private long currentTime;

            public void onRequest(AtmosphereResource event) throws IOException {
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

            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
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
        }, BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "suspend"));

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

}
