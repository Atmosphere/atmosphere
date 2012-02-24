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
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public abstract class BasePubSubTest extends BaseTest {

    protected static final Logger logger = LoggerFactory.getLogger(BasePubSubTest.class);

    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/invoke";
    }

    @Test(timeOut = 20000, enabled = true)
    public void testSuspendTimeout() {
        logger.info("{}: running test: testSuspendTimeout", getClass().getSimpleName());

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

    @Test(timeOut = 20000, enabled = true)
    public void testSuspendWithCommentsTimeout() {
        logger.info("{}: running test: testSuspendWithCommentsTimeout", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        try {
            Response r = c.prepareGet(urlTarget + "/withComments").execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            String[] ct = r.getContentType().toLowerCase().split(";");
            assertEquals(ct[0].trim(), "text/plain");
            assertEquals(resume, AtmosphereResourceImpl.createStreamingPadding(null));
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = false)
    public void testProgrammaticDisconnection() {
        logger.info("{}: running test: testProgrammaticDisconnection", getClass().getSimpleName());

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

    @Test(timeOut = 20000, enabled = true)
    public void testProgrammaticResume() {
        logger.info("{}: running test: testProgrammaticResume", getClass().getSimpleName());

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

                    logger.info("body part byte string: {}", new String(bp.getBodyPartBytes()));
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
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testResumeOnBroadcastUsingBroadcasterFactory() {
        logger.info("{}: running test: testResumeOnBroadcast", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        long t1 = System.currentTimeMillis();

        try {
            Response r = c.prepareGet(urlTarget + "/subscribeAndUsingExternalThread").execute().get();
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testDelayBroadcast() {
        logger.info("{}: running test: testDelayBroadcast", getClass().getSimpleName());

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
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createStreamingPadding(null) + "foo\nbar\n");
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testDelayNextBroadcast() {
        logger.info("{}: running test: testDelayNextBroadcast", getClass().getSimpleName());

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
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createStreamingPadding(null) + "foo\nbar\n");
            assertEquals(r.getStatusCode(), 200);
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testScheduleBroadcast() {
        logger.info("{}: running test: testScheduleBroadcast", getClass().getSimpleName());

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
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testBroadcastFilter() {
        logger.info("{}: running test: testBroadcastFilter", getClass().getSimpleName());

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
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testAggregateFilter() {
        logger.info("{}: running test: testAggregateFilter", getClass().getSimpleName());

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
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testHeaderBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        logger.info("{}: running test: testHeaderBroadcasterCache", getClass().getSimpleName());

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
            Response r = c.prepareGet(urlTarget + "/subscribeAndResume").addHeader(HeaderConfig.X_CACHE_DATE, String.valueOf(t1)).execute(new AsyncCompletionHandler<Response>() {

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
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

    @Test(timeOut = 20000, enabled = true)
    public void testProgrammaticDelayBroadcast() {
        logger.info("{}: running test: testDelayBroadcast", getClass().getSimpleName());

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
            assertEquals(r.getResponseBody(), AtmosphereResourceImpl.createStreamingPadding(null) + "foobar\n");
            assertEquals(r.getStatusCode(), 200);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }
        c.close();
    }
}
