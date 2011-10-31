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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class ConcurrentResourceTest extends BaseJettyTest {
    private static final Logger logger = LoggerFactory.getLogger(ConcurrentResourceTest.class);

    private static final int MAX_CLIENT = 100;

    private final static CountDownLatch suspended = new CountDownLatch(MAX_CLIENT);

    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/concurrent";
    }

    @Test(timeOut = 60000, enabled = false)
    public void testConcurrentAndEmptyDestroyPolicy() {
        logger.info("Running testConcurrentAndEmptyDestroyPolicy");

        AsyncHttpClient c = new AsyncHttpClient();
        Broadcaster b = null;
        try {
            final AtomicReference<StringBuffer> r = new AtomicReference<StringBuffer>(new StringBuffer());
            for (int i = 0; i < MAX_CLIENT; i++) {

                c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Response>() {

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        r.get().append(response.getResponseBody());
                        suspended.countDown();
                        logger.info("suspendedCount" + suspended.getCount());
                        return response;
                    }
                });
            }

            suspended.await(60, TimeUnit.SECONDS);

            StringBuffer b2 = new StringBuffer();
            for (int i = 0; i < MAX_CLIENT; i++) {
                b2.append("foo");
            }

            assertEquals(r.get().toString(), b2.toString());
            // Scope == REQUEST
            assertEquals(1, BroadcasterFactory.getDefault().lookupAll().size());
            Iterator<Broadcaster> i = BroadcasterFactory.getDefault().lookupAll().iterator();
            // Since the policy is EMPTY_DESTROY, only one broadcaster (the default one) will be there..
            assertEquals("/*", i.next().getID());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        } finally {
            if (b != null) b.destroy();
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testConcurrentAndIdleDestroyPolicy() {
        logger.info("Running testConcurrentAndIdleDestroyPolicy");

        AsyncHttpClient c = new AsyncHttpClient();
        Broadcaster b = null;
        try {
            final AtomicReference<StringBuffer> r = new AtomicReference<StringBuffer>(new StringBuffer());
            for (int i = 0; i < MAX_CLIENT; i++) {

                c.prepareGet(urlTarget + "/idleDestroyPolicy").execute(new AsyncCompletionHandler<Response>() {

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        r.get().append(response.getResponseBody());
                        suspended.countDown();
                        logger.info("suspendedCount" + suspended.getCount());
                        return response;
                    }
                });
            }

            suspended.await(60, TimeUnit.SECONDS);

            StringBuffer b2 = new StringBuffer();
            for (int i = 0; i < MAX_CLIENT; i++) {
                b2.append("foo");
            }

            //All Broadcaster will be destroyed after 10 second, so let's wait a little.
            Thread.sleep(30000);

            assertEquals(r.get().toString(), b2.toString());
            // Scope == REQUEST
            assertEquals(BroadcasterFactory.getDefault().lookupAll().size(), 1);
            Iterator<Broadcaster> i = BroadcasterFactory.getDefault().lookupAll().iterator();
            // Since the policy is IDLE_DESTROY, only one broadcaster (the default one) will be there..
            assertEquals("/*", i.next().getID());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        } finally {
            if (b != null) b.destroy();
        }
        c.close();
    }

    @Test(timeOut = 60000, enabled = false)
    public void testConcurrentAndIdleResumePolicy() {
        logger.info("Running testConcurrentAndIdleResumePolicy");

        AsyncHttpClient c = new AsyncHttpClient();
        Broadcaster b = null;
        try {
            final AtomicReference<StringBuffer> r = new AtomicReference<StringBuffer>(new StringBuffer());
            for (int i = 0; i < MAX_CLIENT; i++) {

                c.prepareGet(urlTarget + "/idleDestroyResumePolicy").execute(new AsyncCompletionHandler<Response>() {

                    @Override
                    public Response onCompleted(Response response) throws Exception {
                        r.get().append(response.getResponseBody());
                        suspended.countDown();
                        logger.info("suspendedCount" + suspended.getCount());
                        return response;
                    }
                });
            }

            suspended.await(60, TimeUnit.SECONDS);

            StringBuffer b2 = new StringBuffer();
            for (int i = 0; i < MAX_CLIENT; i++) {
                b2.append("foo");
            }

            //All Broadcaster will be destroyed after 10 second, so let's wait a little.
            Thread.sleep(40000);

            assertEquals(r.get().toString(), b2.toString());
            // Scope == REQUEST
            assertEquals(BroadcasterFactory.getDefault().lookupAll().size(), 1);
            Iterator<Broadcaster> i = BroadcasterFactory.getDefault().lookupAll().iterator();
            // Since the policy is IDLE_DESTROY, only one broadcaster (the default one) will be there..
            assertEquals("/*", i.next().getID());

        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        } finally {
            if (b != null) b.destroy();
        }
        c.close();
    }

}
