package org.atmosphere.samples.twitter;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.SuspendResponse;
import org.codehaus.jettison.json.JSONObject;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Path("/search/{tagid}")
@Produces("text/html;charset=ISO-8859-1")
@Singleton
public class TwitterFeed {

    private final AsyncHttpClient asyncClient = new AsyncHttpClient();

    @GET
    public SuspendResponse<String> search(final @PathParam("tagid") Broadcaster feed,
                                          final @PathParam("tagid") String tagid) {

        if (feed.getAtmosphereResources().size() == 0) {

            feed.scheduleFixedBroadcast(new Callable<String>(){

                private final AtomicReference<String> refreshUrl = new AtomicReference<String>("");

                public String call() throws Exception
                {
                    String query = null;
                    if (!refreshUrl.get().isEmpty()) {
                        query = refreshUrl.get();
                    } else {
                        query = "?q=" + tagid;
                    }
                    asyncClient.prepareGet("http://search.twitter.com/search.json" + query).execute(
                            new AsyncCompletionHandler<Object>() {

                        @Override
                        public Object onCompleted(Response response) throws Exception {
                            // Parse
                            // Broadcast
                            String s = response.getResponseBody();
                            JSONObject json = new JSONObject(s);
                            refreshUrl.set(json.getString("refresh_url"));

                            feed.broadcast(s).get();
                            return null;
                        }

                    });
                    return null;
                }

            }, 1, TimeUnit.SECONDS);
            
        }

        return new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(feed)
                .outputComments(true)
                .addListener(new EventsLogger())
                .build();
    }

//    @POST
//    public ??? newTweet(@FormParam("tweet") String message) {
//        return new Broadcastable(message, "", feed);
//    }



}