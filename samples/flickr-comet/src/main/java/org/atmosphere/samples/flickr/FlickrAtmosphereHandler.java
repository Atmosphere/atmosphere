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
package org.atmosphere.samples.flickr;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


/**
 * Simple Flickr Magnet Demo.
 *
 * @author Jeanfrancois Arcand
 */
public class FlickrAtmosphereHandler implements AtmosphereHandler<HttpServletRequest,HttpServletResponse> {

    private static final Logger logger = LoggerFactory.getLogger(FlickrAtmosphereHandler.class);

    private int counter;

    public void onRequest(AtmosphereResource<HttpServletRequest,
            HttpServletResponse> event) throws IOException {
        HttpServletRequest req = event.getRequest();
        HttpServletResponse res = event.getResponse();
        logger.info("onEvent: {}", req.getRequestURI());

        String[] actionValues = req.getParameterValues("action");
        if (actionValues != null && actionValues[0] != null) {
            String action = req.getParameterValues("action")[0];
            if ("post".equals(action)) {
                String message = req.getParameterValues("message")[0];
                String callback = req.getParameterValues("callback")[0];
                if (callback == null) {
                    callback = "alert";
                }

                event.getBroadcaster().broadcast("<script id='comet_" + counter++ + "'>"
                        + "window.parent." 
                        + callback + "(" + message + ");</script>");
                res.getWriter().write("ok");
                res.getWriter().flush();
            } else if ("start".equals(action)) {
                res.setContentType("text/html;charset=ISO-8859-1");
                res.addHeader("Cache-Control", "private");
                res.addHeader("Pragma", "no-cache");
                String callback = req.getParameterValues("callback")[0];
                if (callback == null) {
                    callback = "alert";
                }
                
                event.suspend();
                String message = "{ message : 'Welcome'}";
                res.getWriter().write("<script id='comet_" + counter++ + "'>" 
                        + "window.parent."
                        + callback + "(" + message + ");</script>");
                res.getWriter().write("<html><head><title>Atmosphere Flickr " +
                        "Demo</title></head><body bgcolor=\"#FFFFFF\">");
                res.getWriter().flush();
            }
        }
    }

    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest,
            HttpServletResponse> event) throws IOException {

        HttpServletResponse res = event.getResource().getResponse();
        if (event.isResuming()) {
            res.getWriter().write("Atmosphere Sample closed<br/>");
            res.getWriter().write("</body></html>");
        } else {
            res.getWriter().write(event.getMessage().toString());
        }
        res.getWriter().flush();
    }
}