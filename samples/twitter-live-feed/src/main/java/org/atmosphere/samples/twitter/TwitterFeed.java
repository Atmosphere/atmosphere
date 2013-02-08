/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.samples.twitter;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.SuspendResponse;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Path("/search/{tagid}")
@Produces("text/html;charset=ISO-8859-1")
@Singleton
public class TwitterFeed {

    private static final Logger logger = LoggerFactory.getLogger(TwitterFeed.class);

    private final AsyncHttpClient asyncClient = new AsyncHttpClient();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<String, Future<?>>();
    private final CountDownLatch suspendLatch = new CountDownLatch(1);

    @GET
    public SuspendResponse<String> search(final @PathParam("tagid") Broadcaster feed,
                                          final @PathParam("tagid") String tagid) {

        if (tagid.isEmpty()) {
            throw new WebApplicationException();
        }

        if (feed.getAtmosphereResources().size() == 0) {

            final Future<?> future = feed.scheduleFixedBroadcast(new Callable<String>() {

                private final AtomicReference<String> refreshUrl = new AtomicReference<String>("");

                public String call() throws Exception {
                    String query = null;
                    if (!refreshUrl.get().isEmpty()) {
                        query = refreshUrl.get();
                    } else {
                        query = "?q=" + tagid;
                    }

                    // Wait for the connection to be suspended.
                    suspendLatch.await();
                    asyncClient.prepareGet("http://search.twitter.com/search.json" + query).execute(
                            new AsyncCompletionHandler<Object>() {

                                @Override
                                public Object onCompleted(Response response) throws Exception {
                                    String s = response.getResponseBody();

                                    if (response.getStatusCode() != 200) {
                                        feed.resumeAll();
                                        feed.destroy();
                                        logger.info("Twitter Search API unavaileble\n{}", s);
                                        return null;
                                    }

                                    JSONObject json = new JSONObject(s);
                                    refreshUrl.set(json.getString("refresh_url"));
                                    if (json.getJSONArray("results").length() > 1) {
                                        feed.broadcast(s).get();
                                    }
                                    return null;
                                }

                            });
                    return null;
                }

            }, 5, TimeUnit.SECONDS);

            futures.put(tagid, future);
        }

        return new SuspendResponse.SuspendResponseBuilder<String>().broadcaster(feed).outputComments(true)
                .addListener(new EventsLogger() {

                    @Override
                    public void onSuspend(
                            final AtmosphereResourceEvent event) {
                        super.onSuspend(event);

                        // OK, we can start polling Twitter!
                        suspendLatch.countDown();
                    }
                }).build();
    }

    @GET
    @Path("stop")
    public String stopSearch(final @PathParam("tagid") Broadcaster feed,
                             final @PathParam("tagid") String tagid) {
        feed.resumeAll();
        if (futures.get(tagid) != null) {
            futures.get(tagid).cancel(true);
        }
        logger.info("Stopping real time update for {}", tagid);
        return "DONE";
    }
}