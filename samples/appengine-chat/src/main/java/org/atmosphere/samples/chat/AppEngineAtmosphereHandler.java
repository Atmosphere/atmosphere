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
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.XSSHtmlFilter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Simple AtmosphereHandler that implement the logic to build a Chat application.
 *
 * @author Jeanfrancois Arcand
 */
public class AppEngineAtmosphereHandler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

    private final static String BEGIN_SCRIPT_TAG = "<script type='text/javascript'>\n";
    private final static String END_SCRIPT_TAG = "</script>\n";
    private final static long serialVersionUID = -2919167206889576860L;

    /**
     * When the {@link AtmosphereServlet} detect an {@link HttpServletRequest}
     * maps to this {@link AtmosphereHandler}, the  {@link AtmosphereHandler#onRequest}
     * gets invoked and the response will be suspended depending on the http
     * method, e.g. GET will suspend the connection, POST will broadcast chat
     * message to suspended connection.
     *
     * @param event An {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, 
            HttpServletResponse> event) throws IOException {

        HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();

        res.setContentType("text/html;charset=ISO-8859-1");
        res.addHeader("Cache-Control", "private");
        res.addHeader("Pragma", "no-cache");

        if (req.getMethod().equalsIgnoreCase("GET")) {
            event.suspend();

            Broadcaster bc = event.getBroadcaster();
            bc.getBroadcasterConfig().addFilter(new XSSHtmlFilter());
            bc.broadcast(event.getAtmosphereConfig().getWebServerName()
                    + "**has suspended a connection from "
                    + req.getRemoteAddr());
        } else if (req.getMethod().equalsIgnoreCase("POST")) {
            res.setCharacterEncoding("UTF-8");
            String action = req.getParameterValues("action")[0];
            String name = req.getParameterValues("name")[0];

            if ("login".equals(action)) {
                req.getSession().setAttribute("name", name);
                event.getBroadcaster().broadcast("System Message from "
                        + event.getAtmosphereConfig().getWebServerName() + "**" + name + " has joined.");
                res.getWriter().write("success");
                res.getWriter().flush();
            } else if ("post".equals(action)) {
                String message = req.getParameterValues("message")[0];
                event.getBroadcaster().broadcast(name + "**" + message);
                res.getWriter().write("success");
                res.getWriter().flush();
            } else {
                res.setStatus(422);

                res.getWriter().write("success");
                res.getWriter().flush();
            }
        }
    }

    /**
     * Invoked when a call to {@link Broadcaster#broadcast(java.lang.Object)} is
     * issued or when the response times out, e.g whne the value {@link AtmosphereResource#suspend(long)}
     * expires.
     *
     * @param event An {@link AtmosphereResourceEvent}
     * @throws java.io.IOException
     */
    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest,
            HttpServletResponse> event) throws IOException {

        HttpServletRequest req = event.getResource().getRequest();
        HttpServletResponse res = event.getResource().getResponse();

        if (event.getMessage() == null) return;

        String e = event.getMessage().toString();

        String name = e;
        String message = "";

        if (e.indexOf("**")> 0){
            name = e.substring(0,e.indexOf("**"));
            message = e.substring(e.indexOf("**")+2);
        }

        String msg = BEGIN_SCRIPT_TAG + toJsonp(name, message) + END_SCRIPT_TAG;

        if (event.isCancelled()){
            event.getResource().getBroadcaster()
                    .broadcast(req.getSession().getAttribute("name") + " has left");
        } else if (event.isResuming() || event.isResumedOnTimeout()) {
            String script = "<script>window.parent.app.listen();\n</script>";

            res.getWriter().write(script);
            res.getWriter().flush();
        } else {
            res.getWriter().write(msg);
            res.getWriter().flush();
        }
    }
    
    private String toJsonp(String name, String message) {
        return "window.parent.app.update({ name: \"" + name + "\", message: \"" + message + "\" });\n";
    }
}
