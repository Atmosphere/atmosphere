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

package org.atmosphere.samples.counter;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple application that demonstrate how a Comet long poll request can be implemented.
 * Mainly, the client send a GET, the {@link AtmosphereHandler} suspend the connection. As
 * soon a POST request arrive, the underlying response is resumed.
 *
 * @author Jeanfrancois Arcand
 */
public class CounterLongPollingAtmosphereHandler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

    private final AtomicInteger currentCount = new AtomicInteger(0);

    /**
     * On GET, suspend the connection. On POST, resume the connection.
     *
     * @param resource
     * @return
     * @throws IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource) throws IOException {

        HttpServletRequest req = resource.getRequest();

        if (req.getMethod().equals("GET")) {
            resource.suspend(-1, false);
        } else {
            // 'POST' request
            if (req.getParameter("stop").equalsIgnoreCase("true")) {
                // this parameter is set true when a browser 'unload' event is triggered in the client
                resource.getBroadcaster().broadcast("");
            } else if (req.getParameter("current_count") != null) {
                Integer i = Integer.valueOf(req.getParameter("current_count"));

                if (i > currentCount.get()) {
                    currentCount.set(i);
                }

                // Always Broadcast the highest value
                resource.getBroadcaster().broadcast(i > currentCount.get() ? i : currentCount.get());
            }

            PrintWriter writer = resource.getResponse().getWriter();
            writer.write("success");
            writer.flush();
        }
    }

    /**
     * Resume the underlying response on the first {@link org.atmosphere.cpr.Broadcaster}
     *
     * @param event
     * @return
     * @throws IOException
     */
    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest,
            HttpServletResponse> event) throws IOException {

        // Client closed the connection.
        if (event.isCancelled()) {
            return;
        }

        int count = 0;
        if (event.getMessage() instanceof Integer) {
            count = ((Integer) event.getMessage()).intValue();
        }

        try {
            event.getResource().getResponse().addHeader("X-JSON", "{\"counter\":" + count + " }");
            PrintWriter writer = event.getResource().getResponse().getWriter();
            writer.write("success");
            writer.flush();
        } finally {
            if (!event.isResumedOnTimeout()) {
                event.getResource().resume();
            }
        }

    }

    public void destroy() {
    }

}
