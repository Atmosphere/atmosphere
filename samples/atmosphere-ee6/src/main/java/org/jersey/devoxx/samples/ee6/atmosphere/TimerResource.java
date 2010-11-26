package org.jersey.devoxx.samples.ee6.atmosphere;

import java.util.Date;
import java.util.concurrent.Semaphore;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.JerseyBroadcaster;

/**
 * curl -N -v http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer
 * 
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/start
 * 
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/stop
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/hardstop
 *
 * @author Paul Sandoz
 */
@Path("timer")
@Stateless
public class TimerResource {

    private @Resource TimerService ts;

    private @Context BroadcasterFactory bf;

    private Semaphore started = new Semaphore(1);
    
    private Semaphore stopped = new Semaphore(1);

    private Broadcaster tb;
    
    private Timer t;

    private @PostConstruct void postConstruct() {
        stopped.tryAcquire();
    }

    private Broadcaster getTimerBroadcaster() {
        return bf.lookup(JerseyBroadcaster.class, "timer", true);
    }
    
    @Suspend
    @GET
    public Broadcastable get() {
        return new Broadcastable(getTimerBroadcaster());
    }

    @Path("start")
    @POST
    public void start() {
        if (started.tryAcquire()) {
            tb = getTimerBroadcaster();
            t = ts.createIntervalTimer(1000, 1000, new TimerConfig("timer", false));
            stopped.release();
        }
    }

    @Timeout
    public void timeout(Timer timer) {
        System.out.println(getClass().getName() + ": " + new Date());
        tb.broadcast(new Date().toString() + "\n");
    }

    @Path("stop")
    @POST
    public void stop() {
        if (stopped.tryAcquire()) {
            t.cancel();
            tb = null;
            t = null;
            started.release();
        }
    }

    @Path("hardstop")
    @POST
    public void hardstop() {
        stop();
        
        getTimerBroadcaster().resumeAll();
    }
}