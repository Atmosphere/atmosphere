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

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.util.LoggerUtils;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Twitter like Comet application. This {@link AtmosphereHandler} implement the logic 
 * needed to support micro blogging a la Twitter.com. Users can blog about what 
 * they are doing and can also follow their friends. When an update is made
 * by one user, all its follower gets updated automatically. The updated words 
 * can be moved on the screen and all follower will see the move. 
 * 
 * This {@link Servlet} demonstrate how multiple {@link Broadcaster} can be 
 * used to easily isolate suspended connection and to only
 * push messages to a subset of those suspended connection. It also demonstrate
 * how to push messages to a single {@link AtmosphereResourceEvent}
 * 
 * There is one {@link AtmosphereResourceEvent} per user. {@link AtmosphereResourceEvent} associated
 * with the user suspended connection are added to their {@link Broadcaster}
 * and added to the {@link Broadcaster} of the users they are following. 
 *
 * @author Jeanfrancois Arcand
 */
public class TwitterAtmosphereHandler extends AbstractReflectorAtmosphereHandler {
    
    // Simple transaction counter
    private int counter;
    
    // Begin Script
    private static final String BEGIN_SCRIPT_TAG = "<script type='text/javascript'>\n";
    
    //End script
    private static final String END_SCRIPT_TAG = "</script>\n";
    
    // Atmosphere Logger
    private static final Logger logger = LoggerUtils.getLogger();
    
    // Unique id
    private static final long serialVersionUID = -2919167206889576860L;
    
    // Before suspending message
    private String startingMessage = "<html><head><title>Twitter</title></head><body bgcolor=\"#FFFFFF\">";
    
    // When terminate or interrupted atmoResource happens.
    private String endingMessage = "Twitter closed<br/>\n</body></html>";

    public TwitterAtmosphereHandler() {
    }

    /**
     * Based on the {@link HttpServletRequest#getParameter} action value, decide
     * if the connection needs to be suspended (when the user logs in) or if the 
     * {@link Broadcaster} needs to be updated by the user or by its follower.
     * 
     * There is one {@link Broadcaster} per suspended connection, representing 
     * the user account. When one user B request to follow user A, the {@link AtmosphereResource}
     * associated with user B's {@link Broadcaster} is also added to user A
     * {@link Broadcaster}. Hence when user A push message ({@link Broadcaster#broadcast(Object)}
     * all {@link AtmosphereResource} gets the {@link AtmosphereResourceEvent}, which means user B
     * will be updated when user A update its micro blog.
     * 
     * The suspended connection on the client side is multiplexed, e.g. 
     * messages sent by the server are not only for a single component, but
     * shared amongst several components. The client side include a message board
     * that is updated by notifying the owner of the {@link Broadcaster}. This
     * is achieved by calling {@link Broadcaster#broadcast(Object)}
     * 
     * @param atmoResource An {@link AtmosphereResourceEvent}
     * @throws java.io.IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> atmoResource) throws IOException{
        HttpServletRequest request = atmoResource.getRequest();
        HttpServletResponse response = atmoResource.getResponse();        
        
        String action = request.getParameter("action");      

        String sessionId = request.getSession().getId();
        HttpSession session = request.getSession();
        Broadcaster myBroadcasterFollower = (Broadcaster) session.getAttribute(sessionId);
        if (action != null) {
            if ("login".equals(action)) {
                response.setContentType("text/plain");
                response.setCharacterEncoding("UTF-8");
                               
                String name = request.getParameter("name"); 
                
                if (name == null) {
                    logger.severe("Name cannot be null");
                    return;
                }
                
                session.setAttribute("name", name);
                
                myBroadcasterFollower.broadcast(BEGIN_SCRIPT_TAG
                        + toJsonp("Welcome back", name) 
                        + END_SCRIPT_TAG);

                // Store the Broadcaster associated with this user so
                // we can retrieve it for supporting follower.
                atmoResource.getAtmosphereConfig().getServletContext().setAttribute(name, myBroadcasterFollower);
            } else if ("post".equals(action)) {
                String message = request.getParameter("message");
                String callback = request.getParameter("callback");
                
                if (message == null) {
                    logger.severe("Message cannot be null");
                    return;
                }
                
                if (callback == null) {
                    callback = "alert";
                }

                if (myBroadcasterFollower != null){
                    myBroadcasterFollower.broadcast("<script id='comet_" + counter++ + "'>" 
                            + "window.parent." + callback + "(" + message + ");</script>");
                } else {
                    throw new RuntimeException("Broadcaster was null");
                }
                response.getWriter().println("ok");
            } else if ("start".equals(action)) {
                String message = "{ message : 'Welcome'}";              
                response.setContentType("text/html;charset=ISO-8859-1");
                atmoResource.suspend();
                
                String callback = request.getParameter("callback");
                if (callback == null) {
                    callback = "alert";
                }

                response.getWriter().println("<script id='comet_" + counter++ + "'>" 
                        + "window.parent." + callback + "(" + message + ");</script>");
                response.getWriter().println(startingMessage);
                response.getWriter().flush();
                
                // Use one Broadcaster per AtmosphereResource
                try {
                    atmoResource.setBroadcaster(BroadcasterFactory.getDefault().get());
                } catch (Throwable t){
                    throw new IOException(t);
                }

                // Create a Broadcaster based on this session id.
                myBroadcasterFollower = atmoResource.getBroadcaster();
                
                session.setAttribute("atmoResource", atmoResource);
                session.setAttribute(sessionId, myBroadcasterFollower);
            } else if ("following".equals(action)) {
                response.setContentType("text/html");
                String follow = request.getParameter("message");
                String name = (String)session.getAttribute("name");
                
                if (follow == null) {
                    logger.severe("Message cannot be null");
                    return;
                }      
                
                if (name == null) {
                    logger.severe("Name cannot be null");
                    return;
                }
                
                Broadcaster outsiderBroadcaster 
                        = (Broadcaster) atmoResource.getAtmosphereConfig().getServletContext().getAttribute(follow);
                                    
                AtmosphereResource r = (AtmosphereResource)session.getAttribute("atmoResource");
                if (outsiderBroadcaster == null){
                    myBroadcasterFollower.broadcast(BEGIN_SCRIPT_TAG
                        + toJsonp("Invalid Twitter user ", follow)
                        + END_SCRIPT_TAG, r);
                    return;
                }

                outsiderBroadcaster.addAtmosphereResource(r);
                
                myBroadcasterFollower.broadcast(BEGIN_SCRIPT_TAG
                        + toJsonp("You are now following ", follow)
                        + END_SCRIPT_TAG, r);
                
                outsiderBroadcaster.broadcast(BEGIN_SCRIPT_TAG
                        + toJsonp(name, " is now following " + follow)
                        + END_SCRIPT_TAG);
            }
        }
    }

    /**
     * Escape any maliscious characters.
     * @param orig the String
     * @return a well formed String.
     */
    private String escape(String orig) {
        StringBuffer buffer = new StringBuffer(orig.length());

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
        return "window.parent.app.update({ name: \""
                + escape(name) + "\", message: \"" + escape(message) + "\" });\n";
    }
}
