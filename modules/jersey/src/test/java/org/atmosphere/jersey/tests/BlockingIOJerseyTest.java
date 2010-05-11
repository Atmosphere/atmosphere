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
package org.atmosphere.jersey.tests;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;


public class BlockingIOJerseyTest extends BaseTest {

    protected Server server;
    protected Context root;
    protected final static String ROOT = "/*";

    @Override
    public void configureCometSupport() {
        atmoServlet.setCometSupport(new BlockingIOCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @Override
    public void startServer() throws Exception {
        server = new Server(port);
        root = new Context(server, "/", Context.SESSIONS);
        root.addServlet(new ServletHolder(atmoServlet), ROOT);
        server.start();
    }

    @Override
    public void stopServer() throws Exception {
        server.stop();
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.destroy();
        BasicConfigurator.resetConfiguration();
        server.stop();
        server = null;
    }
    @Test(timeOut = 60000)
    public void testDelayNextBroadcast() {
        System.out.println("Running testDelayNextBroadcast");
        final CountDownLatch latch = new CountDownLatch(1);
        long t1 = System.currentTimeMillis();

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            c.prepareGet(urlTarget + "/forever").execute(new AsyncCompletionHandler<Response>() {

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

            // Let Atmosphere suspend the connections.
            Thread.sleep(2500);
            c.preparePost(urlTarget + "/delay").addParameter("message", "foo").execute().get();
            c.preparePost(urlTarget + "/delayAndResume").addParameter("message", "bar").execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();

            assertNotNull(r);
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createCompatibleStringJunk() + "foo\nbar\n");
            assertEquals(r.getStatusCode(), 200);
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }


        c.close();

    }
}
