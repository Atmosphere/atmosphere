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
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;


public class Jetty6CometSupportTest extends BlockingIOCometSupportTest {

    public void setConnector(int port) throws Exception {
        Connector listener = new SelectChannelConnector();

        listener.setHost("127.0.0.1");
        listener.setPort(port);
        server.addConnector(listener);
    }

    public void configureCometSupport() {
        atmoServlet.setCometSupport(new JettyCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @Test(timeOut = 60000)
    public void testSuspendTimeout() {
        System.out.println("Running testSuspendTimeout");
        final CountDownLatch latch = new CountDownLatch(1);
        atmoServlet.addAtmosphereHandler(ROOT, new AtmosphereHandler<HttpServletRequest, HttpServletResponse>() {

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
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();
    }
}