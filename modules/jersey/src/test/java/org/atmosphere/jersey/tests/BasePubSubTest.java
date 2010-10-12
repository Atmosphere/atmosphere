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
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import org.atmosphere.cache.HeaderBroadcasterCache;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class BasePubSubTest extends BaseTest {

    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/invoke";
    }

    @Test(timeOut = 20000)
    public void testSuspendTimeout() {
        System.out.println("Running testSuspendTimeout");
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
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000)
    public void testSuspendWithCommentsTimeout() {
        System.out.println("Running testSuspendWithCommentsTimeout");

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget + "/withComments").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            String[] ct = r.getContentType().toLowerCase().split(";");
            assertEquals(ct[0].trim(), "text/plain");
            assertEquals(ct[1].trim(), "charset=iso-8859-1");
            assertEquals(resume, AtmosphereResourceImpl.createCompatibleStringJunk());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(enabled = false)
    public void testProgrammaticDisconnection() {
        System.out.println("Running testProgrammaticDisconnection");
        AsyncHttpClient c = new AsyncHttpClient();
        long t1 = System.currentTimeMillis();

        try {
            Response r = c.prepareGet(urlTarget + "/forever").execute().get(30, TimeUnit.SECONDS);
            assertNotNull(r);
        } catch (Exception e) {
        }
        long current = System.currentTimeMillis() - t1;
        assertTrue(current > 20000 && current < 25000);
        c.close();

    }

    @Test(timeOut = 60000)
    public void testProgrammaticResume() {
        System.out.println("Running testProgrammaticResume");
        AsyncHttpClient c = new AsyncHttpClient();
        final AtomicReference<String> location = new AtomicReference<String>();
        final AtomicReference<String> response = new AtomicReference<String>("");
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch locationLatch = new CountDownLatch(1);
        try {
            c.prepareGet(urlTarget + "/suspendAndResume").execute(new AsyncHandler<String>() {

                public void onThrowable(Throwable throwable) {
                    fail("onThrowable", throwable);
                }

                public STATE onBodyPartReceived(HttpResponseBodyPart bp) throws Exception {

                    System.out.println("bp: " + new String(bp.getBodyPartBytes()));
                    response.set(response.get() + new String(bp.getBodyPartBytes()));
                    locationLatch.countDown();
                    return STATE.CONTINUE;
                }

                public STATE onStatusReceived(HttpResponseStatus hs) throws Exception {
                    return STATE.CONTINUE;
                }

                public STATE onHeadersReceived(HttpResponseHeaders rh) throws Exception {
                    location.set(rh.getHeaders().getFirstValue("Location"));
                    return STATE.CONTINUE;
                }

                public String onCompleted() throws Exception {
                    latch.countDown();
                    return "";
                }
            });

            locationLatch.await(5, TimeUnit.SECONDS);

            Response r = c.prepareGet(location.get()).execute().get(10, TimeUnit.SECONDS);
            latch.await(20, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(response.get(), "suspendresume");

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test
    public void testResumeOnBroadcastUsingBroadcasterFactory() {
        System.out.println("Running testResumeOnBroadcast");
        AsyncHttpClient c = new AsyncHttpClient();
        long t1 = System.currentTimeMillis();

        try {
            Response r = c.prepareGet(urlTarget + "/subscribeAndUsingExternalThread").execute().get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000)
    public void testDelayBroadcast() {
        System.out.println("Running testDelayBroadcast");
        final CountDownLatch latch = new CountDownLatch(1);
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
            c.preparePost(urlTarget + "/publishAndResume").addParameter("message", "bar").execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();

            assertNotNull(r);
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createCompatibleStringJunk() + "foo\nbar\n");
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }


        c.close();

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

    @Test(timeOut = 60000)
    public void testScheduleBroadcast() {
        System.out.println("Running testScheduleBroadcast");
        final CountDownLatch latch = new CountDownLatch(1);
        long t1 = System.currentTimeMillis();
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            c.prepareGet(urlTarget + "/foreverWithoutComments").execute(new AsyncCompletionHandler<Response>() {

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
            c.preparePost(urlTarget + "/scheduleAndResume").addParameter("message", "foo").execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBody(), "foo\n");
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(timeOut = 60000)
    public void testBroadcastFilter() {
        System.out.println("Running testBroadcastFilter");
        final CountDownLatch latch = new CountDownLatch(1);
        long t1 = System.currentTimeMillis();
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            c.prepareGet(urlTarget + "/foreverWithoutComments").execute(new AsyncCompletionHandler<Response>() {

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
            c.preparePost(urlTarget + "/filter").addParameter("message", "<script>foo</script>").execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBody(), "&lt;script&gt;foo&lt;/script&gt;<br />");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

    @Test(enabled = false)
    public void testAggregateFilter() {
        System.out.println("Running testAggregateFilter");
        final CountDownLatch latch = new CountDownLatch(1);
        long t1 = System.currentTimeMillis();
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            c.prepareGet(urlTarget + "/foreverWithoutComments").execute(new AsyncCompletionHandler<Response>() {

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
            for (int i = 0; i < 10; i++) {
                c.preparePost(urlTarget + "/aggregate").addParameter("message",
                        "==================================================").execute().get(5, TimeUnit.SECONDS);
            }


            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBody(), "==================================================\n" +
                    "==================================================\n" +
                    "==================================================\n" +
                    "==================================================\n" +
                    "==================================================\n" +
                    "==================================================\n");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

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

    @Test(timeOut = 60000)
    public void testProgrammaticDelayBroadcast() {
        System.out.println("Running testDelayBroadcast");
        final CountDownLatch latch = new CountDownLatch(1);
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
            c.preparePost(urlTarget + "/programmaticDelayBroadcast").addParameter("message", "foo").execute().get();
            c.preparePost(urlTarget + "/publishAndResume").addParameter("message", "bar").execute().get();

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();

            assertNotNull(r);
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createCompatibleStringJunk() + "foobar\n");
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();
    }

    @Test(timeOut = 60000)
    public void testBroadcasterScope() {
        System.out.println("Running testBroadcasterScope");
        final CountDownLatch latch = new CountDownLatch(2);
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            final AtomicReference<Response> response = new AtomicReference<Response>();
            c.prepareGet(urlTarget + "/scope").execute(new AsyncCompletionHandler<Response>() {

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

            final AtomicReference<Response> response2 = new AtomicReference<Response>();
            c.prepareGet("http://localhost:9999/suspend2/scope").execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response r) throws Exception {
                    try {
                        response2.set(r);
                        return r;
                    } finally {
                        latch.countDown();
                    }
                }
            });

            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }

            Response r = response.get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            assertEquals(r.getResponseBody(), "bar");

            Response r2 = response.get();
            assertNotNull(r2);
            assertEquals(r2.getStatusCode(), 200);
            assertEquals(r2.getResponseBody(), "bar");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }

}
