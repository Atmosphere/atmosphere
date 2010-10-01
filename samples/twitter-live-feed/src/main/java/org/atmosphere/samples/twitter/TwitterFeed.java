package org.atmosphere.samples.twitter;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.SuspendResponse;
import org.codehaus.jettison.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Path("/search/{tagid}")
@Produces("text/html;charset=ISO-8859-1")
@Singleton
public class TwitterFeed {

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
                                        System.out.println("Twitter Search API unavaileble\n" + s);
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

            }, 1, TimeUnit.SECONDS);

            futures.put(tagid, future);
        }

        return new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(feed)
                .outputComments(true)
                .addListener(new EventsLogger() {

                    @Override
                    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
                        System.out.println("onSuspend: " + event.getResource().getRequest().getRemoteAddr()
                                + event.getResource().getRequest().getRemotePort());

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
        futures.get(tagid).cancel(true);
        System.out.println("Stopping real time update for " + tagid);
        return "DONE";
    }
}