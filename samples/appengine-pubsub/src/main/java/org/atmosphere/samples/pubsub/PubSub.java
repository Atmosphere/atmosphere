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
package org.atmosphere.samples.pubsub;

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Cluster;
import org.atmosphere.annotation.Schedule;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.JerseyBroadcaster;
import org.atmosphere.util.StringFilterAggregator;

import javax.annotation.PreDestroy;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Simple PubSub resource that demonstrate many functionality supported by
 * Atmosphere.
 * 
 * @author Jeanfrancois Arcand
 */
@Path("/{topic}")
@Produces("text/plain;charset=ISO-8859-1")
public class PubSub {

    @PreDestroy
    public void destroy(){
        System.out.println("Testing the @PreDestroy");
    }
    
    /**
     * Inject a {@link org.atmosphere.cpr.Broadcaster} based on @Path
     */
    private @PathParam("topic") Broadcaster topic;

    /**
     * Suspend the response, and register a {@link org.atmosphere.cpr.AtmosphereResourceEventListener}
     * that get notified when events occurs like client disconnection, broadcast
     * or when the response get resumed.
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @GET
    @Suspend(listeners={EventsLogger.class})
    public Broadcastable subscribe() {
        return new Broadcastable(topic);
    }

    /**
     * Suspend the response, and tell teh framework to resume the response                                                                                                                     \
     * when the first @Broadcast operation occurs.
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @GET
    @Suspend(resumeOnBroadcast=true,listeners={EventsLogger.class})
    @Path("subscribeAndResume")
    public Broadcastable subscribeAndResume() {
        return new Broadcastable(topic);
    }


    /**                                                                               '
     * Broadcast XML data using JAXB
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @POST
    @Produces("application/xml")
    @Broadcast
    public Broadcastable publishWithXML(@FormParam("message") String message){
        return new Broadcastable(new JAXBBean(message),topic);
    }

    /**
     * Broadcast messahge to this server and also to other server using JGroups
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @POST
    @Broadcast
    public Broadcastable publish(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Retain Broadcast events until we have enough data. See the {@link org.atmosphere.util.StringFilterAggregator}
     * to configure the amount of data buffered before the events gets written
     * back to the set of suspended response.
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @POST
    @Broadcast(value={StringFilterAggregator.class})
    @Path("aggregate")
    public Broadcastable aggregate(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Execute periodic {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} operation and
     * resume the suspended connection after the first broadcast operation.
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @Schedule(period=5, resumeOnBroadcast=true)
    @POST
    @Path("scheduleAndResume")
    public Broadcastable scheduleAndResume(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Wait 5 seconds and then execute periodic {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
     * operations.
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @Schedule(period=10, waitFor=5)
    @POST
    @Path("delaySchedule")
    public Broadcastable delaySchedule(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Execute periodic {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} operation.
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @Schedule(period=5)
    @POST
    @Path("schedule")
    public Broadcastable schedule(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Delay for 5 seconds the executionof {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)} operation
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @Broadcast(delay=5)
    @POST
    @Path("delay")
    public Broadcastable delayPublish(@FormParam("message") String message){
        return broadcast(message);
    }

    @Broadcast(delay=5, resumeOnBroadcast=true)
    @POST
    @Path("delayAndResume")
    public Broadcastable delayPublishAndResume(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Buffer the first broadcast events until the second one happens.
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @Path("buffer")
    @POST
    @Broadcast(delay=0)
    public Broadcastable buffer(@FormParam("message") String message){
        return broadcast(message);
    }

    /**
     * Use the {@link org.atmosphere.cpr.Broadcaster#delayBroadcast(Object)} directly
     * instead of using the annotation.
     *
     * @param message A String from an HTML form
     * @return A {@link Broadcastable} used to broadcast events.
     */
    @POST
    @Path("broadcast")
    public String manualDelayBroadcast(@FormParam("message") String message){
        topic.delayBroadcast(message, 10, TimeUnit.SECONDS);
        return message;
    }

    /**
     * Create a new {@link Broadcastable}.
     * @param m
     * @return
     */
    Broadcastable broadcast(String m){
       return new Broadcastable(m + "\n", topic);
    }
} 
