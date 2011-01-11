/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
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
 *
 */
package org.atmosphere.samples.twitter;

import com.sun.jersey.spi.resource.Singleton;
import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.samples.twitter.UsersState.UserStateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

/**
 * Twitter like Comet application. This rest based web application implements the logic
 * needed to support micro blogging a la Twitter.com. Users can blog about what 
 * they are doing and can also follow their friends. When an update is made
 * by one user, all its follower gets updated automatically. The updated words 
 * can be moved on the screen and all follower will see the move. 
 * 
 * This {@link Singleton} demonstrate how multiple {@link Broadcaster} can be
 * used to easily isolate suspended connection and to only
 * push messages to a subset of those suspended connection using the {@link Suspend} annotation.
 * 
 * There is one {@link AtmosphereResourceEvent} per user. {@link AtmosphereResourceEvent} associated
 * with the user suspended connection are added to their {@link Broadcaster}
 * and added to the {@link Broadcaster} of the users they are following. 
 *
 * @author Jeanfrancois Arcand
 * @author Paul Sandoz
 */
@Path("/twitter")
@Singleton
public class TwitterResource {

    private static final Logger logger = LoggerFactory.getLogger(TwitterResource.class);

    // Simple transaction counter
    private int counter;
    // Begin Script
    private static final String BEGIN_SCRIPT_TAG = "<script type='text/javascript'>\n";
    //End script
    private static final String END_SCRIPT_TAG = "</script>\n";
    // Unique id
    private static final long serialVersionUID = -2919167206889576860L;
    // Before suspending message
    private String startingMessage = "<html><head><title>Twitter</title></head><body bgcolor=\"#FFFFFF\">";

    private UsersState us = new UsersState();

    public TwitterResource() {
    }

    /**
     * When the page loads, suspend the response and set the {@link Suspend} scope
     * to REQUEST, which means every suspended connection by default have their
     * own instance of {@link Broadcaster}
     */
    @Suspend(scope=Suspend.SCOPE.REQUEST)
    @GET
    @Produces("text/html;charset=ISO-8859-1")
    public String onStart(@QueryParam("callback") String callback) {
        String message = "{ message : 'Welcome'}";
        if (callback == null) {
            callback = "alert";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(startingMessage);
        sb.append("<script id='comet_" + counter++
                + "'>" + "window.parent." + callback + "(" + message + ");</script>\n");
        return sb.toString();
    }

    /**
     * Store the user information and broadcast a welcome message using the
     * already suspended connection done {@link #onStart}
     */
    @Path("login")
    @Broadcast
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("text/plain;charset=ISO-8859-1")
    public Broadcastable onLogin(@Context TwitterBroadcaster bc,
                                 @FormParam("name") String name) {

        UserStateData usd = us.create(name,(TwitterBroadcaster)bc);
        // User already exists, client error
        if (usd == null)
            throw new WebApplicationException(400);

        if (name == null) {
            logger.error("Name cannot be null");
            throw new WebApplicationException(400);
        }

        bc.setID(name);
        String m = BEGIN_SCRIPT_TAG + toJsonp("Welcome back", name) + END_SCRIPT_TAG;
        Broadcastable b = new Broadcastable(m, bc);
        return b;
    }

    /**
     * Add a follower, using the user's associated {@link Broadcaster}
     * @param name
     * @param followee
     * @return
     */
    @Path("follows")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("text/html;charset=ISO-8859-1")
    @Broadcast
    public Broadcastable onFollow(@Context TwitterBroadcaster userBc,
                                  @FormParam("name") String name,
                                  @FormParam("followee") String followee) {

        if (followee == null) {
            logger.error("Message cannot be null");
            throw new WebApplicationException(400);
        }

        if (name == null) {
            logger.error("Name cannot be null");
            throw new WebApplicationException(400);
        }

        UserStateData followeeData = us.get(followee);
        TwitterBroadcaster outsiderBroadcaster = followeeData.bc;
        if (outsiderBroadcaster == null) {
            String m = (BEGIN_SCRIPT_TAG + toJsonp("Invalid Twitter user ", followee) + END_SCRIPT_TAG);
            Broadcastable b = new Broadcastable(m, userBc);
            return b;
        }

        outsiderBroadcaster.broadcast(BEGIN_SCRIPT_TAG
                        + toJsonp(name, " is now following " + followee)
                        + END_SCRIPT_TAG);

        outsiderBroadcaster.addAtmosphereResource(userBc.getUserAtmosphereEvent().getResource());

        String m = (BEGIN_SCRIPT_TAG
                        + toJsonp("You are now following ", followee)
                        + END_SCRIPT_TAG);
        Broadcastable b = new Broadcastable(m, userBc);
        return b;
    }

    /**
     * Push tweet to the user and its followers.
     * @param bc The {@link Broadcaster}
     * @param message The message to broadcast
     * @param callback The calback
     * @return a {@link Broadcastable}
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("text/html;charset=ISO-8859-1")
    @Broadcast
    public Broadcastable onPush(@Context TwitterBroadcaster bc,
                                @FormParam("message") String message,
                                @FormParam("callback") String callback){
        
        if (message == null) {
            logger.error("Message cannot be null");
            throw new WebApplicationException(400);
        }

        if (callback == null) {
            callback = "alert";
        }

        String m = "<script id='comet_" + counter++ + "'>" + "window.parent."
                + callback + "(" + message + ");</script>";

        Broadcastable b = new Broadcastable(m, bc);
        return b;
    }

    /**
     * Escape any maliscious characters.
     * @param orig the String
     * @return a well formed String.
     */
    private String escape(String orig) {
        StringBuilder buffer = new StringBuilder(orig.length());

        for (int i = 0; i < orig.length(); i++) {
            char c = orig.charAt(i);
            switch (c) {
                case '\b':
                    buffer.append("\\b");
                    break;
                case '\f':
                    buffer.append("\\f");
                    break;
                case '\n':
                    buffer.append("<br />");
                    break;
                case '\r':
                    // ignore
                    break;
                case '\t':
                    buffer.append("\\t");
                    break;
                case '\'':
                    buffer.append("\\'");
                    break;
                case '\"':
                    buffer.append("\\\"");
                    break;
                case '\\':
                    buffer.append("\\\\");
                    break;
                case '<':
                    buffer.append("&lt;");
                    break;
                case '>':
                    buffer.append("&gt;");
                    break;
                case '&':
                    buffer.append("&amp;");
                    break;
                default:
                    buffer.append(c);
            }
        }
        return buffer.toString();
    }

    /**
     * Simple JSOn transformation.
     * @param name
     * @param message
     * @return the JSON representation.
     */
    private String toJsonp(String name, String message) {
        return "window.parent.app.update({ name: \"" + escape(name) + "\", message: \"" + escape(message) + "\" });\n";
    }
}
