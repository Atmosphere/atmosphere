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
package org.atmosphere.tests.guice.managed;

import com.google.inject.servlet.GuiceFilter;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.mortbay.jetty.servlet.DefaultServlet;
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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class JettyGuiceJerseyTest {

    private static final Logger logger = LoggerFactory.getLogger(JettyGuiceJerseyTest.class);

    protected static final String ROOT = "/*";
    protected GuiceFilter guiceFilter;
    public String urlTarget;
    public int port;
    public Server server;

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
        urlTarget = "http://127.0.0.1:" + port + "/invoke";
        guiceFilter = new GuiceFilter();

        configureCometSupport();
        startServer();
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        if (guiceFilter != null) guiceFilter.destroy();
        stopServer();
    }

    @Test(timeOut = 20000)
    public void testSuspendTimeout() {
        logger.info("running test: testSuspendTimeout");
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            assertEquals(resume, "resume");
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();

    }

    public void configureCometSupport() {
    }

    public void startServer() throws Exception {
        server = new org.eclipse.jetty.server.Server(8080);

        ServletContextHandler sch = new ServletContextHandler(server, "/");
        sch.addEventListener(new GuiceContextListener());
        sch.addFilter(GuiceFilter.class, "/*", null);
        sch.addServlet(DefaultServlet.class, "/");
        server.start();
    }

    public void stopServer() throws Exception {
        server.stop();
    }

}