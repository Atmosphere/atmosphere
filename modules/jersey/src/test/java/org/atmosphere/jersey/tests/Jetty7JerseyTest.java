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
import org.atmosphere.cache.HeaderBroadcasterCache;
import org.atmosphere.container.Jetty7CometSupport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

public class Jetty7JerseyTest extends BaseTest {
    protected Server server;

    @Override
    public void startServer() throws Exception {
        server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        context.addServlet(new ServletHolder(atmoServlet), "/*");
        server.start();
    }

    @Override
    public void configureCometSupport() {
        atmoServlet.setCometSupport(new Jetty7CometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @Override
    public void stopServer() throws Exception {
        server.stop();
    }


    @Test(timeOut = 60000)
    public void testHeaderBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        System.out.println("Running testHeaderBroadcasterCache");
        atmoServlet.setBroadcasterCacheClassName(HeaderBroadcasterCache.class.getName());
        final CountDownLatch latch = new CountDownLatch(1);
        long t1 = System.currentTimeMillis();
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            // Suspend
            c.preparePost(urlTarget).addParameter("message", "cacheme").execute().get();

            // Broadcast
            c.preparePost(urlTarget).addParameter("message", "cachememe").execute().get();

            //Suspend
            Response r = c.prepareGet(urlTarget + "/subscribeAndResume").addHeader("X-Cache-Date", String.valueOf(t1)).execute(new AsyncCompletionHandler<Response>() {

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
            assertEquals(r.getResponseBody(), "cacheme\ncachememe\n");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }

        c.close();
    }
}