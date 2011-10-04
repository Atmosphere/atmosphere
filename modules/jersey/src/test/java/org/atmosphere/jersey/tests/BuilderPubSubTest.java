package org.atmosphere.jersey.tests;

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Resume;
import org.atmosphere.annotation.Schedule;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.JerseyBroadcaster;
import org.atmosphere.jersey.SuspendResponse;
import org.atmosphere.util.StringFilterAggregator;
import org.atmosphere.util.XSSHtmlFilter;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Path("/builder/{topic}")
@Produces("text/plain;charset=ISO-8859-1")
public class BuilderPubSubTest {

    private
    @PathParam("topic")
    Broadcaster broadcaster;

    @GET
    public SuspendResponse<String> suspendUsingAPI() {

        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .entity("resume")
                .broadcaster(broadcaster)
                .outputComments(false)
                .resumeOnBroadcast(true)
                .period(5, TimeUnit.SECONDS)
                .build();

        return r;
    }

    @GET
    @Path("scope")
    public SuspendResponse<String> suspendScopeRequestWithAPI(@PathParam("topic") Broadcaster b) throws ExecutionException, InterruptedException {

        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .entity("bar")
                .broadcaster(broadcaster)
                .scope(Suspend.SCOPE.REQUEST)
                .outputComments(false)
                .resumeOnBroadcast(true)
                .period(5, TimeUnit.SECONDS)
                .build();

        b.broadcast("foo").get();
        return r;
    }

    @GET
    @Path("withComments")
    public SuspendResponse<String> subscribeWithCommentsWithAPI() {
        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(broadcaster)
                .outputComments(true)
                .period(5, TimeUnit.SECONDS)
                .build();

        return r;
    }

    @GET
    @Path("forever")
    public SuspendResponse<String> suspendForeverWithAPI() {
        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(broadcaster)
                .outputComments(true)
                .entity("")
                .build();

        return r;
    }

    @GET
    @Path("foreverWithoutComments")
    public SuspendResponse<String> suspendForeverWithoutCommentsWithAPI() {
        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(broadcaster)
                .outputComments(false)
                .entity("")
                .build();

        return r;
    }

    @GET
    @Path("subscribeAndUsingExternalThread")
    public SuspendResponse<String> subscribeAndResumeUsingExternalThreadWithAPI(final @PathParam("topic") String topic) {
        Executors.newSingleThreadExecutor().submit(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                }
                BroadcasterFactory.getDefault().lookup(JerseyBroadcaster.class, topic).broadcast("Echo: " + topic);
            }
        });

        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(broadcaster)
                .resumeOnBroadcast(true)
                .entity("foo")
                .build();

        return r;
    }

    @GET
    @Path("suspendAndResume")
    @Suspend(outputComments = false)
    public SuspendResponse<String> suspendWithAPI() {
        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .outputComments(false)
                .entity("suspend")
                .build();

        return r;
    }

    @GET
    @Path("subscribeAndResume")
    public SuspendResponse<String> subscribeAndResumeWithAPI() {
        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .outputComments(false)
                .resumeOnBroadcast(true)
                .build();

        return r;
    }

    @GET
    @Resume
    @Path("suspendAndResume/{uuid}")
    public String resume() throws ExecutionException, InterruptedException {
        broadcaster.broadcast("resume").get();
        return "resumed";
    }

    @POST
    @Broadcast
    public Broadcastable publish(@FormParam("message") String message) {
        return broadcast(message);
    }

    @POST
    @Path("publishAndResume")
    @Broadcast(resumeOnBroadcast = true)
    public Broadcastable publishAndResume(@FormParam("message") String message) {
        return broadcast(message);
    }

    @POST
    @Path("filter")
    @Broadcast(resumeOnBroadcast = true, value = {XSSHtmlFilter.class})
    public Broadcastable filter(@FormParam("message") String message) {
        return broadcast(message);
    }

    @POST
    @Path("aggregate")
    @Broadcast(resumeOnBroadcast = true, value = {StringFilterAggregator.class})
    public Broadcastable aggregate(@FormParam("message") String message) {
        return broadcast(message);
    }

    @Schedule(period = 5, resumeOnBroadcast = true, waitFor = 5)
    @POST
    @Path("scheduleAndResume")
    public Broadcastable scheduleAndResume(@FormParam("message") String message) {
        return broadcast(message);
    }

    @Schedule(period = 10, waitFor = 5)
    @POST
    @Path("delaySchedule")
    public Broadcastable delaySchedule(@FormParam("message") String message) {
        return broadcast(message);
    }

    @Schedule(period = 5)
    @POST
    @Path("schedule")
    public Broadcastable schedule(@FormParam("message") String message) {
        return broadcast(message);
    }

    @Broadcast(delay = 0)
    @POST
    @Path("delay")
    public Broadcastable delayPublish(@FormParam("message") String message) {
        return broadcast(message);
    }

    @Broadcast(delay = 5, resumeOnBroadcast = true)
    @POST
    @Path("delayAndResume")
    public Broadcastable delayPublishAndResume(@FormParam("message") String message) {
        return broadcast(message);
    }

    @POST
    @Path("programmaticDelayBroadcast")
    public String manualDelayBroadcast(@FormParam("message") String message) {
        broadcaster.delayBroadcast(message);
        return message;
    }

    Broadcastable broadcast(String m) {
        return new Broadcastable(m + "\n", broadcaster);
    }

    @GET
    @Path("204")
    public SuspendResponse<String> suspend204() {

        SuspendResponse<String> r = new SuspendResponse.SuspendResponseBuilder<String>()
                .broadcaster(broadcaster)
                .outputComments(false)
                .resumeOnBroadcast(true)
                .period(5, TimeUnit.SECONDS)
                .build();

        return r;
    }
}
